package com.example.elbowstrike;

import com.example.elbowstrike.network.NetworkHandler;
import com.example.elbowstrike.network.SpinStartPacket;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 肘击核心逻辑。
 * <p>
 * 通过 {@code @Mod.EventBusSubscriber} 注册玩家登出事件，用于清理冷却 Map，
 * 避免长期运行的服务器上 {@link #COOLDOWNS} 无限增长。
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID)
public final class ElbowStrikeHandler {

    /** 玩家 UUID -> 上次肘击时的 gameTime（用于冷却判定） */
    private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();

    private ElbowStrikeHandler() {}

    /**
     * 释放肘击。
     *
     * @param player     施法玩家
     * @param chargeTicks 客户端蓄力 tick 数（0 = 瞬发，满值由 chargeMaxTicks 决定）
     * @param dash       释放时是否按住前进键（W），true 则触发突进
     */
    public static void strike(ServerPlayer player, int chargeTicks, boolean dash) {
        Level level = player.level();
        long now = level.getGameTime();

        // 冷却（从 config 读）
        int cooldown = ElbowStrikeConfig.COMMON.cooldownTicks.get();
        Long last = COOLDOWNS.get(player.getUUID());
        if (last != null && now - last < cooldown) return;
        COOLDOWNS.put(player.getUUID(), now);

        player.swing(InteractionHand.MAIN_HAND, true);

        // 开启招架窗口（无论是否命中目标，肘击动作本身即开启窗口）
        ParryManager.openWindow(player);

        var cfg = ElbowStrikeConfig.COMMON;

        // ---- 突进：按住 W 蓄力释放时向前冲刺 ----
        // 判定在突进后立刻执行，命中位置就是玩家当前（起冲）位置；
        // 蓄力越长判定距离越远（见 chargeRangeBonus），
        // 因此冲刺途中前方目标已在范围内，不会出现「冲到面前却没伤害」。
        if (dash && cfg.enableChargeDash.get()) {
            float ratio = chargeRatioOf(chargeTicks);
            if (ratio > 0.05F) {
                applyDash(level, player, ratio);
            }
        }

        resolveStrike(player, chargeTicks);
    }

    /** 蓄力档位总数（与客户端 {@code ClientChargeHandler.CHARGE_TIERS} 保持一致） */
    private static final int CHARGE_TIERS = 5;

    /** 蓄力档位（1 ~ {@value #CHARGE_TIERS}）：把客户端连续蓄力 tick 量化到 5 档 */
    private static int chargeTierOf(int chargeTicks) {
        int max = ElbowStrikeConfig.COMMON.chargeMaxTicks.get();
        float progress = max > 0
                ? Math.min((float) Math.max(chargeTicks, 0) / max, 1.0F)
                : 0.0F;
        int tier = (int) (progress * CHARGE_TIERS) + 1;
        return Math.max(1, Math.min(tier, CHARGE_TIERS));
    }

    /** 档位对应的有效蓄力比例：1 档 = 0.0（基础值），5 档 = 1.0（满蓄力） */
    private static float chargeRatioOf(int chargeTicks) {
        return (chargeTierOf(chargeTicks) - 1) / (float) (CHARGE_TIERS - 1);
    }

    /**
     * 真正的命中判定与结算（在玩家当前位置、释放瞬间执行）。
     * <p>
     * 判定距离随蓄力比例增长：0 蓄力 = 配置的基础距离，满蓄力 = 基础距离 + 额外加成。
     * 这样蓄力除了加伤害，也扩大攻击范围，无需延迟即可命中冲刺前方的目标。
     */
    private static void resolveStrike(ServerPlayer player, int chargeTicks) {
        Level level = player.level();
        var cfg = ElbowStrikeConfig.COMMON;

        float chargeRatio = chargeRatioOf(chargeTicks);
        boolean fullCharge = chargeRatio >= 0.999F;

        List<LivingEntity> targets = findTargets(player, chargeRatio);
        if (targets.isEmpty()) {
            level.playSound(null,
                    player.getX(), player.getY(), player.getZ(),
                    ModSounds.ELBOW_STRIKE.get(),
                    SoundSource.PLAYERS,
                    0.6F, 1.2F);
            // 落空也来一簇前方粒子，让玩家能感知蓄力强度
            spawnWhiffEffects(level, player, chargeRatio);
            return;
        }

        float baseDamage = cfg.damage.get().floatValue();
        double hKnock = cfg.knockbackHorizontal.get();
        double vKnock = cfg.knockbackVertical.get();
        int spinDuration = cfg.spinDuration.get();

        // ---- 蓄力缩放（线性插值：0 蓄力 = 基础值，满蓄力 = 倍率值） ----
        float damageMult = 1.0F + (cfg.chargeDamageMult.get().floatValue() - 1.0F) * chargeRatio;
        double knockMult = 1.0 + (cfg.chargeKnockbackMult.get() - 1.0) * chargeRatio;

        float damage = baseDamage * damageMult;
        // ---- 满蓄力额外附加伤害 ----
        if (fullCharge) {
            damage += cfg.chargeFullBonusDamage.get().floatValue();
        }
        double hPower = hKnock * knockMult;
        double vPower = vKnock * knockMult;

        DamageSource source = level.damageSources().playerAttack(player);

        for (LivingEntity target : targets) {
            applyHit(player, target, source, damage, hPower, vPower, spinDuration, true);
            spawnTargetEffects(level, target, chargeRatio);
            // ---- 满蓄力眩晕（减速 + 虚弱）----
            if (fullCharge && cfg.chargeFullStun.get()) {
                applyStun(target, cfg.chargeFullStunTicks.get());
            }
        }

        // ---- 前方冲击波 ----
        spawnImpactEffects(level, player, chargeRatio);

        // ---- 音效（音调随蓄力升高，给玩家反馈） ----
        float pitch = 1.0F + chargeRatio * 0.4F;
        level.playSound(null,
                player.getX(), player.getY() + 1.0D, player.getZ(),
                ModSounds.ELBOW_STRIKE.get(),
                SoundSource.PLAYERS,
                1.0F, pitch);
    }

    // ================================================================
    // 突进 / 眩晕
    // ================================================================

    /**
     * 向前突进：沿视线水平方向给玩家一次速度，按住 W 蓄力释放时调用。
     * 只作用于水平方向，不产生跃起。
     */
    private static void applyDash(Level level, ServerPlayer player, float chargeRatio) {
        var cfg = ElbowStrikeConfig.COMMON;
        if (chargeRatio <= 0.05F) return;

        Vec3 look = player.getLookAngle();
        double speed = cfg.chargeDashSpeed.get() * chargeRatio;

        player.setDeltaMovement(look.x * speed, player.getDeltaMovement().y, look.z * speed);
        player.hasImpulse = true;
        player.hurtMarked = true;
        player.connection.send(new ClientboundSetEntityMotionPacket(player));

        level.playSound(null,
                player.getX(), player.getY() + 1.0D, player.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.8F, 1.5F);
    }

    /** 满蓄力眩晕：减速 + 虚弱，让目标短暂失去反击能力 */
    private static void applyStun(LivingEntity target, int ticks) {
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 4, false, true));
        target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 1, false, true));
    }

    // ================================================================
    // 命中特效（服务端生成粒子，自动下发给附近客户端）
    // ================================================================

    private static boolean effectsEnabled() {
        return ElbowStrikeConfig.COMMON.enableEffects.get();
    }

    /** 玩家前方 1.2 格、胸口高度的特效中心点 */
    private static Vec3 frontPoint(ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        return new Vec3(
                player.getX() + look.x * 1.2D,
                player.getY() + player.getEyeHeight() * 0.6D,
                player.getZ() + look.z * 1.2D
        );
    }

    /** 命中：前方横扫弧 + 水平冲击波环，强度随蓄力增长 */
    private static void spawnImpactEffects(Level level, ServerPlayer player, float chargeRatio) {
        if (!effectsEnabled() || !ElbowStrikeConfig.COMMON.impactParticles.get()) return;
        if (!(level instanceof ServerLevel sl)) return;

        Vec3 c = frontPoint(player);

        // 1) 横扫弧（类似剑的横扫粒子）
        int sweep = 4 + Math.round(chargeRatio * 12.0F);
        sl.sendParticles(ParticleTypes.SWEEP_ATTACK,
                c.x, c.y, c.z, sweep, 0.6D, 0.3D, 0.6D, 0.0D);

        // 2) 水平冲击波环，半径与数量随蓄力增长
        int ring = 8 + Math.round(chargeRatio * 24.0F);
        double radius = 0.5D + chargeRatio * 1.8D;
        for (int i = 0; i < ring; i++) {
            double a = Math.PI * 2.0D * i / ring;
            sl.sendParticles(ParticleTypes.END_ROD,
                    c.x + Math.cos(a) * radius, c.y, c.z + Math.sin(a) * radius,
                    1, 0.0D, 0.0D, 0.0D, 0.02D);
        }

        // 3) 满蓄力额外爆发
        if (chargeRatio >= 0.999F) {
            sl.sendParticles(ParticleTypes.SONIC_BOOM, c.x, c.y, c.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            sl.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                    c.x, c.y, c.z, 30, 0.8D, 0.6D, 0.8D, 0.15D);
        } else if (chargeRatio > 0.0F) {
            sl.sendParticles(ParticleTypes.CRIT,
                    c.x, c.y, c.z, Math.round(10.0F * chargeRatio), 0.5D, 0.4D, 0.5D, 0.08D);
        }
    }

    /** 每个命中目标身上的粒子 */
    private static void spawnTargetEffects(Level level, LivingEntity target, float chargeRatio) {
        if (!effectsEnabled() || !ElbowStrikeConfig.COMMON.impactParticles.get()) return;
        if (!(level instanceof ServerLevel sl)) return;

        double y = target.getY() + target.getBbHeight() * 0.6D;
        int count = 8 + Math.round(chargeRatio * 16.0F);
        sl.sendParticles(ParticleTypes.ENCHANTED_HIT,
                target.getX(), y, target.getZ(), count, 0.4D, 0.4D, 0.4D, 0.1D);
        if (chargeRatio >= 0.999F) {
            sl.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                    target.getX(), y, target.getZ(), 12, 0.3D, 0.4D, 0.3D, 0.1D);
        }
    }

    /** 落空：只在前方来一小簇粒子，也能感觉出蓄力 */
    private static void spawnWhiffEffects(Level level, ServerPlayer player, float chargeRatio) {
        if (!effectsEnabled() || !ElbowStrikeConfig.COMMON.impactParticles.get()) return;
        if (!(level instanceof ServerLevel sl)) return;

        Vec3 c = frontPoint(player);
        sl.sendParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 2, 0.3D, 0.2D, 0.3D, 0.0D);
        if (chargeRatio > 0.0F) {
            sl.sendParticles(ParticleTypes.CRIT,
                    c.x, c.y, c.z, Math.round(6.0F * chargeRatio), 0.4D, 0.3D, 0.4D, 0.05D);
        }
    }

    /**
     * 招架反击：对指定目标释放一次肘击。
     * <p>
     * 特点：
     * <ul>
     *     <li>不走冷却；</li>
     *     <li>不再开启招架窗口（避免连锁招架）。</li>
     * </ul>
     */
    public static void parryCounter(ServerPlayer player, LivingEntity attacker) {
        if (attacker == null || !attacker.isAlive()) return;

        Level level = player.level();
        var cfg = ElbowStrikeConfig.COMMON;
        double mult = cfg.parryCounterKnockbackMult.get();

        float baseDamage = cfg.damage.get().floatValue();
        double hKnock = cfg.knockbackHorizontal.get() * mult;
        double vKnock = cfg.knockbackVertical.get() * mult;
        int spinDuration = cfg.spinDuration.get();

        player.swing(InteractionHand.MAIN_HAND, true);

        // ---- 伤害 + 击退 + （可选）旋转 ----
        DamageSource source = level.damageSources().playerAttack(player);
        applyHit(player, attacker, source, baseDamage, hKnock, vKnock,
                spinDuration, cfg.parryCounterSpin.get());

        // ---- 反击音效（普通音调） ----
        level.playSound(
                null,
                player.getX(), player.getY() + 1.0D, player.getZ(),
                ModSounds.ELBOW_STRIKE.get(),
                SoundSource.PLAYERS,
                1.0F, 1.2F
        );
    }

    /**
     * 查找前方锥形范围内的目标。
     *
     * @param chargeRatio 蓄力比例（0 = 未蓄力，1 = 满蓄力），用于放大判定距离；
     *                    0 蓄力时为配置的基础距离
     */
    private static List<LivingEntity> findTargets(ServerPlayer player, float chargeRatio) {
        var cfg = ElbowStrikeConfig.COMMON;
        double range = cfg.range.get() + cfg.chargeRangeBonus.get() * chargeRatio;
        double cone = cfg.cone.get();
        return TargetFinder.findConeTargets(player, range, cone);
    }

    // ================================================================
    // 命中逻辑（strike 与 parryCounter 共用）
    // ================================================================

    /**
     * 对目标造成伤害，并施加击退与（可选）旋转。
     * <p>
     * 目标为玩家时通过网络包同步速度与旋转；非玩家生物直接改速度并交给
     * {@link SpinManager} 在服务端 tick 旋转。
     */
    private static void applyHit(ServerPlayer player, LivingEntity target,
                                 DamageSource source, float damage,
                                 double hKnock, double vKnock,
                                 int spinDuration, boolean spin) {
        target.invulnerableTime = 0;
        target.hurt(source, damage);
        applyKnockbackAndSpin(player, target, hKnock, vKnock, spinDuration, spin);
    }

    private static void applyKnockbackAndSpin(ServerPlayer player, LivingEntity target,
                                              double hKnock, double vKnock,
                                              int spinDuration, boolean spin) {
        Vec3 dir = knockbackDirection(player, target);

        target.setDeltaMovement(dir.x * hKnock, vKnock, dir.z * hKnock);
        target.hasImpulse = true;
        target.hurtMarked = true;

        if (!spin) return;

        if (target instanceof ServerPlayer sp) {
            sp.connection.send(new ClientboundSetEntityMotionPacket(sp));
            NetworkHandler.CHANNEL.sendTo(
                    new SpinStartPacket(spinDuration),
                    sp.connection.connection,
                    NetworkDirection.PLAY_TO_CLIENT
            );
        } else {
            SpinManager.startSpin(target, spinDuration);
        }
    }

    /**
     * 计算从玩家指向目标的水平击退方向（归一化）。
     * 若目标与玩家几乎重合，回退到玩家视线方向；仍退化则回退到 +Z。
     */
    private static Vec3 knockbackDirection(ServerPlayer player, LivingEntity target) {
        Vec3 dir = new Vec3(target.getX() - player.getX(), 0.0D, target.getZ() - player.getZ());
        if (dir.lengthSqr() < 1.0E-4D) {
            Vec3 look = player.getLookAngle();
            dir = new Vec3(look.x, 0.0D, look.z);
        }
        if (dir.lengthSqr() < 1.0E-4D) {
            dir = new Vec3(0.0D, 0.0D, 1.0D);
        }
        return dir.normalize();
    }

    // ================================================================
    // 玩家登出时清理冷却，避免 COOLDOWNS 无限增长
    // ================================================================
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            COOLDOWNS.remove(sp.getUUID());
        }
    }
}