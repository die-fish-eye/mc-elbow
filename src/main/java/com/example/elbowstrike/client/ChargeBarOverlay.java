package com.example.elbowstrike.client;

import com.example.elbowstrike.ElbowStrikeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 星星蓄力条。
 * <p>
 * 共 {@value #STAR_COUNT} 颗空心星星，居中排列，尺寸由中间向两侧递减。
 * 蓄力时从最外侧两颗开始、逐颗向中间点亮：
 * <ul>
 *     <li>未点亮：只有描边（空心）；</li>
 *     <li>点亮：描边 + 内部星空渐变填充（深蓝 → 靛紫 → 星蓝）；</li>
 *     <li>点亮到最中间那颗（第 5 档 / 满蓄力）：整排变为循环彩虹色。</li>
 * </ul>
 * 注意：{@link RenderGuiOverlayEvent.Post} 会为每一个原版 HUD 元素各触发一次，
 * 这里用时间戳去重，保证每帧只绘制一次，避免重复绘制导致的卡顿。
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID, value = Dist.CLIENT)
public final class ChargeBarOverlay {

    /** 星星总数（奇数，便于有唯一中心） */
    private static final int STAR_COUNT = 11;
    /** 中心星外半径（像素），向两侧每颗递减 1 */
    private static final double CENTER_RADIUS = 8.0D;
    /** 五角星内外半径比 */
    private static final double INNER_RATIO = 0.45D;

    /** 彩虹循环周期（毫秒） */
    private static final long RAINBOW_CYCLE_MS = 2800L;

    /** 同一帧内只绘制一次的时间阈值（纳秒） */
    private static final long FRAME_GUARD_NANOS = 300_000L;
    private static long lastRenderNanos = 0L;

    /** 未点亮星星的描边色 */
    private static final int HOLLOW_COLOR = 0xFF7E8AB8;
    /** 点亮星星的描边色（星空模式下） */
    private static final int FILLED_EDGE = 0xFFB9C7FF;

    /** 星空填充的 3 段渐变色（上 → 中 → 下） */
    private static final int SKY_TOP = 0x101A4A;
    private static final int SKY_MID = 0x34318F;
    private static final int SKY_BOTTOM = 0x2D6BA8;

    private ChargeBarOverlay() {}

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        // 一帧内该事件会触发多次（每个 HUD 元素一次），只让第一次真正绘制
        long nowNs = System.nanoTime();
        if (nowNs - lastRenderNanos < FRAME_GUARD_NANOS) return;
        lastRenderNanos = nowNs;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!ClientChargeHandler.isCharging()) return;

        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;

        int tier = ClientChargeHandler.getChargeTier();
        if (tier <= 0) return;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        // ---- 星星布局：以屏幕中心为轴，尺寸从中间向两侧递减 ----
        double[] radius = new double[STAR_COUNT];
        double[] offsetX = new double[STAR_COUNT];
        int mid = STAR_COUNT / 2;
        for (int i = 0; i < STAR_COUNT; i++) {
            radius[i] = CENTER_RADIUS - Math.abs(i - mid);
        }
        // 由中心向两侧累加间距，保证星星互不重叠
        for (int k = 1; k <= mid; k++) {
            double step = radius[mid + k - 1] + radius[mid + k] + 2.0D;
            offsetX[mid + k] = offsetX[mid + k - 1] + step;
            offsetX[mid - k] = -offsetX[mid + k];
        }

        double cx = screenWidth / 2.0D;
        double cy = screenHeight - 44.0D;

        long now = System.currentTimeMillis();
        float rainbowPhase = (now % RAINBOW_CYCLE_MS) / (float) RAINBOW_CYCLE_MS;

        // ---- 档位 → 点亮进度：5 档对应 11 颗星（0 档时不显示）----
        float chargeRatio = tier / (float) ClientChargeHandler.CHARGE_TIERS;
        double litStars = chargeRatio * STAR_COUNT;
        int fullyLit = (int) Math.floor(litStars);
        float partial = (float) (litStars - fullyLit);
        boolean rainbow = tier >= ClientChargeHandler.CHARGE_TIERS;

        // 点亮顺序：从两侧向中间，即按「离中心距离」由远到近
        int[] order = buildLightingOrder(mid);

        for (int step = 0; step < STAR_COUNT; step++) {
            int idx = order[step];
            double sx = cx + offsetX[idx];
            double r = radius[idx];

            float fill; // 该星的点亮程度 0~1
            if (step < fullyLit) {
                fill = 1.0F;
            } else if (step == fullyLit) {
                fill = partial;
            } else {
                fill = 0.0F;
            }

            if (fill <= 0.0F) {
                // 空心：先投影一层暗色描边，再画亮色描边，制造厚度
                starOutline(g, sx + 1.0D, cy + 1.0D, r, 0x55000000);
                starOutline(g, sx, cy, r, HOLLOW_COLOR);
            } else {
                int[] colors = starColors(idx, rainbow, rainbowPhase);
                starFill(g, sx, cy, r, colors[0], colors[1], colors[2], fill);
                starOutline(g, sx, cy, r, rainbow ? colors[3] : FILLED_EDGE);
                starSparkles(g, sx, cy, r);
            }
        }

        // ---- 档位文字 ----
        String text = rainbow ? "蓄力已满" : ("蓄力 " + tier + " / " + ClientChargeHandler.CHARGE_TIERS);
        int textColor = rainbow
                ? Mth.hsvToRgb(rainbowPhase, 0.9F, 1.0F)
                : 0xFF9FB0E0;
        int tw = font.width(text);
        g.drawString(font, text, (int) (cx - tw / 2.0D), (int) cy + 11, textColor, true);
    }

    /**
     * 点亮顺序：交替从最外侧向中心推进，保证左右对称。
     * 例如 11 颗：0,10,1,9,2,8,3,7,4,6,5（0 为最左）。
     */
    private static int[] buildLightingOrder(int mid) {
        int[] order = new int[STAR_COUNT];
        int i = 0;
        for (int k = mid; k >= 1; k--) {
            order[i++] = mid - k;
            order[i++] = mid + k;
        }
        order[i] = mid;
        return order;
    }

    /** 计算某颗星使用的颜色：{上, 中, 下, 描边} */
    private static int[] starColors(int index, boolean rainbow, float phase) {
        if (rainbow) {
            float base = (index / (float) STAR_COUNT + phase) % 1.0F;
            return new int[]{
                    Mth.hsvToRgb(base, 0.75F, 1.0F),
                    Mth.hsvToRgb((base + 0.08F) % 1.0F, 0.85F, 0.95F),
                    Mth.hsvToRgb((base + 0.16F) % 1.0F, 0.9F, 0.8F),
                    Mth.hsvToRgb((base + 0.24F) % 1.0F, 1.0F, 1.0F)
            };
        }
        // 每颗星略微偏移色相，整排看起来像一片星空
        float shift = (index - STAR_COUNT / 2) * 0.012F;
        return new int[]{
                shiftColor(SKY_TOP, shift, 0.0F),
                shiftColor(SKY_MID, shift, 0.0F),
                shiftColor(SKY_BOTTOM, shift, 0.0F)
        };
    }

    // ================================================================
    // 五角星绘制
    // ================================================================

    /** 生成五角星的 10 个顶点（外/内交替，从正上方开始顺时针） */
    private static double[][] starVertices(double cx, double cy, double outerR) {
        double innerR = outerR * INNER_RATIO;
        double[][] pts = new double[10][2];
        for (int i = 0; i < 10; i++) {
            double angle = -Math.PI / 2.0D + i * Math.PI / 5.0D;
            double r = (i % 2 == 0) ? outerR : innerR;
            pts[i][0] = cx + Math.cos(angle) * r;
            pts[i][1] = cy + Math.sin(angle) * r;
        }
        return pts;
    }

    /** 空心星星：只描边 */
    private static void starOutline(GuiGraphics g, double cx, double cy, double outerR, int color) {
        double[][] pts = starVertices(cx, cy, outerR);
        for (int i = 0; i < 10; i++) {
            double[] a = pts[i];
            double[] b = pts[(i + 1) % 10];
            drawLine(g, (int) Math.round(a[0]), (int) Math.round(a[1]),
                    (int) Math.round(b[0]), (int) Math.round(b[1]), color);
        }
    }

    /**
     * 实心星星：按扫描线填充，并做竖向渐变。
     *
     * @param partial 点亮比例 0~1，从底部向上填充
     */
    private static void starFill(GuiGraphics g, double cx, double cy, double outerR,
                                 int colorTop, int colorMid, int colorBottom, float partial) {
        double[][] pts = starVertices(cx, cy, outerR);
        int yTop = (int) Math.floor(cy - outerR);
        int yBottom = (int) Math.ceil(cy + outerR);
        int rows = yBottom - yTop;

        int filledRows = Math.round(partial * rows);
        if (filledRows <= 0) return;
        int startY = yBottom - filledRows; // 从底部开始点亮

        for (int y = startY; y < yBottom; y++) {
            double sampleY = y + 0.5D;
            double[] xs = new double[10];
            int n = 0;
            for (int i = 0; i < 10; i++) {
                double[] a = pts[i];
                double[] b = pts[(i + 1) % 10];
                if ((a[1] <= sampleY) != (b[1] <= sampleY)) {
                    double t = (sampleY - a[1]) / (b[1] - a[1]);
                    xs[n++] = a[0] + t * (b[0] - a[0]);
                }
            }
            if (n < 2) continue;
            // 交点排序（n 很小，直接插入排序）
            for (int i = 1; i < n; i++) {
                double v = xs[i];
                int j = i - 1;
                while (j >= 0 && xs[j] > v) {
                    xs[j + 1] = xs[j];
                    j--;
                }
                xs[j + 1] = v;
            }

            float t = (y - yTop) / (float) Math.max(rows - 1, 1);
            int rgb = t < 0.5F
                    ? lerpColor(colorTop, colorMid, t / 0.5F)
                    : lerpColor(colorMid, colorBottom, (t - 0.5F) / 0.5F);

            for (int i = 0; i + 1 < n; i += 2) {
                int x0 = (int) Math.round(xs[i]);
                int x1 = (int) Math.round(xs[i + 1]);
                if (x1 > x0) {
                    g.fill(x0, y, x1, y + 1, 0xFF000000 | rgb);
                }
            }
        }
    }

    /** 点亮后，在星星内部点两颗白色小星点，强化「星空」感 */
    private static void starSparkles(GuiGraphics g, double cx, double cy, double outerR) {
        int s1x = (int) Math.round(cx - outerR * 0.28D);
        int s1y = (int) Math.round(cy - outerR * 0.30D);
        int s2x = (int) Math.round(cx + outerR * 0.30D);
        int s2y = (int) Math.round(cy + outerR * 0.10D);
        g.fill(s1x, s1y, s1x + 1, s1y + 1, 0xE6FFFFFF);
        g.fill(s2x, s2y, s2x + 1, s2y + 1, 0xB3FFFFFF);
    }

    /** Bresenham 直线 */
    private static void drawLine(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            g.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    /** 在 0xRRGGBB 两色之间线性插值 */
    private static int lerpColor(int from, int to, float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        int fr = (from >> 16) & 0xFF, fg = (from >> 8) & 0xFF, fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF, tg = (to >> 8) & 0xFF, tb = to & 0xFF;
        int r = (int) (fr + (tr - fr) * t);
        int gg = (int) (fg + (tg - fg) * t);
        int bl = (int) (fb + (tb - fb) * t);
        return (r << 16) | (gg << 8) | bl;
    }

    /** 轻微偏移色相（用于让每颗星的星空色略有差异） */
    private static int shiftColor(int rgb, float hueShift, float valueShift) {
        float[] hsv = new float[3];
        rgbToHsv(rgb, hsv);
        hsv[0] = (hsv[0] + hueShift + 1.0F) % 1.0F;
        hsv[2] = Mth.clamp(hsv[2] + valueShift, 0.0F, 1.0F);
        return Mth.hsvToRgb(hsv[0], hsv[1], hsv[2]);
    }

    /** RGB(0xRRGGBB) → HSV（h/s/v 均为 0~1） */
    private static void rgbToHsv(int rgb, float[] out) {
        float r = ((rgb >> 16) & 0xFF) / 255.0F;
        float g = ((rgb >> 8) & 0xFF) / 255.0F;
        float b = (rgb & 0xFF) / 255.0F;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;

        float h = 0.0F;
        if (delta > 1.0E-5F) {
            if (max == r) {
                h = ((g - b) / delta) % 6.0F;
            } else if (max == g) {
                h = (b - r) / delta + 2.0F;
            } else {
                h = (r - g) / delta + 4.0F;
            }
            h /= 6.0F;
            if (h < 0.0F) h += 1.0F;
        }
        out[0] = h;
        out[1] = max <= 1.0E-5F ? 0.0F : delta / max;
        out[2] = max;
    }
}
