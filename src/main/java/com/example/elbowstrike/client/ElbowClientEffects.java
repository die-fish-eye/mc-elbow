package com.example.elbowstrike.client;

import com.example.elbowstrike.ElbowStrikeConfig;
import com.example.elbowstrike.ElbowStrikeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;

/**
 * 蓄力肘击 + 突进的客户端视觉反馈。
 * <p>
 * 配色统一走「冷色能量系」：主体使用原版的双色渐变尘粒子
 * {@link DustColorTransitionOptions}（{@code ParticleTypes.DUST_COLOR_TRANSITION}），
 * 它可以在原版条件下任意指定「起始色 → 结束色」并让每个粒子在寿命内自动渐变，
 * 而且尘粒子是全亮度渲染，不受环境光影响，颜色非常纯。
 * <p>
 * 三套特效：
 * <ul>
 *     <li><b>背后光环</b>：竖直圆盘，但只在圆盘上的一段弧里撒点、弧段随相位绕圈流动，
 *         因此看起来是「一道流光绕环跑」而不是一块实心圆盘；</li>
 *     <li><b>脚下魂环</b>：贴地（不陷进方块）的多层旋转环，颜色按冷色梯度递进，
 *         外加一圈每 4 tick 向外扩散的脉冲环；</li>
 *     <li><b>突进音爆</b>：突进期间每隔 3 tick 在身前生成 3 个竖直朝向的圆环，
 *         半径与前进距离逐批增大，形成音爆激波推进的观感。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID, value = Dist.CLIENT)
public final class ElbowClientEffects {

    // ================================================================
    // 冷色能量系配色（RGB 0~1）
    // ================================================================

    /** 青 */
    private static final Vector3f C_CYAN = new Vector3f(0.30F, 0.95F, 1.00F);
    /** 冰白 */
    private static final Vector3f C_ICE = new Vector3f(0.66F, 0.92F, 1.00F);
    /** 冰蓝 */
    private static final Vector3f C_BLUE = new Vector3f(0.22F, 0.62F, 1.00F);
    /** 靛 */
    private static final Vector3f C_INDIGO = new Vector3f(0.38F, 0.38F, 1.00F);
    /** 紫 */
    private static final Vector3f C_VIOLET = new Vector3f(0.62F, 0.34F, 1.00F);
    /** 电光白 */
    private static final Vector3f C_WHITE = new Vector3f(0.88F, 0.96F, 1.00F);
    /** 金（法阵主色） */
    private static final Vector3f C_GOLD = new Vector3f(1.00F, 0.78F, 0.30F);
    /** 橙 */
    private static final Vector3f C_ORANGE = new Vector3f(1.00F, 0.55F, 0.18F);
    /** 红橙 */
    private static final Vector3f C_RED = new Vector3f(1.00F, 0.35F, 0.20F);

    /** 彩虹循环周期（毫秒） */
    private static final long RAINBOW_CYCLE_MS = 2800L;

    /** 完成满蓄力提示是否已触发 */
    private static boolean fullChargeNotified = false;

    /** 每条背后轨道的流动相位（弧度），保证粒子是「流」而不是每帧随机跳动 */
    private static final double[] ORBIT_PHASE = new double[5];

    /** 脚下魂环向外扩散的脉冲：当前半径与计时 */
    private static double soulPulseRadius = 0.0D;
    private static int soulPulseTimer = 0;

    /** 突进音爆特效：剩余 tick 与批间隔计时 */
    private static int dashFxTicks = 0;
    private static int dashFxCalls = 0;

    private ElbowClientEffects() {}

    /** 突进开始（客户端松手发包时调用），启动音爆圆环特效 */
    public static void startDashEffect() {
        dashFxTicks = 12;
        dashFxCalls = 0;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            fullChargeNotified = false;
            dashFxTicks = 0;
            soulPulseTimer = 0;
            soulPulseRadius = 0.0D;
            return;
        }
        if (!ElbowStrikeConfig.COMMON.enableEffects.get()) return;

        // ---- 突进音爆：与蓄力状态无关，松手后才开始 ----
        if (dashFxTicks > 0) {
            tickDashFx(mc);
        }

        if (!ClientChargeHandler.isCharging()) {
            fullChargeNotified = false;
            soulPulseTimer = 0;
            soulPulseRadius = 0.0D;
            return;
        }

        int tier = ClientChargeHandler.getChargeTier();
        float ratio = tier / (float) ClientChargeHandler.CHARGE_TIERS;

        if (ElbowStrikeConfig.COMMON.chargeParticles.get()) {
            updateOrbitPhase(ratio);
            spawnBackOrbits(mc, tier, ratio);
            spawnSoulRings(mc, tier, ratio);
            tickSoulPulse(mc, ratio);
        }
        if (tier >= ClientChargeHandler.CHARGE_TIERS && !fullChargeNotified) {
            fullChargeNotified = true;
            onFullCharge(mc);
        }
    }

    // ================================================================
    // 通用工具
    // ================================================================

    /** 构造一个「起始色 → 结束色」的渐变尘粒子（原版自带，颜色可任意指定） */
    private static ParticleOptions dust(Vector3f from, Vector3f to, float scale) {
        return new DustColorTransitionOptions(from, to, scale);
    }

    /** HSV → Vector3f（用于满蓄时按角度取彩虹色） */
    private static Vector3f hsv(float hue, float sat, float val) {
        int rgb = Mth.hsvToRgb(hue - (float) Math.floor(hue), sat, val);
        return new Vector3f(
                ((rgb >> 16) & 0xFF) / 255.0F,
                ((rgb >> 8) & 0xFF) / 255.0F,
                (rgb & 0xFF) / 255.0F
        );
    }

    /** 当前彩虹相位（0~1，随时间循环） */
    private static float rainbowPhase() {
        return (System.currentTimeMillis() % RAINBOW_CYCLE_MS) / (float) RAINBOW_CYCLE_MS;
    }

    /** 每 tick 推进背后轨道的流动相位 */
    private static void updateOrbitPhase(float ratio) {
        double base = 0.05D + ratio * 0.07D;
        for (int i = 0; i < ORBIT_PHASE.length; i++) {
            double dir = i % 2 == 0 ? 1.0D : -1.0D;
            ORBIT_PHASE[i] = (ORBIT_PHASE[i] + base * dir * (1.0D + i * 0.25D)) % (Math.PI * 2.0D);
        }
    }

    // ================================================================
    // 背后光环：竖直圆盘上的「流动光弧」
    // ================================================================

    /**
     * 背后竖直光环。
     * <p>
     * 关键点：不在整圈撒点，只在圆盘上的一段弧（60° ~ 100°）内撒点，
     * 弧头随相位绕圈前进，其余部分留空 —— 于是看起来是一道流光在环上流动，
     * 而不是一块糊住视线的实心圆盘。
     */
    private static void spawnBackOrbits(Minecraft mc, int tier, float progress) {
        Player p = mc.player;
        Vec3 look = p.getLookAngle();
        double cx = p.getX();
        double cy = p.getY() + 0.95D;
        double cz = p.getZ();

        // 背后方向（水平）= 圆盘法线
        double backX = -look.x;
        double backZ = -look.z;
        double len = Math.sqrt(backX * backX + backZ * backZ);
        if (len < 1.0E-4D) { backX = 0.0D; backZ = -1.0D; } else { backX /= len; backZ /= len; }
        // 圆盘平面内的水平轴
        double rightX = backZ;
        double rightZ = -backX;

        double centerOffset = 0.6D + progress * 0.8D;
        double orbitCx = cx + backX * centerOffset;
        double orbitCz = cz + backZ * centerOffset;

        // 轨道数 1~3（不再 5 条，避免拥挤）
        int orbits = Math.max(1, Math.min(3, (tier + 1) / 2));
        // 弧段长度：60° → 100°
        double arcLen = Math.toRadians(60.0D + progress * 40.0D);
        float phase = rainbowPhase();

        for (int orbit = 0; orbit < orbits; orbit++) {
            double radius = 0.55D + orbit * 0.30D;   // 0.55 / 0.85 / 1.15
            int perTick = 4 + orbit;                  // 4 / 5 / 6
            double spinDir = orbit % 2 == 0 ? 1.0D : -1.0D;
            double head = ORBIT_PHASE[orbit] * spinDir;

            for (int i = 0; i < perTick; i++) {
                double a = head + arcLen * (i / (double) perTick);
                double cos = Math.cos(a);
                double sin = Math.sin(a);

                double px = orbitCx + rightX * cos * radius;
                double py = cy + sin * radius;
                double pz = orbitCz + rightZ * cos * radius;

                // 沿视线方向的薄厚度
                double jitter = (mc.level.random.nextDouble() - 0.5D) * 0.05D;
                px += backX * jitter;
                pz += backZ * jitter;

                // 沿圆弧切线方向流动
                double spin = 0.045D * spinDir;
                double vx = -sin * rightX * spin;
                double vy = cos * spin;
                double vz = -sin * rightZ * spin;

                mc.level.addParticle(orbitDust(orbit, progress, a, phase), px, py, pz, vx, vy, vz);
            }
        }
    }

    /** 背后轨道的渐变色：冷色梯度；满蓄时按角度取彩虹 */
    private static ParticleOptions orbitDust(int orbit, float progress, double angle, float phase) {
        if (progress >= 0.999F) {
            float hue = (float) (angle / (Math.PI * 2.0D)) + phase;
            return dust(hsv(hue, 0.70F, 1.0F), hsv(hue + 0.12F, 0.85F, 1.0F), 1.15F);
        }
        float scale = 0.85F + progress * 0.30F;
        return switch (orbit) {
            case 0 -> dust(C_CYAN, C_ICE, scale);
            case 1 -> dust(C_BLUE, C_ICE, scale);
            default -> dust(C_INDIGO, C_VIOLET, scale);
        };
    }

    // ================================================================
    // 脚下六芒星阵：多层同心六芒星 + 扩散脉冲
    // ================================================================

    /**
     * 脚下六芒星阵（替代原先的圆环）。
     * <p>
     * 每层是一个同心六芒星（两个重叠等边三角形），半径递增、方向交替旋转，
     * 整体放大到最大 1.8 格，更有法阵气势。
     * 主色为金白，中间层过渡冷色，满蓄时全阵走彩虹。
     */
    private static void spawnSoulRings(Minecraft mc, int tier, float progress) {
        Player p = mc.player;
        double cx = p.getX();
        double cy = p.getY() + 0.08D;   // 浮在地面上方
        double cz = p.getZ();

        int layers = Math.min(tier, 5);
        // 半径放大到最大 5 格，法阵更有气势
        double[] radius = { 1.00D, 2.00D, 3.00D, 4.00D, 5.00D };
        // 半径大，每边粒子数也要增加，避免边断断续续
        int[] perEdge = { 6, 8, 10, 12, 14 };
        double[] rotSpeed = { 0.06D, -0.09D, 0.13D, -0.16D, 0.19D };
        float phase = rainbowPhase();

        for (int layer = 0; layer < layers; layer++) {
            double r = radius[layer];
            int n = perEdge[layer];
            double dir = Math.signum(rotSpeed[layer]);
            double rot = ORBIT_PHASE[layer] * dir;

            // 六芒星 6 个顶点 + 6 条边（正三角 3 边 + 倒三角 3 边）
            double[][] verts = hexagramVertices(cx, cz, r, rot);
            int[][] edges = { {0,1},{1,2},{2,0}, {3,4},{4,5},{5,3} };

            // 沿每条边撒粒子
            for (int[] e : edges) {
                double[] a = verts[e[0]];
                double[] b = verts[e[1]];
                double ex = b[0] - a[0];
                double ez = b[1] - a[1];
                double elen = Math.sqrt(ex * ex + ez * ez);
                double vx = elen > 1e-4D ? (ex / elen) * 0.045D * dir : 0.0D;
                double vz = elen > 1e-4D ? (ez / elen) * 0.045D * dir : 0.0D;

                for (int i = 0; i <= n; i++) {
                    double t = i / (double) n;
                    double px = a[0] + ex * t;
                    double pz = a[1] + ez * t;
                    double py = cy + (mc.level.random.nextDouble() - 0.5D) * 0.04D;
                    mc.level.addParticle(hexagramDust(layer, progress, phase), px, py, pz, vx, 0.006D, vz);
                }
            }

            // 6 个尖角处各点一颗高亮光（金色尖角，法阵点睛）
            for (double[] v : verts) {
                mc.level.addParticle(
                        dust(C_GOLD, C_WHITE, 1.30F),
                        v[0], cy, v[1],
                        0.0D, 0.01D, 0.0D
                );
            }
        }

        // 阵心：向上的光柱（少量粒子，点缀）
        if (progress > 0.3F) {
            int coreN = Math.round((progress - 0.3F) * 6.0F);
            for (int i = 0; i < coreN; i++) {
                double a = mc.level.random.nextDouble() * Math.PI * 2.0D;
                double r = mc.level.random.nextDouble() * 0.12D;
                mc.level.addParticle(
                        dust(C_GOLD, C_WHITE, 1.1F),
                        cx + Math.cos(a) * r, cy, cz + Math.sin(a) * r,
                        0.0D, 0.08D + mc.level.random.nextDouble() * 0.05D, 0.0D
                );
            }
        }
    }

    /**
     * 计算六芒星的 6 个顶点（XZ 平面）。
     * 0~2：正三角（顶点朝上）；3~5：倒三角（顶点朝下）。
     */
    private static double[][] hexagramVertices(double cx, double cz, double r, double rot) {
        double[][] v = new double[6][2];
        for (int i = 0; i < 3; i++) {
            double a = rot + Math.PI / 2.0D + i * Math.PI * 2.0D / 3.0D;
            v[i][0] = cx + Math.cos(a) * r;
            v[i][1] = cz + Math.sin(a) * r;
        }
        for (int i = 0; i < 3; i++) {
            double a = rot - Math.PI / 2.0D + i * Math.PI * 2.0D / 3.0D;
            v[3 + i][0] = cx + Math.cos(a) * r;
            v[3 + i][1] = cz + Math.sin(a) * r;
        }
        return v;
    }

    /**
     * 六芒星每层配色：暖色系（金/橙/红），与背后冷色光环形成冷暖对比。
     * 满蓄时保持金白高亮（不走彩虹，避免与背后彩虹光环冲突）。
     */
    private static ParticleOptions hexagramDust(int layer, float progress, float phase) {
        float scale = 1.00F + layer * 0.04F;
        return switch (layer) {
            case 0 -> dust(C_GOLD, C_WHITE, scale);          // 最内：金 → 白
            case 1 -> dust(C_ORANGE, C_GOLD, scale);          // 橙 → 金
            case 2 -> dust(C_RED, C_ORANGE, scale);           // 红 → 橙
            case 3 -> dust(C_GOLD, C_ORANGE, scale);          // 金 → 橙
            default -> dust(C_WHITE, C_GOLD, scale + 0.1F);   // 最外：白 → 金（最大最亮）
        };
    }

    /** 脚下每隔 2 tick 释放一圈向外扩散的六芒星脉冲 */
    private static void tickSoulPulse(Minecraft mc, float progress) {
        if (soulPulseRadius < 1.0D) soulPulseRadius = 1.00D;
        soulPulseTimer++;
        if (soulPulseTimer % 2 != 0) {
            soulPulseRadius += 0.22D + progress * 0.10D;
            if (soulPulseRadius > 5.5D) soulPulseRadius = 1.00D;
            return;
        }

        Player p = mc.player;
        double cx = p.getX();
        double cy = p.getY() + 0.10D;
        double cz = p.getZ();

        double r = soulPulseRadius;
        double rot = System.currentTimeMillis() / 2000.0D;
        double[][] verts = hexagramVertices(cx, cz, r, rot);
        int[][] edges = { {0,1},{1,2},{2,0}, {3,4},{4,5},{5,3} };
        int n = 8;

        for (int[] e : edges) {
            double[] a = verts[e[0]];
            double[] b = verts[e[1]];
            for (int i = 0; i <= n; i++) {
                double t = i / (double) n;
                double px = a[0] + (b[0] - a[0]) * t;
                double pz = a[1] + (b[1] - a[1]) * t;
                // 径向向外扩散
                double dx = px - cx;
                double dz = pz - cz;
                double dlen = Math.sqrt(dx * dx + dz * dz);
                double vx = dlen > 1e-4D ? (dx / dlen) * 0.08D : 0.0D;
                double vz = dlen > 1e-4D ? (dz / dlen) * 0.08D : 0.0D;
                mc.level.addParticle(dust(C_GOLD, C_ORANGE, 1.05F), px, cy, pz, vx, 0.004D, vz);
            }
        }
        soulPulseRadius += 0.22D + progress * 0.10D;
        if (soulPulseRadius > 5.5D) soulPulseRadius = 1.00D;
    }

    // ================================================================
    // 突进音爆：3 个竖直朝向的圆环逐批变大
    // ================================================================

    /**
     * 突进期间每隔 3 tick 生成一批音爆圆环。
     * <p>
     * 每批 3 个环，环平面垂直于玩家前进方向（即「竖着朝向玩家方向」），
     * 半径与前进距离随批次递增 —— 由小变大的激波一层层向前推开。
     */
    private static void tickDashFx(Minecraft mc) {
        // 首批直接带音爆粒子与音效
        if (dashFxTicks == 12) {
            Player p = mc.player;
            Vec3 look = p.getLookAngle();
            mc.level.addParticle(ParticleTypes.SONIC_BOOM,
                    p.getX() + look.x * 0.8D, p.getY() + 1.0D, p.getZ() + look.z * 0.8D,
                    0.0D, 0.0D, 0.0D);
            // 用原版爆炸声拉高音调，模拟音爆的短促爆裂感
            p.playSound(SoundEvents.GENERIC_EXPLODE, 0.55F, 1.9F);
            p.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 0.6F);
        }

        dashFxTicks--;
        if (dashFxCalls % 3 == 0) {
            spawnDashShockRings(mc, (12 - dashFxTicks) / 12.0F);
        }
        dashFxCalls++;
    }

    /** 一批 3 个激波环：半径、距离都递增 */
    private static void spawnDashShockRings(Minecraft mc, float progress) {
        Player p = mc.player;
        Vec3 look = p.getLookAngle();

        // 水平前进方向（归一化）
        double fx = look.x;
        double fz = look.z;
        double len = Math.sqrt(fx * fx + fz * fz);
        if (len < 1.0E-4D) { fx = 0.0D; fz = 1.0D; } else { fx /= len; fz /= len; }
        // 环平面内的水平轴
        double rightX = fz;
        double rightZ = -fx;

        double cx = p.getX();
        double cy = p.getY() + 1.0D;
        double cz = p.getZ();

        double[] baseRadius = { 0.45D, 0.85D, 1.30D };
        double[] baseDist = { 0.55D, 0.95D, 1.40D };
        float grow = 0.85F + progress * 0.55F; // 由小变大

        for (int k = 0; k < 3; k++) {
            double r = baseRadius[k] * grow;
            double dist = baseDist[k] + progress * 0.6D;
            double ringCx = cx + fx * dist;
            double ringCz = cz + fz * dist;
            int n = 20 + k * 6;

            for (int i = 0; i < n; i++) {
                double a = Math.PI * 2.0D * i / n;
                double cos = Math.cos(a);
                double sin = Math.sin(a);

                double px = ringCx + rightX * cos * r;
                double py = cy + sin * r;
                double pz = ringCz + rightZ * cos * r;

                // 向外扩张 + 向前推进
                double vx = rightX * cos * 0.07D + fx * 0.14D;
                double vy = sin * 0.07D;
                double vz = rightZ * cos * 0.07D + fz * 0.14D;

                mc.level.addParticle(
                        dust(C_WHITE, C_CYAN, 1.05F + k * 0.15F),
                        px, py, pz, vx, vy, vz
                );
            }
        }
    }

    // ================================================================
    // 满蓄力瞬间爆发（一次性）
    // ================================================================

    private static void onFullCharge(Minecraft mc) {
        Player p = mc.player;
        Vec3 look = p.getLookAngle();
        double backX = -look.x;
        double backZ = -look.z;
        double len = Math.sqrt(backX * backX + backZ * backZ);
        if (len < 1.0E-4D) { backX = 0.0D; backZ = -1.0D; } else { backX /= len; backZ /= len; }
        double rightX = backZ;
        double rightZ = -backX;

        double cx = p.getX();
        double cy = p.getY() + 1.0D;
        double cz = p.getZ();
        float phase = rainbowPhase();

        // 背后爆一圈竖直彩虹环
        double bx = cx + backX * 1.6D;
        double bz = cz + backZ * 1.6D;
        for (int i = 0; i < 36; i++) {
            double a = Math.PI * 2.0D * i / 36.0D;
            double cos = Math.cos(a);
            double sin = Math.sin(a);
            float hue = (float) (a / (Math.PI * 2.0D)) + phase;
            mc.level.addParticle(
                    dust(hsv(hue, 0.70F, 1.0F), hsv(hue + 0.15F, 0.90F, 1.0F), 1.35F),
                    bx + rightX * cos * 0.9D, cy + sin * 0.9D, bz + rightZ * cos * 0.9D,
                    rightX * cos * 0.22D, sin * 0.22D, rightZ * cos * 0.22D
            );
        }
        mc.level.addParticle(ParticleTypes.SONIC_BOOM, bx, cy, bz, 0.0D, 0.0D, 0.0D);

        // 脚下魂环炸出一道上冲的电光柱
        for (int i = 0; i < 24; i++) {
            double a = mc.level.random.nextDouble() * Math.PI * 2.0D;
            double r = 0.4D + mc.level.random.nextDouble() * 1.0D;
            mc.level.addParticle(
                    dust(C_CYAN, C_WHITE, 1.2F),
                    cx + Math.cos(a) * r, cy - 0.3D, cz + Math.sin(a) * r,
                    Math.cos(a) * 0.05D, 0.14D + mc.level.random.nextDouble() * 0.08D, Math.sin(a) * 0.05D
            );
        }

        p.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.9F, 1.4F);
    }
}
