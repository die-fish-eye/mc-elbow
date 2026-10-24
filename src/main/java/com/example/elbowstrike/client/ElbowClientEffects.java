package com.example.elbowstrike.client;

import com.example.elbowstrike.ElbowStrikeConfig;
import com.example.elbowstrike.ElbowStrikeMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 蓄力肘击 + 突进的客户端视觉反馈。
 * <p>
 * 配色统一走「星空系」：以夜空蓝 / 星蓝 / 星紫为底色，星芒青白作高光，
 * 主体使用原版的双色渐变尘粒子 {@link DustColorTransitionOptions}
 * （{@code ParticleTypes.DUST_COLOR_TRANSITION}），可在原版条件下任意指定
 * 「起始色 → 结束色」并让每个粒子在寿命内自动渐变，且尘粒子全亮度渲染、
 * 不受环境光影响。
 * <p>
 * 三套特效：
 * <ul>
 *     <li><b>背后光环</b>：竖直圆盘，但只在圆盘上的一段弧里撒点、弧段随相位绕圈流动，
 *         因此看起来是「一道流光绕环跑」而不是一块实心圆盘；</li>
 *     <li><b>脚下六芒星阵</b>：贴地（不陷进方块）的 1~3 层同心六芒星，半径最大 5 格，
 *         顶点星芒勾勒形状，阵内散布稀疏星点，外加一圈缓慢向外扩散的六芒星脉冲；</li>
 *     <li><b>突进音爆</b>：突进期间每隔 3 tick 在身前生成 3 个竖直朝向的圆环，
 *         半径与前进距离逐批增大，形成音爆激波推进的观感。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID, value = Dist.CLIENT)
public final class ElbowClientEffects {

    // ================================================================
    // 星空配色（RGB 0~1）：以深蓝 / 靛 / 紫为底色，青白为星芒
    // ================================================================

    /** 夜空蓝（最暗底色） */
    private static final Vector3f C_NIGHT = new Vector3f(0.10F, 0.13F, 0.42F);
    /** 星蓝 */
    private static final Vector3f C_STARBLUE = new Vector3f(0.20F, 0.32F, 0.85F);
    /** 星紫 */
    private static final Vector3f C_STARPURPLE = new Vector3f(0.48F, 0.32F, 0.90F);
    /** 星芒白（星点高光） */
    private static final Vector3f C_STARWHITE = new Vector3f(0.92F, 0.97F, 1.00F);
    /** 青（星芒冷光） */
    private static final Vector3f C_CYAN = new Vector3f(0.30F, 0.88F, 1.00F);

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

    /** 脚下六芒星阵贴图（用户自备：512x512 正方形、透明背景、图案居中） */
    private static final ResourceLocation HEXAGRAM_TEX =
            new ResourceLocation(ElbowStrikeMod.MODID, "textures/effect/hexagram.png");

    /** 贴图是否可用；null = 尚未检测。缺失时自动回退到粒子版六芒星 */
    private static Boolean hexagramTexAvailable = null;

    private ElbowClientEffects() {}

    /**
     * 检测六芒星贴图是否存在（只检测一次）。
     * 用户把图放到 {@code assets/elbowstrike/textures/effect/hexagram.png} 后自动生效；
     * 没放时自动回退到粒子拼的六芒星，不会出现紫黑方块。
     */
    private static boolean hexagramTexAvailable() {
        if (hexagramTexAvailable == null) {
            boolean ok = false;
            try {
                ok = !Minecraft.getInstance().getResourceManager()
                        .listResources("textures/effect",
                                rl -> rl.getPath().equals("textures/effect/hexagram.png"))
                        .isEmpty();
            } catch (RuntimeException ignored) {
                ok = false;
            }
            hexagramTexAvailable = ok;
        }
        return hexagramTexAvailable;
    }

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
            // 有贴图时用贴图渲染法阵（见 onRenderLevel），否则回退到粒子版
            if (!hexagramTexAvailable()) {
                spawnSoulRings(mc, tier, ratio);
                tickSoulPulse(mc, ratio);
            }
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

                mc.level.addParticle(orbitDust(orbit, progress), px, py, pz, vx, vy, vz);
            }
        }
    }

    /** 背后轨道的星空配色：深蓝打底、蓝紫过渡，满蓄时整环转为星芒青白 */
    private static ParticleOptions orbitDust(int orbit, float progress) {
        float scale = 0.85F + progress * 0.25F;
        if (progress >= 0.999F) {
            return dust(C_STARWHITE, C_CYAN, 1.15F);
        }
        return switch (orbit) {
            case 0 -> dust(C_NIGHT, C_STARBLUE, scale);
            case 1 -> dust(C_STARBLUE, C_STARPURPLE, scale);
            default -> dust(C_STARPURPLE, C_STARBLUE, scale);
        };
    }

    // ================================================================
    // 脚下六芒星阵：多层同心六芒星 + 扩散脉冲
    // ================================================================

    /**
     * 脚下六芒星阵（星空版）。
     * <p>
     * 每层是一个同心六芒星（两个重叠等边三角形），半径递增、方向交替旋转，
     * 最大半径 5 格。为控制粒子密度、保证法阵线条清晰：
     * <ul>
     *     <li>层数最多 3 层（蓄力档位 1~2 → 1 层，3~4 → 2 层，5 → 3 层）；</li>
     *     <li>每条边的粒子数按边长自适应，间隔约 0.5 ~ 1.1 格，不再糊成一片；</li>
     *     <li>顶点用更亮的星芒单点勾勒，形状靠「顶点 + 稀疏边」表达。</li>
     * </ul>
     * 配色统一为星空系（夜空蓝 → 星蓝 → 星紫 → 青白），不使用彩虹。
     */
    private static void spawnSoulRings(Minecraft mc, int tier, float progress) {
        Player p = mc.player;
        double cx = p.getX();
        double cy = p.getY() + 0.08D;   // 浮在地面上方
        double cz = p.getZ();

        // 层数：1~3 层，避免多层叠加糊成一团
        int layers = Math.max(1, Math.min(3, (tier + 1) / 2));
        double[] radius = { 1.60D, 3.20D, 5.00D };
        int[] perEdge = { 5, 6, 8 };               // 每条边的分段数（控制密度）
        double[] rotSpeed = { 0.05D, -0.08D, 0.11D };

        for (int layer = 0; layer < layers; layer++) {
            double r = radius[layer];
            int n = perEdge[layer];
            double dir = Math.signum(rotSpeed[layer]);
            double rot = ORBIT_PHASE[layer] * dir;

            // 六芒星 6 个顶点 + 6 条边（正三角 3 边 + 倒三角 3 边）
            double[][] verts = hexagramVertices(cx, cz, r, rot);
            int[][] edges = { {0,1},{1,2},{2,0}, {3,4},{4,5},{5,3} };

            ParticleOptions edgeDust = hexagramDust(layer, progress);

            for (int[] e : edges) {
                double[] a = verts[e[0]];
                double[] b = verts[e[1]];
                double ex = b[0] - a[0];
                double ez = b[1] - a[1];
                double elen = Math.sqrt(ex * ex + ez * ez);
                double vx = elen > 1e-4D ? (ex / elen) * 0.035D * dir : 0.0D;
                double vz = elen > 1e-4D ? (ez / elen) * 0.035D * dir : 0.0D;

                for (int i = 0; i < n; i++) {
                    double t = i / (double) n;
                    double px = a[0] + ex * t;
                    double pz = a[1] + ez * t;
                    double py = cy + (mc.level.random.nextDouble() - 0.5D) * 0.03D;
                    mc.level.addParticle(edgeDust, px, py, pz, vx, 0.005D, vz);
                }
            }

            // 6 个尖角：一颗更亮的星芒，勾勒法阵形状
            for (double[] v : verts) {
                mc.level.addParticle(
                        dust(C_STARWHITE, C_CYAN, 1.20F),
                        v[0], cy, v[1],
                        0.0D, 0.01D, 0.0D
                );
            }
        }

        // 阵内稀疏星点：低密度、缓慢上飘，营造星空氛围
        int stars = 2 + Math.round(progress * 2.0F);
        for (int i = 0; i < stars; i++) {
            double a = mc.level.random.nextDouble() * Math.PI * 2.0D;
            double rr = mc.level.random.nextDouble() * radius[layers - 1] * 0.9D;
            mc.level.addParticle(
                    dust(C_STARWHITE, C_STARBLUE, 0.70F),
                    cx + Math.cos(a) * rr, cy + mc.level.random.nextDouble() * 0.25D, cz + Math.sin(a) * rr,
                    0.0D, 0.012D, 0.0D
            );
        }

        // 阵心：少量向上光点
        if (progress > 0.4F) {
            int coreN = Math.round((progress - 0.4F) * 4.0F);
            for (int i = 0; i < coreN; i++) {
                double a = mc.level.random.nextDouble() * Math.PI * 2.0D;
                double rr = mc.level.random.nextDouble() * 0.10D;
                mc.level.addParticle(
                        dust(C_STARWHITE, C_CYAN, 0.95F),
                        cx + Math.cos(a) * rr, cy, cz + Math.sin(a) * rr,
                        0.0D, 0.06D + mc.level.random.nextDouble() * 0.04D, 0.0D
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
     * 六芒星每层配色：统一星空系（夜空蓝 → 星蓝 → 星紫）。
     * 满蓄时整阵升为星芒青白高亮。
     */
    private static ParticleOptions hexagramDust(int layer, float progress) {
        if (progress >= 0.999F) {
            return dust(C_STARWHITE, C_CYAN, 1.10F + layer * 0.05F);
        }
        float scale = 0.95F + layer * 0.05F;
        return switch (layer) {
            case 0 -> dust(C_NIGHT, C_STARBLUE, scale);        // 最内：夜空蓝 → 星蓝
            case 1 -> dust(C_STARBLUE, C_STARPURPLE, scale);   // 中：星蓝 → 星紫
            default -> dust(C_STARPURPLE, C_STARBLUE, scale);  // 最外：星紫 → 星蓝
        };
    }

    /** 脚下每隔 4 tick 释放一圈向外扩散的六芒星脉冲（低密度，避免糊成一片） */
    private static void tickSoulPulse(Minecraft mc, float progress) {
        if (soulPulseRadius < 1.6D) soulPulseRadius = 1.60D;
        soulPulseTimer++;
        if (soulPulseTimer % 4 != 0) {
            soulPulseRadius += 0.20D + progress * 0.10D;
            if (soulPulseRadius > 5.6D) soulPulseRadius = 1.60D;
            return;
        }

        Player p = mc.player;
        double cx = p.getX();
        double cy = p.getY() + 0.10D;
        double cz = p.getZ();

        double r = soulPulseRadius;
        double rot = System.currentTimeMillis() / 2600.0D;
        double[][] verts = hexagramVertices(cx, cz, r, rot);
        int[][] edges = { {0,1},{1,2},{2,0}, {3,4},{4,5},{5,3} };
        int n = 5;

        ParticleOptions pulseDust = dust(C_STARBLUE, C_CYAN, 0.85F);

        for (int[] e : edges) {
            double[] a = verts[e[0]];
            double[] b = verts[e[1]];
            for (int i = 0; i < n; i++) {
                double t = i / (double) n;
                double px = a[0] + (b[0] - a[0]) * t;
                double pz = a[1] + (b[1] - a[1]) * t;
                // 径向向外扩散
                double dx = px - cx;
                double dz = pz - cz;
                double dlen = Math.sqrt(dx * dx + dz * dz);
                double vx = dlen > 1e-4D ? (dx / dlen) * 0.06D : 0.0D;
                double vz = dlen > 1e-4D ? (dz / dlen) * 0.06D : 0.0D;
                mc.level.addParticle(pulseDust, px, cy, pz, vx, 0.004D, vz);
            }
        }
        soulPulseRadius += 0.20D + progress * 0.10D;
        if (soulPulseRadius > 5.6D) soulPulseRadius = 1.60D;
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
                        k == 0 ? dust(C_STARWHITE, C_CYAN, 1.05F)
                               : dust(C_STARWHITE, C_STARBLUE, 1.05F + k * 0.12F),
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

        // 背后爆一圈竖直星环（星空色，不用彩虹）
        double bx = cx + backX * 1.6D;
        double bz = cz + backZ * 1.6D;
        for (int i = 0; i < 28; i++) {
            double a = Math.PI * 2.0D * i / 28.0D;
            double cos = Math.cos(a);
            double sin = Math.sin(a);
            // 上半环偏星芒白，下半环偏星蓝，形成「星空环」层次
            boolean upper = sin > 0.0D;
            mc.level.addParticle(
                    upper ? dust(C_STARWHITE, C_CYAN, 1.30F) : dust(C_STARBLUE, C_STARPURPLE, 1.25F),
                    bx + rightX * cos * 0.9D, cy + sin * 0.9D, bz + rightZ * cos * 0.9D,
                    rightX * cos * 0.22D, sin * 0.22D, rightZ * cos * 0.22D
            );
        }
        mc.level.addParticle(ParticleTypes.SONIC_BOOM, bx, cy, bz, 0.0D, 0.0D, 0.0D);

        // 脚下炸出一道上冲的星芒光柱
        for (int i = 0; i < 20; i++) {
            double a = mc.level.random.nextDouble() * Math.PI * 2.0D;
            double r = 0.4D + mc.level.random.nextDouble() * 1.2D;
            mc.level.addParticle(
                    dust(C_STARWHITE, C_STARBLUE, 1.15F),
                    cx + Math.cos(a) * r, cy - 0.3D, cz + Math.sin(a) * r,
                    Math.cos(a) * 0.05D, 0.14D + mc.level.random.nextDouble() * 0.08D, Math.sin(a) * 0.05D
            );
        }

        p.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.9F, 1.4F);
    }

    // ================================================================
    // 脚下六芒星阵：贴图渲染（世界空间，真正平铺在地面）
    // ================================================================

    /**
     * 把 {@link #HEXAGRAM_TEX} 平铺在玩家脚下。
     * <p>
     * 用 PoseStack 先移到脚下、绕 Y 轴自转、再绕 X 轴 -90° 把四边形放平，
     * 因此法阵是「真正躺在地上」的 —— 不会像粒子贴图那样随摄像机转动而竖起来。
     * <p>
     * 每帧画两遍：主阵 + 一圈放大并淡出的残影，形成向外扩散的脉冲。
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (!hexagramTexAvailable()) return;
        if (!ElbowStrikeConfig.COMMON.enableEffects.get()) return;
        if (!ElbowStrikeConfig.COMMON.chargeParticles.get()) return;
        if (!ClientChargeHandler.isCharging()) return;

        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (p == null) return;

        int tier = ClientChargeHandler.getChargeTier();
        float ratio = (tier - 1) / (float) (ClientChargeHandler.CHARGE_TIERS - 1); // 0 ~ 1

        float pt = event.getPartialTick();
        double px = Mth.lerp(pt, (float) p.xOld, (float) p.getX());
        double py = Mth.lerp(pt, (float) p.yOld, (float) p.getY());
        double pz = Mth.lerp(pt, (float) p.zOld, (float) p.getZ());

        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(px - cam.x, py + 0.05D - cam.y, pz - cam.z);

        long now = System.currentTimeMillis();
        // 自转：基础 18°/秒，满蓄 56°/秒
        float deg = (now % 360_000L) / 1000.0F * (18.0F + ratio * 38.0F);
        pose.mulPose(Axis.YP.rotationDegrees(deg));
        // 放平到地面
        pose.mulPose(Axis.XP.rotationDegrees(-90.0F));

        // 半径随档位从 1.6 格增长到 5.0 格（贴图为正方形，half = 半径）
        float half = 1.6F + ratio * 3.4F;
        // 颜色：星空蓝白 → 满蓄亮青白
        float r = 0.42F + ratio * 0.33F;
        float g = 0.68F + ratio * 0.24F;
        float b = 1.00F;
        float alpha = 0.35F + ratio * 0.45F;

        // 脉冲残影：1.8 秒一轮，向外扩散并淡出
        float pulse = (now % 1800L) / 1800.0F;
        float pulseHalf = half * (1.0F + pulse * 0.55F);
        float pulseAlpha = alpha * (1.0F - pulse) * 0.55F;

        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, HEXAGRAM_TEX);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        Matrix4f m = pose.last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        addTexQuad(buf, m, pulseHalf, r, g, b, pulseAlpha);
        addTexQuad(buf, m, half, r, g, b, alpha);
        BufferUploader.drawWithShader(buf.end());

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        pose.popPose();
    }

    /** 在局部 XY 平面画一个带贴图的四边形 */
    private static void addTexQuad(BufferBuilder buf, Matrix4f m, float half,
                                   float r, float g, float b, float a) {
        buf.vertex(m, -half, -half, 0.0F).uv(0.0F, 1.0F).color(r, g, b, a).endVertex();
        buf.vertex(m,  half, -half, 0.0F).uv(1.0F, 1.0F).color(r, g, b, a).endVertex();
        buf.vertex(m,  half,  half, 0.0F).uv(1.0F, 0.0F).color(r, g, b, a).endVertex();
        buf.vertex(m, -half,  half, 0.0F).uv(0.0F, 0.0F).color(r, g, b, a).endVertex();
    }
}
