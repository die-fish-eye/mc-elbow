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
 * 蓄力条渲染。位于经验条上方。
 * <p>
 * 视觉构成：
 * <ul>
 *     <li>双层描边外框 + 内凹底色，带立体感；</li>
 *     <li>填充为「上亮下暗」的竖向渐变（橙 → 黄）；</li>
 *     <li>每 10% 一道刻度，便于读数；</li>
 *     <li>一道高光带随时间横向扫过填充区；</li>
 *     <li>两端各有一颗菱形装饰，颜色跟随填充状态；</li>
 *     <li>满蓄力：填充变为循环彩虹色，外框脉动彩虹光晕，文字同步彩虹。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID, value = Dist.CLIENT)
public final class ChargeBarOverlay {

    /** 进度条尺寸（GUI 缩放像素） */
    private static final int BAR_WIDTH = 120;
    private static final int BAR_HEIGHT = 12;

    /** 彩虹循环周期（毫秒） */
    private static final long RAINBOW_CYCLE_MS = 3000L;
    /** 高光扫过一轮的周期（毫秒） */
    private static final long SHEEN_CYCLE_MS = 1400L;

    /** 蓄力中的填充色（顶部亮色 / 底部暗色） */
    private static final int FILL_TOP = 0xFFFFF07A;   // 亮黄
    private static final int FILL_BOTTOM = 0xFFE06A00; // 深橙
    /** 刻度线颜色 */
    private static final int TICK_COLOR = 0x33FFFFFF;

    private ChargeBarOverlay() {}

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!ClientChargeHandler.isCharging()) return;

        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;

        float progress = ClientChargeHandler.getChargeProgress();
        boolean full = progress >= 1.0F;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        int x0 = (screenWidth - BAR_WIDTH) / 2;
        int y0 = screenHeight - 46;
        int x1 = x0 + BAR_WIDTH;
        int y1 = y0 + BAR_HEIGHT;

        long now = System.currentTimeMillis();
        float rainbowPhase = (now % RAINBOW_CYCLE_MS) / (float) RAINBOW_CYCLE_MS;
        float sheenPhase = (now % SHEEN_CYCLE_MS) / (float) SHEEN_CYCLE_MS;

        // ================= 1. 满蓄力：脉动彩虹光晕 =================
        if (full) {
            float pulse = (Mth.sin(rainbowPhase * (float) (Math.PI * 2.0)) + 1.0F) * 0.5F;
            for (int layer = 3; layer >= 1; layer--) {
                int alpha = (int) ((46 + pulse * 60.0F) / layer);
                int rgb = Mth.hsvToRgb((rainbowPhase + layer * 0.08F) % 1.0F, 0.75F, 1.0F);
                outline(g, x0 - layer, y0 - layer, BAR_WIDTH + layer * 2, BAR_HEIGHT + layer * 2,
                        (alpha << 24) | (rgb & 0x00FFFFFF));
            }
        }

        // ================= 2. 外框（双层：深色外沿 + 亮色内沿） =================
        g.fill(x0 - 2, y0 - 2, x1 + 2, y1 + 2, 0xF0101010);          // 外沿
        g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF6B6B6B);          // 内沿（灰亮）
        // 顶/左内沿略亮，底/右内沿略暗，制造斜面
        g.fill(x0 - 1, y0 - 1, x1 + 1, y0, 0xFFB4B4B4);
        g.fill(x0 - 1, y0 - 1, x0, y1 + 1, 0xFFA0A0A0);
        g.fill(x0 - 1, y1, x1 + 1, y1 + 1, 0xFF3C3C3C);
        g.fill(x1, y0 - 1, x1 + 1, y1 + 1, 0xFF4A4A4A);

        // ================= 3. 内底（深色渐变） =================
        for (int y = y0; y < y1; y++) {
            float t = (y - y0) / (float) BAR_HEIGHT;
            int rgb = lerpColor(0x0C0C10, 0x24242C, t);
            g.fill(x0, y, x1, y + 1, 0xFF000000 | rgb);
        }

        // ================= 4. 填充（逐列 + 竖向渐变） =================
        int fillWidth = Math.round(BAR_WIDTH * progress);
        for (int i = 0; i < fillWidth; i++) {
            float t = i / (float) BAR_WIDTH;
            int topColor = full
                    ? Mth.hsvToRgb((t + rainbowPhase) % 1.0F, 0.85F, 1.0F)
                    : lerpColor(FILL_BOTTOM, FILL_TOP, t);
            int bottomColor = full
                    ? Mth.hsvToRgb((t + rainbowPhase + 0.06F) % 1.0F, 0.95F, 0.72F)
                    : lerpColor(0xB04A00, 0xD9A000, t);

            int colX = x0 + i;
            for (int y = y0; y < y1; y++) {
                float vt = (y - y0) / (float) BAR_HEIGHT;
                int rgb = lerpColor(topColor, bottomColor, vt);
                g.fill(colX, y, colX + 1, y + 1, 0xFF000000 | rgb);
            }
        }

        // ================= 5. 高光带（扫过填充区） =================
        if (fillWidth > 6) {
            int bandWidth = 18;
            int bandX = x0 - bandWidth + (int) ((fillWidth + bandWidth * 2) * sheenPhase);
            int bx0 = Math.max(bandX, x0);
            int bx1 = Math.min(bandX + bandWidth, x0 + fillWidth);
            for (int x = bx0; x < bx1; x++) {
                float distFromCenter = Math.abs(x - (bandX + bandWidth * 0.5F)) / (bandWidth * 0.5F);
                int alpha = (int) ((1.0F - distFromCenter) * 70.0F);
                if (alpha <= 0) continue;
                g.fill(x, y0, x + 1, y1, (alpha << 24) | 0x00FFFFFF);
            }
        }

        // ================= 6. 刻度（每 10%） =================
        for (int i = 1; i < 10; i++) {
            int tx = x0 + Math.round(BAR_WIDTH * i / 10.0F);
            g.fill(tx, y0 + 1, tx + 1, y1 - 1, TICK_COLOR);
        }

        // ================= 7. 两端菱形装饰 =================
        int capColor = full
                ? Mth.hsvToRgb(rainbowPhase, 1.0F, 1.0F)
                : (progress > 0.5F ? 0xFFFFC94A : 0xFFB06A1E);
        diamond(g, x0 - 4, y0 + BAR_HEIGHT / 2, 2, 0xFF101010);
        diamond(g, x0 - 4, y0 + BAR_HEIGHT / 2, 1, capColor);
        diamond(g, x1 + 3, y0 + BAR_HEIGHT / 2, 2, 0xFF101010);
        diamond(g, x1 + 3, y0 + BAR_HEIGHT / 2, 1, capColor);

        // ================= 8. 文本 =================
        String text = full ? "蓄力已满" : ("蓄力 " + (int) (progress * 100) + "%");
        int textColor = full
                ? Mth.hsvToRgb(rainbowPhase, 0.9F, 1.0F)
                : (progress > 0.5F ? 0xFFFFE96B : 0xFFFFC04A);
        int textWidth = font.width(text);
        int tx = x0 + (BAR_WIDTH - textWidth) / 2;
        int ty = y0 + (BAR_HEIGHT - font.lineHeight) / 2 + 1;
        g.drawString(font, text, tx, ty, textColor, true);
    }

    /** 画一圈 1 像素宽的矩形描边 */
    private static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** 以 (cx, cy) 为中心画一颗半径为 r 的菱形 */
    private static void diamond(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int half = r - Math.abs(dy);
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    /** 在 0xRRGGBB 两色之间线性插值 */
    private static int lerpColor(int from, int to, float t) {
        int fr = (from >> 16) & 0xFF, fg = (from >> 8) & 0xFF, fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF, tg = (to >> 8) & 0xFF, tb = to & 0xFF;
        int r = (int) (fr + (tr - fr) * t);
        int gg = (int) (fg + (tg - fg) * t);
        int bl = (int) (fb + (tb - fb) * t);
        return (r << 16) | (gg << 8) | bl;
    }
}
