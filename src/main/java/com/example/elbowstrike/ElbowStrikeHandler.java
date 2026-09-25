package com.example.elbowstrike;

import com.example.elbowstrike.network.NetworkHandler;
import com.example.elbowstrike.network.SpinStartPacket;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkDirection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ElbowStrikeHandler {

    public static final double RANGE = 3.0D;
    public static final double CONE = 0.5D;
    public static final double KNOCKBACK_HORIZONTAL = 1.8D;
    public static final double KNOCKBACK_VERTICAL = 0.45D;

    public static final float DAMAGE = 3.0F;
    public static final int COOLDOWN_TICKS = 12;
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

        LivingEntity target = findTarget(player);
        if (target == null) {
            broadcastSound(level, player.getX(), player.getY(), player.getZ(), 0.6F, 1.2F);
            return;
        }

        // ---- 伤害 ----
        target.invulnerableTime = 0;
        DamageSource source = level.damageSources().playerAttack(player);
        target.hurt(source, DAMAGE);

        // ---- 计算击退方向（仅水平）----
        Vec3 dir = new Vec3(target.getX() - player.getX(), 0.0D, target.getZ() - player.getZ());
        if (dir.lengthSqr() < 1.0E-4D) {
            Vec3 look = player.getLookAngle();
            dir = new Vec3(look.x, 0.0D, look.z);
        }
        if (dir.lengthSqr() < 1.0E-4D) {
            dir = new Vec3(0.0D, 0.0D, 1.0D);
        }
        dir = dir.normalize();

        // ---- 强制击退：直接覆写速度 ----
        target.setDeltaMovement(
                dir.x * KNOCKBACK_HORIZONTAL,
                KNOCKBACK_VERTICAL,
                dir.z * KNOCKBACK_HORIZONTAL
        );
        target.hasImpulse = true;
        target.hurtMarked = true;

        if (target instanceof ServerPlayer serverPlayer) {
            serverPlayer.connection.send(new ClientboundSetEntityMotionPacket(serverPlayer));
        }

        // ---- 旋转 ----
        if (target instanceof ServerPlayer serverPlayer) {
            NetworkHandler.CHANNEL.sendTo(
                    new SpinStartPacket(SPIN_DURATION),
                    serverPlayer.connection.connection,
                    NetworkDirection.PLAY_TO_CLIENT
            );
        } else {
            SpinManager.startSpin(target, SPIN_DURATION);
        }

        // ---- 音效 ----
        broadcastSound(level,
                target.getX(), target.getY(), target.getZ(),
                1.0F, 1.0F);
    }

    /**
     * 用原版音效包广播给附近玩家。
     * 关键：用 BuiltInRegistries.SOUND_EVENT.wrapAsHolder 生成带 registry key 的 Holder，
     * 否则 Holder.direct 在网络上无法解码。
     */
    private static void broadcastSound(Level level, double x, double y, double z,
                                       float volume, float pitch) {
        if (level.getServer() == null) return;

        Holder<SoundEvent> holder = BuiltInRegistries.SOUND_EVENT
                .wrapAsHolder(ModSounds.ELBOW_STRIKE.get());

        ClientboundSoundPacket packet = new ClientboundSoundPacket(
                holder,
                SoundSource.PLAYERS,
                x, y, z,
                volume, pitch,
                level.random.nextLong()
        );

        level.getServer().getPlayerList().getPlayers().forEach(p -> {
            if (p.distanceToSqr(x, y, z) < 64.0D * 64.0D) {
                p.connection.send(packet);
            }
        });
    }

    private static LivingEntity findTarget(ServerPlayer player) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();

        AABB box = player.getBoundingBox()
                .inflate(RANGE + 1.0D)
                .expandTowards(look.scale(RANGE));

        List<Entity> candidates = level.getEntities(player, box,
                e -> e instanceof LivingEntity living && living.isAlive() && living.isPickable());

        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity e : candidates) {
            LivingEntity living = (LivingEntity) e;
            Vec3 center = living.position().add(0.0D, living.getBbHeight() * 0.5D, 0.0D);
            Vec3 to = center.subtract(eye);
            double dist = to.length();

            if (dist > RANGE + living.getBbWidth() * 0.5D) continue;
            if (dist < 1.0E-4D) continue;
            if (to.normalize().dot(look) < CONE) continue;

            if (dist < bestDist) {
                bestDist = dist;
                best = living;
            }
        }
        return best;
    }
}