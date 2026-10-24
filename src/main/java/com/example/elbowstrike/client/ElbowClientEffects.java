package com.example.elbowstrike.client;

import com.example.elbowstrike.ElbowStrikeConfig;
import com.example.elbowstrike.ElbowStrikeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
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
 * 全部粒子都围绕玩家身体（紧贴身后 + 脚下），保证不挡屏幕中央视野：
 * <ul>
 *     <li><b>背后光环（多轨道）</b>：玩家身后贴身处的多层水平轨道环，
 *         每层粒子沿着轨道流动，蓄力越久层数越多、速度越快、颜色越亮；</li>
 *     <li><b>脚下魂环（斗罗风格）</b>：玩家脚下旋转的多层水平圆环，
 *         每档蓄力解锁一层，5 档时共 5 环同时旋转；</li>
 *     <li><b>满蓄力爆发</b>：蓄满瞬间背后爆一圈彩色 END_ROD + 脚下魂环炸出一道电光 + 铃音。</li>
 * </ul>
 * 可通过 {@code config/elbowstrike-common.toml} 的 {@code effects} 段开关。
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID, value = Dist.CLIENT)
public final class ElbowClientEffects {

    /** 本次蓄力是否已触发过「满蓄力」提示 */
    private static boolean fullChargeNotified = false;

    /** 每个轨道的"当前相位"——在 static 字段里存，避免粒子一帧一帧乱跳 */
    private static final double[] ORBIT_PHASE = new double[5];

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
            updateOrbits(ratio);
            spawnBackOrbits(mc, ratio);
            spawnSoulRings(mc, tier, ratio);
        }
        if (tier >= ClientChargeHandler.CHARGE_TIERS && !fullChargeNotified) {
            fullChargeNotified = true;
            onFullCharge(mc);
        }
    }

    /** 每 tick 推进轨道相位，让粒子平滑流动 */
    private static void updateOrbits(float ratio) {
        double baseSpeed = 0.04 + ratio * 0.08; // 蓄力越高转越快
        for (int i = 0; i < ORBIT_PHASE.length; i++) {
            double speed = baseSpeed * (i % 2 == 0 ? 1.0 : -1.0) * (1.0 + i * 0.3);
            ORBIT_PHASE[i] = (ORBIT_PHASE[i] + speed) % (Math.PI * 2.0D);
        }
    }

    // ================================================================
    // 背后多轨道光环（竖直圆盘，正面观感最佳）
    // ================================================================

    /**
     * 玩家身后的多轨道光环（<b>竖直</b>圆盘，正面观感最佳）。
     * <p>
     * 每条轨道都位于「垂直于视线」的竖直平面内，圆心在玩家背后 0.6 ~ 1.4 格、
     * 高度约胸口；因此从正面（第三人称摄像机在身后）看过去是完整的圆形光环，
     * 而不是一条扁平的横线。
     * <p>
     * 蓄力越高：轨道越多、每条轨道每 tick 粒子越多、转速越快、颜色越炫。
     */
    private static void spawnBackOrbits(Minecraft mc, float progress) {
        Player p = mc.player;
        Vec3 look = p.getLookAngle();
        double cx = p.getX();
        double cy = p.getY() + 0.95D; // 胸口偏上，光环中心略高于腰部
        double cz = p.getZ();

        // 背后方向（水平分量取反）= 圆盘法线
        double backX = -look.x;
        double backZ = -look.z;
        double backLen = Math.sqrt(backX * backX + backZ * backZ);
        if (backLen < 1.0E-4D) { backX = 0.0D; backZ = -1.0D; } else { backX /= backLen; backZ /= backLen; }

        // 圆盘平面内的水平轴（与背后方向垂直）：right = cross(up, back)
        double rightX = backZ;
        double rightZ = -backX;

        // 圆盘圆心 = 玩家身后紧贴（随蓄力略微后退）
        double centerOffset = 0.6D + progress * 0.8D; // 0.6 → 1.4 格
        double orbitCx = cx + backX * centerOffset;
        double orbitCz = cz + backZ * centerOffset;

        // 解锁的轨道数 = 1~5 条
        int unlockedOrbits = Math.max(1, Math.round(progress * 5.0F));

        for (int orbit = 0; orbit < unlockedOrbits; orbit++) {
            double orbitRadius = 0.28D + orbit * 0.14D; // 0.28 → 0.84（直径 1.68，约等于玩家身高）
            int perTick = 1 + orbit;                     // 1 → 5
            double phaseOffset = ORBIT_PHASE[orbit];
            double spinDir = orbit % 2 == 0 ? 1.0D : -1.0D; // 相邻轨道反向旋转

            ParticleOptions type = orbitParticleType(orbit, progress);

            for (int i = 0; i < perTick; i++) {
                double angle = phaseOffset + (i / (double) perTick) * Math.PI * 2.0D;
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);

                // 竖直圆盘上的点：水平轴 * cos + 竖直轴 * sin
                double px = orbitCx + rightX * cos * orbitRadius;
                double py = cy + sin * orbitRadius;
                double pz = orbitCz + rightZ * cos * orbitRadius;

                // 沿视线方向的细微抖动（让环看起来有厚度，但不破坏圆盘形状）
                double jitter = (mc.level.random.nextDouble() - 0.5D) * 0.06D;
                px += backX * jitter;
                pz += backZ * jitter;

                // 速度：沿圆盘切线方向，形成「流光绕环」的观感
                double spin = 0.03D * spinDir;
                double vx = -sin * rightX * spin;
                double vy = cos * spin;
                double vz = -sin * rightZ * spin;

                mc.level.addParticle(type, px, py, pz, vx, vy, vz);
            }
        }

        // 额外：蓄力过半后，圆盘间隙里偶尔闪出电火花（"电流感"）
        if (progress > 0.5F) {
            int sparkCount = Math.round((progress - 0.5F) * 3.0F);
            for (int i = 0; i < sparkCount; i++) {
                double a = mc.level.random.nextDouble() * Math.PI * 2.0D;
                double r = 0.30D + mc.level.random.nextDouble() * 0.6D;
                mc.level.addParticle(
                        ParticleTypes.ELECTRIC_SPARK,
                        orbitCx + rightX * Math.cos(a) * r,
                        cy + Math.sin(a) * r,
                        orbitCz + rightZ * Math.cos(a) * r,
                        0.0D, 0.0D, 0.0D
                );
            }
        }
    }

    /**
     * 每条轨道用的粒子类型。蓄力越久越炫：
     * 1 档：CRIT（白）→ 2 档：END_ROD（靛蓝）→ 3 档：END_ROD + 零星 ELECTRIC_SPARK
     * → 4 档：END_ROD + 更多 ELECTRIC_SPARK → 5 档：END_ROD + 密集 ELECTRIC_SPARK
     */
    private static ParticleOptions orbitParticleType(int orbit, float progress) {
        if (progress >= 0.999F) {
            // 满蓄：30% 概率混 ELECTRIC_SPARK
            if (mcRandom() < 0.3F) return ParticleTypes.ELECTRIC_SPARK;
            return ParticleTypes.END_ROD;
        } else if (progress >= 0.6F) {
            if (mcRandom() < 0.15F) return ParticleTypes.ELECTRIC_SPARK;
            return ParticleTypes.END_ROD;
        } else if (progress >= 0.3F) {
            if (mcRandom() < 0.4F) return ParticleTypes.CRIT;
            return ParticleTypes.END_ROD;
        } else {
            return ParticleTypes.CRIT;
        }
    }

    // ================================================================
    // 脚下魂环（斗罗大陆风格：玩家脚下旋转的多层水平圆环）
    // ================================================================

    /**
     * 脚下魂环：玩家脚下 y - 0.15 处的水平圆环。
     * <p>
     * 每档蓄力解锁一层（1 档 1 环，5 档 5 环），半径递增、速度各异、方向有顺有逆。
     * 颜色按斗罗魂环等级递进：白（10 年）→ 黄（百年）→ 紫（千年）→ 黑（万年）→ 金红（十万年）。
     */
    private static void spawnSoulRings(Minecraft mc, int tier, float progress) {
        Player p = mc.player;
        double cx = p.getX();
        double cy = p.getY() - 0.15D; // 脚下半格以下，贴地
        double cz = p.getZ();

        // 魂环配置：半径（格）、每 tick 粒子数、旋转方向（+1 / -1）、相位
        // 每层旋转速度不同，有的顺时针有的逆时针，叠加起来很炫酷
        int ringCount = Math.min(tier, 5);
        double[] ringRadius = { 0.50D, 0.75D, 1.00D, 1.25D, 1.55D };
        int[] perTick        = { 4, 5, 6, 7, 8 };
        double[] ringSpeed   = { 0.06D, -0.09D, 0.12D, -0.15D, 0.18D }; // 方向交替，速度递增

        for (int ring = 0; ring < ringCount; ring++) {
            double phase = ORBIT_PHASE[ring]; // 复用上面的相位索引，速度方向不同
            double radius = ringRadius[ring];
            int n = perTick[ring];
            // 因为 ringSpeed 交替取负，直接用 ringSpeed * ORBIT_PHASE 不行，
            // 这里自己算一个带方向的 phase
            double myPhase = phase * Math.signum(ringSpeed[ring]);

            ParticleOptions type = soulRingParticleType(ring, progress);

            for (int i = 0; i < n; i++) {
                double angle = myPhase + (i / (double) n) * Math.PI * 2.0D;
                double px = cx + Math.cos(angle) * radius;
                double pz = cz + Math.sin(angle) * radius;
                double py = cy + (mc.level.random.nextDouble() - 0.5D) * 0.08D; // 几乎贴地

                // 速度：沿切线 + 轻微向外扩散 + 向上飘一点点（让环有"悬浮"感）
                double tangentX = -Math.sin(angle);
                double tangentZ = Math.cos(angle);
                double vx = tangentX * ringSpeed[ring] * 0.15D + Math.cos(angle) * 0.005D * progress;
                double vz = tangentZ * ringSpeed[ring] * 0.15D + Math.sin(angle) * 0.005D * progress;
                double vy = 0.005D + (mc.level.random.nextDouble() - 0.5D) * 0.01D;

                mc.level.addParticle(type, px, py, pz, vx, vy, vz);
            }
        }

        // 满蓄力时最内层魂环额外喷一圈电光（斗罗十万年魂环的感觉）
        if (progress >= 0.999F) {
            int extra = 6;
            for (int i = 0; i < extra; i++) {
                double a = mc.level.random.nextDouble() * Math.PI * 2.0D;
                double r = 0.45D + mc.level.random.nextDouble() * 0.08D;
                mc.level.addParticle(
                        ParticleTypes.ELECTRIC_SPARK,
                        cx + Math.cos(a) * r, cy + 0.05D, cz + Math.sin(a) * r,
                        0.0D, 0.03D, 0.0D
                );
            }
        }
    }

    /**
     * 每层魂环用的粒子类型（斗罗颜色递进）：
     * 0 白 → 1 黄（END_ROD 自带暖色） → 2 紫 → 3 黑（CRIT 混 END_ROD 加深） → 4 金红
     * Minecraft 粒子颜色不可直接指定，只能用不同粒子类型模拟：
     * 最通用：CRIT（白/中性）+ END_ROD（偏紫蓝）+ ELECTRIC_SPARK（蓝）
     * 黄色/金色没有直接粒子，靠满蓄力时 END_ROD 自带的颜色多样性来凑
     */
    private static ParticleOptions soulRingParticleType(int ring, float progress) {
        // 魂环等级越高越炫
        if (progress >= 0.999F) {
            // 满蓄：前 3 层 END_ROD（彩色），第 4 层混 ELECTRIC_SPARK，第 5 层也混
            if (ring >= 3 && mcRandom() < 0.25F) return ParticleTypes.ELECTRIC_SPARK;
            return ParticleTypes.END_ROD;
        }
        // 普通蓄力：低环 CRIT，中环 END_ROD，高环 END_ROD 为主
        if (ring <= 1) {
            return ParticleTypes.CRIT;
        } else if (ring <= 3) {
            if (mcRandom() < 0.35F) return ParticleTypes.CRIT;
            return ParticleTypes.END_ROD;
        } else {
            if (mcRandom() < 0.1F) return ParticleTypes.ELECTRIC_SPARK;
            return ParticleTypes.END_ROD;
        }
    }

    /** 从 Minecraft 全局随机取一个 0~1 的值 */
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
        // 背后方向（水平）
        double backX = -look.x;
        double backZ = -look.z;
        double backLen = Math.sqrt(backX * backX + backZ * backZ);
        if (backLen < 1.0E-4D) { backX = 0.0D; backZ = -1.0D; } else { backX /= backLen; backZ /= backLen; }

        double cx = p.getX();
        double cy = p.getY() + 1.0D;
        double cz = p.getZ();

        // ---- 背后 2 格处爆一圈竖直光环，向外飞散 ----
        double bx = cx + backX * 2.0D;
        double bz = cz + backZ * 2.0D;
        // 圆盘平面内的水平轴（与背后方向垂直）
        double rightX = backZ;
        double rightZ = -backX;
        for (int i = 0; i < 40; i++) {
            double a = Math.PI * 2.0D * i / 40.0D;
            double cos = Math.cos(a);
            double sin = Math.sin(a);
            double ex = bx + rightX * cos * 0.85D;
            double ey = cy + sin * 0.85D;
            double ez = bz + rightZ * cos * 0.85D;
            mc.level.addParticle(ParticleTypes.END_ROD,
                    ex, ey, ez,
                    rightX * cos * 0.25D, sin * 0.25D, rightZ * cos * 0.25D);
        }
        // 背后一颗音爆（视觉信号）
        mc.level.addParticle(ParticleTypes.SONIC_BOOM, bx, cy, bz, 0.0D, 0.0D, 0.0D);

        // ---- 脚下魂环炸出一道上冲的 ELECTRIC_SPARK 柱 ----
        for (int i = 0; i < 20; i++) {
            double a = mc.level.random.nextDouble() * Math.PI * 2.0D;
            double r = 0.4D + mc.level.random.nextDouble() * 1.1D;
            double px = cx + Math.cos(a) * r;
            double pz = cz + Math.sin(a) * r;
            mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK,
                    px, cy - 0.2D, pz,
                    0.0D, 0.15D + mc.level.random.nextDouble() * 0.1D, 0.0D);
        }

        // ---- 玩家周身少量电火花（量少，不挡眼，只有近身才看得到）----
        for (int i = 0; i < 12; i++) {
            double a = mc.level.random.nextDouble() * Math.PI * 2.0D;
            double r = 0.5D + mc.level.random.nextDouble() * 0.8D;
            mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK,
                    cx + Math.cos(a) * r,
                    cy + (mc.level.random.nextDouble() - 0.5D) * 0.9D,
                    cz + Math.sin(a) * r,
                    0.0D, 0.04D, 0.0D);
        }

        p.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.9F, 1.4F);
    }
}
