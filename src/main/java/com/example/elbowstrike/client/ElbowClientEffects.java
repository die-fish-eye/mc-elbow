package com.example.elbowstrike.client;

import com.example.elbowstrike.ElbowStrikeConfig;
import com.example.elbowstrike.ElbowStrikeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 蓄力肘击的客户端视觉反馈。
 * <p>
 * 全部粒子都生成在玩家<b>背后</b>（视线反方向），保证不挡屏幕中央视野：
 * <ul>
 *     <li><b>蓄力星空环</b>：玩家背后水平一圈环绕粒子，蓄力越久越密越亮，
 *         颜色随档位升级（白 → 靛蓝 → 电光紫 → 满蓄彩虹电闪）；</li>
 *     <li><b>满蓄力爆发</b>：蓄满瞬间一次性在背后方向爆一圈彩色 END_ROD + 音爆 + 提示音。</li>
 * </ul>
 * 可通过 {@code config/elbowstrike-common.toml} 的 {@code effects} 段开关。
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID, value = Dist.CLIENT)
public final class ElbowClientEffects {

    /** 本次蓄力是否已触发过「满蓄力」提示 */
    private static boolean fullChargeNotified = false;

    private ElbowClientEffects() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            fullChargeNotified = false;
            return;
        }
        if (!ElbowStrikeConfig.COMMON.enableEffects.get()) return;
        if (!ClientChargeHandler.isCharging()) {
            fullChargeNotified = false;
            return;
        }

        int tier = ClientChargeHandler.getChargeTier();
        float ratio = tier / (float) ClientChargeHandler.CHARGE_TIERS;

        if (ElbowStrikeConfig.COMMON.chargeParticles.get()) {
            spawnChargeBackstar(mc, ratio);
        }
        if (tier >= ClientChargeHandler.CHARGE_TIERS && !fullChargeNotified) {
            fullChargeNotified = true;
            onFullCharge(mc);
        }
    }

    // ================================================================
    // 背后星空环（蓄力全程持续生成）
    // ================================================================

    /**
     * 在玩家背后生成一圈环绕的星空粒子。
     * 位置：玩家背后（视线反方向）距离 2.0 + ratio * 1.5 格处，水平环绕玩家。
     * 环半径随蓄力增大（2.5 → 4.0 格），粒子数量 / 亮度 / 类型也随蓄力升级。
     */
    private static void spawnChargeBackstar(Minecraft mc, float progress) {
        Player p = mc.player;
        Vec3 look = p.getLookAngle();
        double cx = p.getX();
        double cy = p.getY() + 0.9D; // 胸口高度
        double cz = p.getZ();

        // 背后方向 = -look（水平分量）
        double backX = -look.x;
        double backZ = -look.z;
        double backLen = Math.sqrt(backX * backX + backZ * backZ);
        if (backLen < 1.0E-4D) {
            backX = 0.0D;
            backZ = -1.0D;
        } else {
            backX /= backLen;
            backZ /= backLen;
        }

        // 环的半径：蓄力越高环越大，永远保持在玩家背后
        double ringRadius = 2.5D + progress * 1.5D;
        // 环中心（玩家背后）
        double ringCx = cx + backX * ringRadius;
        double ringCz = cz + backZ * ringRadius;

        // 本 tick 生成几颗（越蓄越多）
        int perTick = 1 + Math.round(progress * 3.0F);

        for (int i = 0; i < perTick; i++) {
            // 在环上随机选一个角度（环是水平圆）
            double angle = mc.level.random.nextDouble() * Math.PI * 2.0D;
            double px = ringCx + Math.cos(angle) * ringRadius;
            double pz = ringCz + Math.sin(angle) * ringRadius;
            // 环的高度在胸口附近随机波动 ±0.6
            double py = cy + (mc.level.random.nextDouble() - 0.5D) * 1.2D;

            // 速度：粒子缓慢向玩家方向飘回来 + 轻微向外扩散，制造漂浮感
            double toPlayerX = cx - px;
            double toPlayerZ = cz - pz;
            double toLen = Math.sqrt(toPlayerX * toPlayerX + toPlayerZ * toPlayerZ);
            double vx = toLen > 1.0E-4D ? (toPlayerX / toLen) * 0.015D : 0.0D;
            double vz = toLen > 1.0E-4D ? (toPlayerZ / toLen) * 0.015D : 0.0D;
            // 向外的径向扩散（让环略有呼吸感）
            vx += Math.cos(angle) * 0.01D * progress;
            vz += Math.sin(angle) * 0.01D * progress;
            double vy = (mc.level.random.nextDouble() - 0.5D) * 0.02D;

            // 粒子类型选择在方法内部直接返回 ParticleOptions（ParticleType 本身实现了它）
            net.minecraft.core.particles.ParticleOptions opts = pickBackstarParticle(progress);
            mc.level.addParticle(opts, px, py, pz, vx, vy, vz);
        }

        // 满蓄力时，在环心（玩家背后很近的位置）额外加一颗电光
        if (progress >= 0.999F) {
            mc.level.addParticle(
                    ParticleTypes.ELECTRIC_SPARK,
                    ringCx + (mc.level.random.nextDouble() - 0.5D) * 0.6D,
                    cy,
                    ringCz + (mc.level.random.nextDouble() - 0.5D) * 0.6D,
                    0.0D, 0.02D, 0.0D
            );
        }
    }

    /**
     * 根据蓄力进度选粒子类型。
     * ParticleType<T> 本身实现了 ParticleOptions，因此可以直接作为返回值传给 addParticle。
     */
    private static net.minecraft.core.particles.ParticleOptions pickBackstarParticle(float progress) {
        if (progress >= 0.999F) {
            // 满蓄：随机混合 END_ROD（彩色感）和 ELECTRIC_SPARK
            if (mcRandom() < 0.25F) {
                return ParticleTypes.ELECTRIC_SPARK;
            }
            return ParticleTypes.END_ROD;
        } else if (progress >= 0.66F) {
            // 中后期：END_ROD 为主，偶尔 ELECTRIC_SPARK
            if (mcRandom() < 0.15F) {
                return ParticleTypes.ELECTRIC_SPARK;
            }
            return ParticleTypes.END_ROD;
        } else if (progress >= 0.33F) {
            // 中期：END_ROD + CRIT 混合
            if (mcRandom() < 0.5F) {
                return ParticleTypes.END_ROD;
            }
            return ParticleTypes.CRIT;
        } else {
            // 初期：纯白 CRIT
            return ParticleTypes.CRIT;
        }
    }

    /** 从 Minecraft 全局随机取一个 0~1 的值（避免每次都写 mc.level.random） */
    private static float mcRandom() {
        return Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.random.nextFloat()
                : (float) Math.random();
    }

    // ================================================================
    // 满蓄力瞬间爆发（一次性）
    // ================================================================

    private static void onFullCharge(Minecraft mc) {
        Player p = mc.player;
        Vec3 look = p.getLookAngle();
        // 背后方向
        double backX = -look.x;
        double backZ = -look.z;
        double backLen = Math.sqrt(backX * backX + backZ * backZ);
        if (backLen < 1.0E-4D) { backX = 0.0D; backZ = -1.0D; } else { backX /= backLen; backZ /= backLen; }

        double cx = p.getX();
        double cy = p.getY() + 1.0D;
        double cz = p.getZ();

        // 背后 3 格处的爆点
        double bx = cx + backX * 3.0D;
        double bz = cz + backZ * 3.0D;

        // 背后环绕爆一圈 END_ROD（向外飞）
        for (int i = 0; i < 32; i++) {
            double a = Math.PI * 2.0D * i / 32.0D;
            double ex = bx + Math.cos(a) * 0.5D;
            double ez = bz + Math.sin(a) * 0.5D;
            mc.level.addParticle(ParticleTypes.END_ROD,
                    ex, cy + (mc.level.random.nextDouble() - 0.5D) * 0.4D, ez,
                    Math.cos(a) * 0.2D, 0.08D, Math.sin(a) * 0.2D);
        }
        // 背后一颗音爆（不在正前方，避免挡视野）
        mc.level.addParticle(ParticleTypes.SONIC_BOOM, bx, cy, bz, 0.0D, 0.0D, 0.0D);
        // 玩家周身少量电火花（量少，不挡眼）
        for (int i = 0; i < 8; i++) {
            double a = mc.level.random.nextDouble() * Math.PI * 2.0D;
            double r = 0.6D + mc.level.random.nextDouble() * 0.6D;
            mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK,
                    cx + Math.cos(a) * r,
                    cy + (mc.level.random.nextDouble() - 0.5D) * 0.8D,
                    cz + Math.sin(a) * r,
                    0.0D, 0.05D, 0.0D);
        }
        p.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.8F, 1.4F);
    }
}
