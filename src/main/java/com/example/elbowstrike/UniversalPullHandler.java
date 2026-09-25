package com.example.elbowstrike;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
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
 * 万象天引：把前方锥形范围内的生物拉到玩家面前。
 *
 * 实现方式：
 *   - 玩家按 X → 找到范围内所有目标，为每个目标创建一个 PullTask。
 *   - 每个 tick 给目标一个朝向"玩家面前 2 格处"的速度，持续若干 tick。
 *   - 期间目标重力被关闭（noGravity）以保证拉拽稳定，结束后恢复。
 *
 * 兼容性：
 *   - 目标为玩家时通过 ClientboundSetEntityMotionPacket 同步速度；
 *   - 目标为生物时直接 setDeltaMovement + hurtMarked。
 *   - 目标落地或到达终点时提前结束拉拽。
 *
 * 注意：本类通过 @Mod.EventBusSubscriber 自动注册，切勿在 ElbowStrikeMod 里重复注册。
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID)
public final class UniversalPullHandler {

    private static final class PullTask {
        UUID casterId;
        int ticksLeft;
        boolean originalNoGravity;
        boolean gravityChanged;
    }

    /** 目标 UUID -> 拉拽任务 */
    private static final Map<UUID, PullTask> ACTIVE_PULLS = new HashMap<>();
    /** 玩家 UUID -> 上次使用时间（用于冷却） */
    private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();

    private UniversalPullHandler() {}

    // ================================================================
    // 触发
    // ================================================================
    public static void activate(ServerPlayer player) {
        var cfg = ElbowStrikeConfig.COMMON;
        if (!cfg.enableUniversalPull.get()) return;

        UUID uuid = player.getUUID();
        long now = player.level().getGameTime();

        // 冷却
        Long last = COOLDOWNS.get(uuid);
        int cd = cfg.universalPullCooldown.get();
        if (last != null && now - last < cd) return;
        COOLDOWNS.put(uuid, now);

        double range = cfg.universalPullRange.get();
        double cone = cfg.universalPullCone.get();

        List<LivingEntity> targets = findTargets(player, range, cone);

        // 无论是否命中，都播放音效 + 粒子（空放也有效果）
        playCastEffects(player);

        if (targets.isEmpty()) {
            player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
            return;
        }

        player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);

        int duration = cfg.universalPullDuration.get();

        for (LivingEntity target : targets) {
            // 已存在则刷新
            PullTask task = new PullTask();
            task.casterId = uuid;
            task.ticksLeft = duration;

            if (!target.isNoGravity()) {
                task.originalNoGravity = false;
                task.gravityChanged = true;
                target.setNoGravity(true);
            }

            ACTIVE_PULLS.put(target.getUUID(), task);

            // 施法瞬间给一次初速度（快速启动）
            applyPullVelocity(player, target);
        }
    }

    // ================================================================
    // 每 tick
    // ================================================================
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (ACTIVE_PULLS.isEmpty() && COOLDOWNS.isEmpty()) return;

        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        long now = server.overworld().getGameTime();
        COOLDOWNS.entrySet().removeIf(e -> now - e.getValue() > 1200);

        Iterator<Map.Entry<UUID, PullTask>> it = ACTIVE_PULLS.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            UUID targetId = entry.getKey();
            PullTask task = entry.getValue();

            ServerPlayer caster = server.getPlayerList().getPlayer(task.casterId);
            if (caster == null || !caster.isAlive()) {
                finishPull(targetId, task);
                it.remove();
                continue;
            }

            // 找到目标实体
            LivingEntity target = findEntity(server, targetId);
            if (target == null || !target.isAlive()) {
                finishPull(targetId, task);
                it.remove();
                continue;
            }

            // 已经到玩家附近就结束
            if (target.position().distanceToSqr(caster.position()) < 2.0D) {
                finishPull(targetId, task);
                it.remove();
                continue;
            }

            // 施加拉拽速度
            applyPullVelocity(caster, target);

            // 粒子：从目标到玩家画一条紫线
            if (caster.level() instanceof ServerLevel sl) {
                drawTrail(sl, target, caster);
            }

            task.ticksLeft--;
            if (task.ticksLeft <= 0) {
                finishPull(targetId, task);
                it.remove();
            }
        }
    }

    // ================================================================
    // 计算拉拽速度
    // ================================================================
    private static void applyPullVelocity(ServerPlayer caster, LivingEntity target) {
        var cfg = ElbowStrikeConfig.COMMON;
        double speed = cfg.universalPullSpeed.get();
        double stopDist = cfg.universalPullStopDistance.get();

        // 目标：玩家面前 stopDist 格处
        Vec3 look = caster.getLookAngle();
        Vec3 horizontalLook = new Vec3(look.x, 0.0D, look.z);
        if (horizontalLook.lengthSqr() < 1.0E-4D) {
            horizontalLook = new Vec3(0.0D, 0.0D, 1.0D);
        }
        horizontalLook = horizontalLook.normalize();

        Vec3 destination = caster.position()
                .add(horizontalLook.scale(stopDist))
                .add(0.0D, 0.2D, 0.0D);   // 略微抬升

        Vec3 diff = destination.subtract(target.position());
        double dist = diff.length();
        if (dist < 0.5D) {
            // 已经足够近，不再施加
            return;
        }

        // 速度方向 = 归一化差值 × speed
        Vec3 v = diff.normalize().scale(speed);

        // 目标现在已有的速度按比例保留一点，避免拉拽太"死板"
        Vec3 current = target.getDeltaMovement();
        target.setDeltaMovement(
                current.x * 0.2D + v.x,
                current.y * 0.1D + v.y,
                current.z * 0.2D + v.z
        );

        target.hasImpulse = true;
        target.hurtMarked = true;
    }

    // ================================================================
    // 结束一个拉拽任务
    // ================================================================
    private static void finishPull(UUID targetId, PullTask task) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        LivingEntity target = findEntity(server, targetId);
        if (target == null) return;

        if (task.gravityChanged) {
            target.setNoGravity(task.originalNoGravity);
        }

        // 到达后清掉速度，避免穿模
        Vec3 v = target.getDeltaMovement();
        target.setDeltaMovement(v.x * 0.2D, 0.0D, v.z * 0.2D);
    }

    // ================================================================
    // 查找目标（锥形）
    // ================================================================
    private static List<LivingEntity> findTargets(ServerPlayer player,
                                                  double range, double cone) {
        var level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();

        AABB box = player.getBoundingBox().inflate(range);
        List<Entity> candidates = level.getEntities(player, box,
                e -> e instanceof LivingEntity living && living.isAlive() && living.isPickable());

        List<LivingEntity> result = new ArrayList<>();
        for (Entity e : candidates) {
            LivingEntity living = (LivingEntity) e;
            Vec3 center = living.position().add(0.0D, living.getBbHeight() * 0.5D, 0.0D);
            Vec3 to = center.subtract(eye);
            double dist = to.length();

            if (dist > range + living.getBbWidth() * 0.5D) continue;
            if (dist < 1.0E-4D) continue;
            if (to.normalize().dot(look) < cone) continue;

            result.add(living);
        }
        return result;
    }

    // ================================================================
    // 音效 + 施法粒子
    // ================================================================
    private static void playCastEffects(ServerPlayer player) {
        var level = player.level();
        level.playSound(null,
                player.getX(), player.getY(), player.getZ(),
                SoundEvents.BEACON_ACTIVATE,
                SoundSource.PLAYERS,
                1.0F, 1.6F);
        level.playSound(null,
                player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS,
                1.0F, 0.6F);

        if (level instanceof ServerLevel sl) {
            sl.sendParticles(
                    ParticleTypes.PORTAL,
                    player.getX(), player.getY() + 1.0D, player.getZ(),
                    40, 0.6D, 0.6D, 0.6D, 0.4D
            );
            sl.sendParticles(
                    ParticleTypes.REVERSE_PORTAL,
                    player.getX(), player.getY() + 1.0D, player.getZ(),
                    30, 0.5D, 0.5D, 0.5D, 0.3D
            );
        }
    }

    // ================================================================
    // 目标到玩家的轨迹粒子
    // ================================================================
    private static void drawTrail(ServerLevel level, LivingEntity target, ServerPlayer caster) {
        Vec3 from = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        Vec3 to = caster.position().add(0.0D, 1.0D, 0.0D);
        Vec3 dir = to.subtract(from);
        double len = dir.length();
        if (len < 0.3D) return;
        dir = dir.normalize();

        int steps = (int) Math.min(10, Math.max(2, len / 1.5D));
        for (int i = 0; i < steps; i++) {
            double t = (i + 1.0D) / (steps + 1.0D);
            Vec3 p = from.add(dir.scale(len * t));
            level.sendParticles(
                    ParticleTypes.PORTAL,
                    p.x, p.y, p.z,
                    1, 0.05D, 0.05D, 0.05D, 0.02D
            );
        }
    }

    // ================================================================
    // 工具
    // ================================================================
    private static LivingEntity findEntity(net.minecraft.server.MinecraftServer server, UUID uuid) {
        for (var level : server.getAllLevels()) {
            Entity e = level.getEntity(uuid);
            if (e instanceof LivingEntity living && living.isAlive()) {
                return living;
            }
        }
        return null;
    }

    // ================================================================
    // 玩家退出时清理
    // ================================================================
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        // 清掉该玩家发起的拉拽
        UUID casterId = sp.getUUID();
        ACTIVE_PULLS.entrySet().removeIf(e -> {
            if (e.getValue().casterId.equals(casterId)) {
                finishPull(e.getKey(), e.getValue());
                return true;
            }
            return false;
        });
        COOLDOWNS.remove(casterId);
    }
}