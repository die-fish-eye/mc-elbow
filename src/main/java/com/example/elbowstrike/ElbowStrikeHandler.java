package com.example.elbowstrike;

import com.example.elbowstrike.network.NetworkHandler;
import com.example.elbowstrike.network.SpinStartPacket;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
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

    public static void strike(ServerPlayer player) {
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

        List<LivingEntity> targets = findTargets(player);
        if (targets.isEmpty()) {
            level.playSound(null,
                    player.getX(), player.getY(), player.getZ(),
                    ModSounds.ELBOW_STRIKE.get(),
                    SoundSource.PLAYERS,
                    0.6F, 1.2F);
            return;
        }

        var cfg = ElbowStrikeConfig.COMMON;
        float baseDamage = cfg.damage.get().floatValue();
        double hKnock = cfg.knockbackHorizontal.get();
        double vKnock = cfg.knockbackVertical.get();
        int spinDuration = cfg.spinDuration.get();

        DamageSource source = level.damageSources().playerAttack(player);
        boolean anyCritical = false;

        for (LivingEntity target : targets) {
            boolean critical = cfg.enableCrit.get() && isCritical(player, target);
            if (critical) anyCritical = true;

            float damage = critical
                    ? baseDamage * cfg.critDamageMult.get().floatValue()
                    : baseDamage;
            double hPower = critical ? hKnock * cfg.critKnockbackHorizontalMult.get() : hKnock;
            double vPower = critical ? vKnock * cfg.critKnockbackVerticalMult.get() : vKnock;

            applyHit(player, target, source, damage, hPower, vPower, spinDuration, true);

            // ---- 暴击粒子 ----
            if (critical && level instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(
                        ParticleTypes.CRIT,
                        target.getX(),
                        target.getY() + target.getBbHeight() * 0.5D,
                        target.getZ(),
                        15, 0.3D, 0.3D, 0.3D, 0.15D
                );
            }
        }

        // ---- 音效 ----
        float pitch = anyCritical ? cfg.critPitch.get().floatValue() : 1.0F;
        level.playSound(null,
                player.getX(), player.getY() + 1.0D, player.getZ(),
                ModSounds.ELBOW_STRIKE.get(),
                SoundSource.PLAYERS,
                1.0F, pitch);
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

    private static boolean isCritical(ServerPlayer player, LivingEntity target) {
        boolean fallingAttack = player.fallDistance > 0.0F
                && !player.onGround()
                && !player.onClimbable()
                && !player.isInWater()
                && !player.isPassenger();

        boolean airTarget = !target.onGround();

        return fallingAttack || airTarget;
    }

    private static List<LivingEntity> findTargets(ServerPlayer player) {
        double range = ElbowStrikeConfig.COMMON.range.get();
        double cone = ElbowStrikeConfig.COMMON.cone.get();
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