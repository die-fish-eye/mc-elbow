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
 *     <li><b>背后光环</b>：竖直圆盘，只在圆盘上的一段弧里撒点，弧头带高亮彗星头、
 *         弧尾渐隐成拖尾，随相位绕圈流动；轨道数 2~4 条交替正反转，另有偶发远星点缀；</li>
 *     <li><b>脚下六芒星阵</b>：直接渲染用户自备的贴图（{@code textures/effect/hexagram.png}），
 *         平铺在地面、缓慢自转、随蓄力进度丝滑放大；贴图缺失时不绘制；</li>
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

    /** 脚下六芒星阵：当前旋转角（度），按帧增量累加，保证转速变化时丝滑不跳变 */
    private static float hexagramAngle = 0.0F;

    /** 上一帧渲染时间（纳秒），用于计算帧间隔 */
    private static long hexagramLastNanos = 0L;

    /** 突进音爆特效：剩余 tick 与批间隔计时 */
    private static int dashFxTicks = 0;
    private static int dashFxCalls = 0;

    /** 脚下六芒星阵贴图（用户自备：512x512 正方形、透明背景、图案居中） */
    private static final ResourceLocation HEXAGRAM_TEX =
            new ResourceLocation(ElbowStrikeMod.MODID, "textures/effect/hexagram.png");

    /** 贴图是否可用；null = 尚未检测。缺失时整张法阵不渲染（不会出现紫黑方块） */
    private static Boolean hexagramTexAvailable = null;

    private ElbowClientEffects() {}

    /**
     * 检测六芒星贴图是否存在（只检测一次）。
     * 用户把图放到 {@code assets/elbowstrike/textures/effect/hexagram.png} 后自动生效；
     * 没放时法阵不显示（背后光环与突进特效不受影响）。
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
            return;
        }
        if (!ElbowStrikeConfig.COMMON.enableEffects.get()) return;

        // ---- 突进音爆：与蓄力状态无关，松手后才开始 ----
        if (dashFxTicks > 0) {
            tickDashFx(mc);
        }

        if (!ClientChargeHandler.isCharging()) {
            fullChargeNotified = false;
            return;
        }

        int tier = ClientChargeHandler.getChargeTier();
        float ratio = tier / (float) ClientChargeHandler.CHARGE_TIERS;

        if (ElbowStrikeConfig.COMMON.chargeParticles.get()) {
            updateOrbitPhase(ratio);
            spawnBackOrbits(mc, tier, ratio);
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

        // 轨道数 2~4：起手就两条，蓄满四条，层次更丰富
        int orbits = Math.min(4, 1 + (tier + 1) / 2);
        // 弧段长度：50° → 110°，流光更长更明显
        double arcLen = Math.toRadians(50.0D + progress * 60.0D);

        for (int orbit = 0; orbit < orbits; orbit++) {
            double radius = 0.50D + orbit * 0.30D;    // 0.50 / 0.80 / 1.10 / 1.40
            int perTick = 3 + orbit;                   // 3 / 4 / 5 / 6
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

                // 拖尾：弧头最亮最大，越往后越小越淡
                float tail = 1.0F - (i / (float) perTick) * 0.45F;
                mc.level.addParticle(orbitDust(orbit, progress, tail), px, py, pz, vx, vy, vz);
            }

            // 彗星头：弧段起点单独放一颗高亮星芒，明确"流光从哪来"
            {
                double cos = Math.cos(head);
                double sin = Math.sin(head);
                mc.level.addParticle(
                        dust(C_STARWHITE, C_CYAN, 1.45F),
                        orbitCx + rightX * cos * radius,
                        cy + sin * radius,
                        orbitCz + rightZ * cos * radius,
                        0.0D, 0.005D, 0.0D
                );
            }
        }

        // 远星闪烁：整圈随机位置偶发一颗淡星，制造"星空不空"的感觉
        if (mc.level.random.nextInt(4) == 0) {
            double a = mc.level.random.nextDouble() * Math.PI * 2.0D;
            double radius = 0.55D + mc.level.random.nextDouble() * 0.95D;
            double cos = Math.cos(a);
            double sin = Math.sin(a);
            mc.level.addParticle(
                    dust(C_STARWHITE, C_STARBLUE, 0.62F),
                    orbitCx + rightX * cos * radius,
                    cy + sin * radius,
                    orbitCz + rightZ * cos * radius,
                    0.0D, 0.008D, 0.0D
            );
        }
    }

    /** 背后轨道的星空配色：深蓝打底、蓝紫过渡，满蓄时整环转为星芒青白 */
    private static ParticleOptions orbitDust(int orbit, float progress, float tail) {
        float scale = (0.85F + progress * 0.25F) * tail;
        if (progress >= 0.999F) {
            return dust(C_STARWHITE, C_CYAN, 1.15F * tail);
        }
        return switch (orbit) {
            case 0 -> dust(C_NIGHT, C_STARBLUE, scale);
            case 1 -> dust(C_STARBLUE, C_STARPURPLE, scale);
            case 2 -> dust(C_STARPURPLE, C_CYAN, scale);
            default -> dust(C_STARWHITE, C_STARBLUE, scale);
        };
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
     * 把 {@link #HEXAGRAM_TEX} 平铺在玩家脚下 —— 全场只画这一张。
     * <p>
     * 用 PoseStack 先移到脚下、绕 Y 轴慢慢自转、再绕 X 轴 -90° 把四边形放平，
     * 因此法阵是「真正躺在地上」的，不会像粒子那样随摄像机转动而竖起来。
     * <p>
     * 半径与蓄力进度（连续值 0~1）线性挂钩：蓄满即停止放大，不会来回跳档。
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

        // 连续蓄力进度 0~1（不是量化档位），用于丝滑放大
        float progress = ClientChargeHandler.getChargeProgress();

        // 帧间隔（秒）：按帧增量累加旋转角，转速变化时也不会跳变
        long now = System.nanoTime();
        float dt = hexagramLastNanos == 0L ? 0.016F : (now - hexagramLastNanos) / 1.0E9F;
        hexagramLastNanos = now;
        if (dt > 0.2F) dt = 0.2F;
        // 慢慢旋转：8°/秒 → 满蓄 14°/秒
        hexagramAngle = (hexagramAngle + (8.0F + progress * 6.0F) * dt) % 360.0F;

        float pt = event.getPartialTick();
        double px = Mth.lerp(pt, (float) p.xOld, (float) p.getX());
        double py = Mth.lerp(pt, (float) p.yOld, (float) p.getY());
        double pz = Mth.lerp(pt, (float) p.zOld, (float) p.getZ());

        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(px - cam.x, py + 0.05D - cam.y, pz - cam.z);

        pose.mulPose(Axis.YP.rotationDegrees(hexagramAngle));
        // 放平到地面
        pose.mulPose(Axis.XP.rotationDegrees(-90.0F));

        // 半径随蓄力从 1.6 格平滑增长到 5.0 格，蓄满即止
        float half = 1.6F + progress * 3.4F;
        // 颜色：星空蓝白 → 满蓄亮青白
        float r = 0.42F + progress * 0.33F;
        float g = 0.68F + progress * 0.24F;
        float b = 1.00F;
        float alpha = 0.35F + progress * 0.45F;

        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, HEXAGRAM_TEX);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        Matrix4f m = pose.last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
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
