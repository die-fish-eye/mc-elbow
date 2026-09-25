package com.example.elbowstrike;

import com.example.elbowstrike.network.NetworkHandler;
import com.example.elbowstrike.network.SpinStartPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkDirection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ElbowStrikeHandler {

    /** 肘击判定距离 */
    public static final double RANGE = 4.0D;
    /** 前方锥形判定，0.5 ≈ 60° */
    public static final double CONE = 0.3D;

    /** 水平击退力度 */
    public static final double KNOCKBACK_HORIZONTAL = 1.8D;
    /** 垂直击退力度（让它离地） */
    public static final double KNOCKBACK_VERTICAL = 1.0D;

    public static final float DAMAGE = 3.0F;
    /** 冷却：5 tick ≈ 0.25 秒 */
    public static final int COOLDOWN_TICKS = 5;
    public static final int SPIN_DURATION = 60;

    private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();

    private ElbowStrikeHandler() {}

    public static void strike(ServerPlayer player) {
        Level level = player.level();
        long now = level.getGameTime();

        // 冷却
        Long last = COOLDOWNS.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_TICKS) return;
        COOLDOWNS.put(player.getUUID(), now);

        player.swing(InteractionHand.MAIN_HAND, true);

        // ---- 找到前方所有目标 ----
        List<LivingEntity> targets = findTargets(player);

        if (targets.isEmpty()) {
            level.playSound(null,
                    player.getX(), player.getY(), player.getZ(),
                    ModSounds.ELBOW_STRIKE.get(),
                    SoundSource.PLAYERS,
                    0.6F, 1.2F);
            return;
        }

        DamageSource source = level.damageSources().playerAttack(player);

        // ---- 对每个目标施加伤害、击退、旋转 ----
        for (LivingEntity target : targets) {
            // 伤害
            target.invulnerableTime = 0;
            target.hurt(source, DAMAGE);

            // 计算击退方向（仅水平）
            Vec3 dir = new Vec3(target.getX() - player.getX(), 0.0D, target.getZ() - player.getZ());
            if (dir.lengthSqr() < 1.0E-4D) {
                Vec3 look = player.getLookAngle();
                dir = new Vec3(look.x, 0.0D, look.z);
            }
            if (dir.lengthSqr() < 1.0E-4D) {
                dir = new Vec3(0.0D, 0.0D, 1.0D);
            }
            dir = dir.normalize();

            // 强制击退
            target.setDeltaMovement(
                    dir.x * KNOCKBACK_HORIZONTAL,
                    KNOCKBACK_VERTICAL,
                    dir.z * KNOCKBACK_HORIZONTAL
            );
            target.hasImpulse = true;
            target.hurtMarked = true;

            if (target instanceof ServerPlayer serverPlayer) {
                serverPlayer.connection.send(new ClientboundSetEntityMotionPacket(serverPlayer));
                // 玩家：客户端权威，发 S2C 包让它自己旋转
                NetworkHandler.CHANNEL.sendTo(
                        new SpinStartPacket(SPIN_DURATION),
                        serverPlayer.connection.connection,
                        NetworkDirection.PLAY_TO_CLIENT
                );
            } else {
                // 生物：服务端强制旋转
                SpinManager.startSpin(target, SPIN_DURATION);
            }
        }

        // ---- 音效（只播一次） ----
        level.playSound(null,
                player.getX(), player.getY() + 1.0D, player.getZ(),
                ModSounds.ELBOW_STRIKE.get(),
                SoundSource.PLAYERS,
                1.0F, 1.0F);
    }

    /** 在玩家前方锥形范围内找出所有活体目标 */
    private static List<LivingEntity> findTargets(ServerPlayer player) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();

        AABB box = player.getBoundingBox()
                .inflate(RANGE + 1.0D)
                .expandTowards(look.scale(RANGE));

        List<Entity> candidates = level.getEntities(player, box,
                e -> e instanceof LivingEntity living && living.isAlive() && living.isPickable());

        List<LivingEntity> result = new ArrayList<>();

        for (Entity e : candidates) {
            LivingEntity living = (LivingEntity) e;

            Vec3 center = living.position().add(0.0D, living.getBbHeight() * 0.5D, 0.0D);
            Vec3 to = center.subtract(eye);
            double dist = to.length();

            if (dist > RANGE + living.getBbWidth() * 0.5D) continue;
            if (dist < 1.0E-4D) continue;
            if (to.normalize().dot(look) < CONE) continue;

            result.add(living);
        }
        return result;
    }
}