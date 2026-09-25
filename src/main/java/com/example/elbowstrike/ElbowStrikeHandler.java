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

            // ---- 伤害 ----
            target.invulnerableTime = 0;
            float damage = critical
                    ? baseDamage * cfg.critDamageMult.get().floatValue()
                    : baseDamage;
            target.hurt(source, damage);

            // ---- 击退方向 ----
            Vec3 dir = new Vec3(target.getX() - player.getX(), 0.0D, target.getZ() - player.getZ());
            if (dir.lengthSqr() < 1.0E-4D) {
                Vec3 look = player.getLookAngle();
                dir = new Vec3(look.x, 0.0D, look.z);
            }
            if (dir.lengthSqr() < 1.0E-4D) {
                dir = new Vec3(0.0D, 0.0D, 1.0D);
            }
            dir = dir.normalize();

            double hPower = critical ? hKnock * cfg.critKnockbackHorizontalMult.get() : hKnock;
            double vPower = critical ? vKnock * cfg.critKnockbackVerticalMult.get() : vKnock;

            target.setDeltaMovement(dir.x * hPower, vPower, dir.z * hPower);
            target.hasImpulse = true;
            target.hurtMarked = true;

            if (target instanceof ServerPlayer serverPlayer) {
                serverPlayer.connection.send(new ClientboundSetEntityMotionPacket(serverPlayer));
                NetworkHandler.CHANNEL.sendTo(
                        new SpinStartPacket(spinDuration),
                        serverPlayer.connection.connection,
                        NetworkDirection.PLAY_TO_CLIENT
                );
            } else {
                SpinManager.startSpin(target, spinDuration);
            }

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

        // ---- 伤害 ----
        DamageSource source = level.damageSources().playerAttack(player);
        attacker.invulnerableTime = 0;
        attacker.hurt(source, baseDamage);

        // ---- 击退方向：从玩家指向攻击者 ----
        Vec3 dir = new Vec3(
                attacker.getX() - player.getX(),
                0.0D,
                attacker.getZ() - player.getZ()
        );
        if (dir.lengthSqr() < 1.0E-4D) {
            Vec3 look = player.getLookAngle();
            dir = new Vec3(look.x, 0.0D, look.z);
        }
        if (dir.lengthSqr() < 1.0E-4D) {
            dir = new Vec3(0.0D, 0.0D, 1.0D);
        }
        dir = dir.normalize();

        attacker.setDeltaMovement(dir.x * hKnock, vKnock, dir.z * hKnock);
        attacker.hasImpulse = true;
        attacker.hurtMarked = true;

        // ---- 旋转 ----
        if (cfg.parryCounterSpin.get()) {
            if (attacker instanceof ServerPlayer sp) {
                sp.connection.send(new ClientboundSetEntityMotionPacket(sp));
                NetworkHandler.CHANNEL.sendTo(
                        new SpinStartPacket(spinDuration),
                        sp.connection.connection,
                        NetworkDirection.PLAY_TO_CLIENT
                );
            } else {
                SpinManager.startSpin(attacker, spinDuration);
            }
        }

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
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();

        double range = ElbowStrikeConfig.COMMON.range.get();
        double cone = ElbowStrikeConfig.COMMON.cone.get();

        AABB box = player.getBoundingBox()
                .inflate(range + 1.0D)
                .expandTowards(look.scale(range));

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
}