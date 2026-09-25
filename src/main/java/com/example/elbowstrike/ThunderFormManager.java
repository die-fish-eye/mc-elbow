package com.example.elbowstrike;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 雷霆形态（司空震大招）状态机。
 *
 * 流程：
 *   1. 按 K 激活：玩家获得 levitation（漂浮）效果，缓慢上升。
 *      期间每 X tick 在 20 格内召唤一道雷电。
 *   2. 漂浮持续 thunderFormFloatDuration tick 后：
 *      移除 levitation，给一次向下的初速度，让重力自然加速下坠。
 *   3. 玩家落地 → 触发雷暴：对 10 格内所有生物造成伤害 + 击退 + 粒子。
 *
 * 为什么用 levitation？
 *   - 原版效果自动处理垂直速度，不会像手动 setDeltaMovement + hurtMarked
 *     那样每 tick 覆盖客户端的水平速度，所以 WASD 可以正常使用。
 *   - 与其他模组、爆炸、烟花、鞘翅的兼容性最好。
 *   - 显示效果由玩家状态（粒子、抖动）体现，不依赖额外逻辑。
 *
 * 雷电检测：
 *   - 以玩家为中心的柱形（水平 radius，垂直 ±300），升到高空也能劈到地面生物。
 *   - 无生物时随机劈点，会从玩家 Y 往下找地面，让闪电劈在地表。
 *
 * 注意：本类通过 @Mod.EventBusSubscriber 自动注册，切勿在 ElbowStrikeMod 里重复注册。
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID)
public final class ThunderFormManager {

    private enum Phase { FLOATING, FALLING }

    private static final class State {
        Phase phase;
        int floatTicksLeft;
        int lightningTimer;
        int timeout;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();

    private ThunderFormManager() {}

    // ================================================================
    // 激活
    // ================================================================
    public static void tryActivate(ServerPlayer player) {
        var cfg = ElbowStrikeConfig.COMMON;
        if (!cfg.enableThunderForm.get()) return;

        UUID uuid = player.getUUID();
        long now = player.level().getGameTime();

        Long cd = COOLDOWNS.get(uuid);
        if (cd != null && now < cd) return;
        if (STATES.containsKey(uuid)) return;

        int duration = cfg.thunderFormFloatDuration.get();
        if (duration <= 0) return;

        State s = new State();
        s.phase = Phase.FLOATING;
        s.floatTicksLeft = duration;
        s.lightningTimer = 0;         // 第一 tick 立刻劈雷
        s.timeout = duration + 400;   // 漂浮 + 20 秒兜底

        // ---- 施加 levitation 效果实现漂浮 ----
        // 原版 levitation 的每 tick 目标速度 ≈ 0.05 * (amplifier + 1)
        // 把 floatSpeed 换算成 amplifier：
        //   0.05 → amp 0
        //   0.10 → amp 1
        //   0.15 → amp 2（默认）
        //   0.20 → amp 3
        //   0.25 → amp 4
        double speed = cfg.thunderFormFloatSpeed.get();
        int amp = (int) Math.max(0, Math.min(4, Math.round(speed / 0.05D) - 1));

        // 时长多给 5 tick，避免刚好在效果结束时还没切换阶段
        player.addEffect(new MobEffectInstance(
                MobEffects.LEVITATION,
                duration + 5,
                amp,
                false,   // ambient
                false,   // visible
                false    // showIcon
        ));

        STATES.put(uuid, s);
        COOLDOWNS.put(uuid, now + cfg.thunderFormCooldown.get());

        // 激活音效
        player.level().playSound(
                null,
                player.getX(), player.getY(), player.getZ(),
                SoundEvents.TRIDENT_THUNDER,
                SoundSource.PLAYERS,
                1.5F, 0.8F
        );

        // 激活粒子爆发
        if (player.level() instanceof ServerLevel sl) {
            sl.sendParticles(
                    ParticleTypes.ELECTRIC_SPARK,
                    player.getX(), player.getY() + 1.0D, player.getZ(),
                    60, 0.8D, 1.2D, 0.8D, 0.25D
            );
        }
    }

    // ================================================================
    // Server tick
    // ================================================================
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (STATES.isEmpty() && COOLDOWNS.isEmpty()) return;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        long now = server.overworld().getGameTime();

        Iterator<Map.Entry<UUID, State>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
            State s = entry.getValue();

            if (p == null || !p.isAlive()) {
                cleanup(p);
                it.remove();
                continue;
            }

            tick(p, s, it);
        }

        COOLDOWNS.entrySet().removeIf(e -> now >= e.getValue());
    }

    // ================================================================
    // 每 tick 逻辑
    // ================================================================
    private static void tick(ServerPlayer p, State s,
                             Iterator<Map.Entry<UUID, State>> it) {
        var cfg = ElbowStrikeConfig.COMMON;

        // 超时保护
        if (--s.timeout <= 0) {
            finish(p, s);
            it.remove();
            return;
        }

        // 摔落伤害（彩蛋）
        if (!cfg.thunderFormAllowFallDamage.get()) {
            p.fallDistance = 0.0F;
        }

        // 只有坠落阶段才判定落地
        if (s.phase == Phase.FALLING) {
            boolean onGround = p.onGround() || p.isInWater() || p.isInLava();
            if (onGround) {
                finish(p, s);
                it.remove();
                return;
            }
        }

        switch (s.phase) {
            case FLOATING -> tickFloating(p, s);
            case FALLING  -> tickFalling(p, s);
        }

        // ---- 劈雷 ----
        if (s.lightningTimer <= 0) {
            spawnLightning(p);
            s.lightningTimer = cfg.thunderFormLightningInterval.get();
        } else {
            s.lightningTimer--;
        }

        // 拖尾粒子
        if (p.level() instanceof ServerLevel sl) {
            sl.sendParticles(
                    ParticleTypes.ELECTRIC_SPARK,
                    p.getX(), p.getY() + 1.0D, p.getZ(),
                    3, 0.3D, 0.5D, 0.3D, 0.05D
            );
        }
    }

    // ================================================================
    // 漂浮阶段：什么都不做，levitation 会处理上升
    // ================================================================
    private static void tickFloating(ServerPlayer p, State s) {
        var cfg = ElbowStrikeConfig.COMMON;

        s.floatTicksLeft--;
        if (s.floatTicksLeft <= 0) {
            // 进入坠落阶段
            s.phase = Phase.FALLING;
            s.timeout = 400;

            // 移除 levitation
            p.removeEffect(MobEffects.LEVITATION);

            // 给一次向下的初速度，让重力接管
            double fall = cfg.thunderFormFallSpeed.get();
            Vec3 v = p.getDeltaMovement();
            p.setDeltaMovement(v.x, -fall, v.z);
            p.hurtMarked = true;

            // 下坠音效
            p.level().playSound(
                    null,
                    p.getX(), p.getY(), p.getZ(),
                    SoundEvents.TRIDENT_RIPTIDE_3,
                    SoundSource.PLAYERS,
                    1.2F, 0.9F
            );
        }
    }

    // ================================================================
    // 坠落阶段：交给重力，不做任何速度干预
    // ================================================================
    private static void tickFalling(ServerPlayer p, State s) {
        // 重力会自然加速下坠（终速约 -3.92 格/tick）。
        // 不干预速度 → WASD 水平移动完全自由。
    }

    // ================================================================
    // 召唤单道雷电（柱形检测）
    // ================================================================
    private static void spawnLightning(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        var cfg = ElbowStrikeConfig.COMMON;

        double radius = cfg.thunderFormLightningRadius.get();
        float dmg = cfg.thunderFormLightningDamage.get().floatValue();

        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        // ---------- 柱形检测 ----------
        AABB column = new AABB(
                px - radius, py - 300.0D, pz - radius,
                px + radius, py + 300.0D, pz + radius
        );

        List<LivingEntity> raw = level.getEntitiesOfClass(
                LivingEntity.class,
                column,
                e -> e != player && e.isAlive() && e.isPickable()
        );

        // 细筛成圆柱
        double r2 = radius * radius;
        List<LivingEntity> candidates = new ArrayList<>();
        for (LivingEntity e : raw) {
            double dx = e.getX() - px;
            double dz = e.getZ() - pz;
            if (dx * dx + dz * dz <= r2) {
                candidates.add(e);
            }
        }

        double tx, ty, tz;
        if (!candidates.isEmpty()) {
            LivingEntity target = candidates.get(
                    player.getRandom().nextInt(candidates.size()));
            tx = target.getX();
            ty = target.getY();
            tz = target.getZ();
        } else {
            // 无生物 → 随机水平位置，往下找地面
            double a = player.getRandom().nextDouble() * Math.PI * 2.0D;
            double r = radius * Math.sqrt(player.getRandom().nextDouble());
            tx = px + Math.cos(a) * r;
            tz = pz + Math.sin(a) * r;

            int bx = (int) Math.floor(tx);
            int bz = (int) Math.floor(tz);
            int startY = (int) Math.floor(py);
            int groundY = startY - 3;

            int minY = Math.max(level.getMinBuildHeight(), startY - 120);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = startY; y >= minY; y--) {
                pos.set(bx, y, bz);
                if (!level.getBlockState(pos).isAir()) {
                    groundY = y + 1;
                    break;
                }
            }
            ty = groundY;
        }

        // ---------- 视觉闪电 ----------
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt != null) {
            bolt.moveTo(tx, ty, tz);
            bolt.setVisualOnly(true);
            level.addFreshEntity(bolt);
        }

        // ---------- 伤害 ----------
        if (dmg > 0.0F) {
            AABB area = new AABB(tx - 2.0D, ty - 1.0D, tz - 2.0D,
                                 tx + 2.0D, ty + 3.0D, tz + 2.0D);
            DamageSource src = level.damageSources().playerAttack(player);
            for (LivingEntity e : level.getEntitiesOfClass(
                    LivingEntity.class, area,
                    e -> e != player && e.isAlive())) {
                e.invulnerableTime = 0;
                e.hurt(src, dmg);
            }
        }

        // ---------- 音效 ----------
        level.playSound(
                null, tx, ty, tz,
                SoundEvents.LIGHTNING_BOLT_THUNDER,
                SoundSource.WEATHER,
                0.8F, 1.0F + player.getRandom().nextFloat() * 0.3F
        );
    }

    // ================================================================
    // 落地雷暴
    // ================================================================
    private static void detonateThunderstorm(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        var cfg = ElbowStrikeConfig.COMMON;

        double radius = cfg.thunderFormImpactRadius.get();
        float dmg = cfg.thunderFormImpactDamage.get().floatValue();
        double knock = cfg.thunderFormImpactKnockback.get();

        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        level.sendParticles(
                ParticleTypes.EXPLOSION_EMITTER,
                px, py, pz,
                1, 0.0D, 0.0D, 0.0D, 0.0D
        );
        level.sendParticles(
                ParticleTypes.ELECTRIC_SPARK,
                px, py + 0.5D, pz,
                180, radius * 0.6D, 0.8D, radius * 0.6D, 0.4D
        );
        level.sendParticles(
                ParticleTypes.CLOUD,
                px, py + 0.3D, pz,
                60, radius * 0.5D, 0.2D, radius * 0.5D, 0.15D
        );

        level.playSound(null, px, py, pz,
                SoundEvents.LIGHTNING_BOLT_THUNDER,
                SoundSource.PLAYERS, 2.0F, 0.7F);
        level.playSound(null, px, py, pz,
                SoundEvents.GENERIC_EXPLODE,
                SoundSource.PLAYERS, 1.5F, 1.2F);

        if (dmg > 0.0F || knock > 0.0D) {
            DamageSource src = level.damageSources().playerAttack(player);
            AABB area = player.getBoundingBox().inflate(radius);
            for (LivingEntity e : level.getEntitiesOfClass(
                    LivingEntity.class, area,
                    e -> e != player && e.isAlive() && e.isPickable())) {

                e.invulnerableTime = 0;
                if (dmg > 0.0F) e.hurt(src, dmg);

                if (knock > 0.0D) {
                    Vec3 dir = new Vec3(e.getX() - px, 0.0D, e.getZ() - pz);
                    if (dir.lengthSqr() < 1.0E-4D) {
                        dir = new Vec3(0.0D, 0.0D, 1.0D);
                    }
                    dir = dir.normalize();
                    e.setDeltaMovement(
                            dir.x * knock,
                            knock * 0.6D,
                            dir.z * knock
                    );
                    e.hasImpulse = true;
                    e.hurtMarked = true;
                }
            }
        }
    }

    // ================================================================
    // 结束 / 清理
    // ================================================================
    private static void finish(ServerPlayer p, State s) {
        cleanup(p);
        detonateThunderstorm(p);
    }

    private static void cleanup(ServerPlayer p) {
        if (p == null) return;
        // 保险：移除可能残留的 levitation
        p.removeEffect(MobEffects.LEVITATION);
    }

    // ================================================================
    // 取消坠落伤害（彩蛋可放开）
    // ================================================================
    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!STATES.containsKey(sp.getUUID())) return;

        if (!ElbowStrikeConfig.COMMON.thunderFormAllowFallDamage.get()) {
            event.setCanceled(true);
        }
    }

    // ================================================================
    // 玩家登出时清理
    // ================================================================
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            STATES.remove(sp.getUUID());
            cleanup(sp);
            COOLDOWNS.remove(sp.getUUID());
        }
    }
}