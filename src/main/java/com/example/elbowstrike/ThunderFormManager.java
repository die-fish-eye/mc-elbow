package com.example.elbowstrike;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
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

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 雷霆形态（司空震大招）状态机。
 *
 * 流程：
 *   1. 按 K 激活：玩家进入"漂浮"阶段，缓慢匀速上升（水平方向可自由移动）。
 *   2. 漂浮持续 thunderFormFloatDuration tick，期间每 X tick 在 20 格内召唤一道雷电。
 *      优先落在生物身上，否则随机位置。
 *   3. 漂浮结束后进入"坠落"阶段：锁定向下的速度，快速下坠，继续劈雷。
 *   4. 玩家落地 → 触发雷暴：对 10 格内所有生物造成伤害 + 击退 + 粒子。
 *
 * 说明：
 *   - 漂浮阶段每 tick 把垂直速度锁定为 +floatSpeed，玩家不会因重力下落。
 *   - 坠落阶段每 tick 把垂直速度锁定为 -fallSpeed，玩家快速砸向地面。
 *   - 默认全程清零 fallDistance，避免摔伤；彩蛋配置可放开。
 *   - 超时保护，防止玩家卡在空中（比如鞘翅、创造飞行）。
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
        boolean hasLeftGround;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();

    private ThunderFormManager() {}

    /** 客户端请求激活 */
    public static void tryActivate(ServerPlayer player) {
        var cfg = ElbowStrikeConfig.COMMON;
        if (!cfg.enableThunderForm.get()) return;

        UUID uuid = player.getUUID();
        long now = player.level().getGameTime();

        // 冷却中
        Long cd = COOLDOWNS.get(uuid);
        if (cd != null && now < cd) return;
        // 已经在形态中
        if (STATES.containsKey(uuid)) return;

        int duration = cfg.thunderFormFloatDuration.get();
        if (duration <= 0) return;

        State s = new State();
        s.phase = Phase.FLOATING;
        s.floatTicksLeft = duration;
        s.lightningTimer = 0;      // 第一 tick 立刻劈雷
        s.timeout = duration + 400; // 漂浮时长 + 20 秒兜底
        s.hasLeftGround = false;
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
            detonateThunderstorm(p);
            it.remove();
            return;
        }

        // 是否允许摔落伤害（彩蛋）
        boolean allowFallDamage = cfg.thunderFormAllowFallDamage.get();
        if (!allowFallDamage) {
            p.fallDistance = 0.0F;
        }

        // 记录是否离过地
        boolean onGround = p.onGround() || p.isInWater() || p.isInLava();
        if (!onGround) {
            s.hasLeftGround = true;
        } else if (s.hasLeftGround && s.phase == Phase.FALLING) {
            // 只有在坠落阶段才允许落地触发雷暴
            detonateThunderstorm(p);
            it.remove();
            return;
        }

        switch (s.phase) {
            case FLOATING -> tickFloating(p, s);
            case FALLING  -> tickFalling(p, s);
        }

        // ---- 从按 K 那一刻起就劈雷，不管哪个阶段 ----
        if (s.lightningTimer <= 0) {
            spawnLightning(p);
            s.lightningTimer = cfg.thunderFormLightningInterval.get();
        } else {
            s.lightningTimer--;
        }

        // 空中拖尾粒子
        if (p.level() instanceof ServerLevel sl) {
            sl.sendParticles(
                    ParticleTypes.ELECTRIC_SPARK,
                    p.getX(), p.getY() + 1.0D, p.getZ(),
                    3, 0.3D, 0.5D, 0.3D, 0.05D
            );
        }
    }

    // ================================================================
    // 漂浮阶段：缓慢匀速上升，水平移动保留
    // ================================================================
    private static void tickFloating(ServerPlayer p, State s) {
        var cfg = ElbowStrikeConfig.COMMON;
        double speed = cfg.thunderFormFloatSpeed.get();

        Vec3 v = p.getDeltaMovement();
        p.setDeltaMovement(v.x, speed, v.z);
        p.hasImpulse = true;
        p.hurtMarked = true;

        s.floatTicksLeft--;
        if (s.floatTicksLeft <= 0) {
            // 进入坠落阶段
            s.phase = Phase.FALLING;
            s.timeout = 400;   // 20 秒坠落兜底

            double fall = cfg.thunderFormFallSpeed.get();
            Vec3 v2 = p.getDeltaMovement();
            p.setDeltaMovement(v2.x, -fall, v2.z);
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
    // 坠落阶段：锁定向下速度，快速砸向地面
    // ================================================================
    private static void tickFalling(ServerPlayer p, State s) {
        var cfg = ElbowStrikeConfig.COMMON;
        double fall = cfg.thunderFormFallSpeed.get();

        Vec3 v = p.getDeltaMovement();
        p.setDeltaMovement(v.x, -fall, v.z);
        p.hasImpulse = true;
        p.hurtMarked = true;
    }

    // ================================================================
    // 召唤单道雷电
    // ================================================================
    private static void spawnLightning(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        var cfg = ElbowStrikeConfig.COMMON;

        double radius = cfg.thunderFormLightningRadius.get();
        float dmg = cfg.thunderFormLightningDamage.get().floatValue();

        // 优先找范围内的生物
        List<LivingEntity> candidates = level.getEntitiesOfClass(
                LivingEntity.class,
                player.getBoundingBox().inflate(radius),
                e -> e != player && e.isAlive() && e.isPickable()
        );

        double tx, ty, tz;
        if (!candidates.isEmpty()) {
            LivingEntity target = candidates.get(
                    player.getRandom().nextInt(candidates.size()));
            tx = target.getX();
            ty = target.getY();
            tz = target.getZ();
        } else {
            // 无生物 → 玩家周围随机位置（略微偏下，让闪电从上方劈下来）
            double a = player.getRandom().nextDouble() * Math.PI * 2.0D;
            double r = radius * Math.sqrt(player.getRandom().nextDouble());
            tx = player.getX() + Math.cos(a) * r;
            ty = player.getY() - 3.0D;
            tz = player.getZ() + Math.sin(a) * r;
        }

        // 视觉闪电
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt != null) {
            bolt.moveTo(tx, ty, tz);
            bolt.setVisualOnly(true);
            level.addFreshEntity(bolt);
        }

        // 伤害
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

        // 音效
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

        // 视觉粒子
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

        // 音效
        level.playSound(null, px, py, pz,
                SoundEvents.LIGHTNING_BOLT_THUNDER,
                SoundSource.PLAYERS, 2.0F, 0.7F);
        level.playSound(null, px, py, pz,
                SoundEvents.GENERIC_EXPLODE,
                SoundSource.PLAYERS, 1.5F, 1.2F);

        // 伤害 + 击退
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
    // 取消坠落伤害（若配置允许则不取消）
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
            UUID uuid = sp.getUUID();
            STATES.remove(uuid);
            COOLDOWNS.remove(uuid);
        }
    }
}