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
 * 蓄力条渲染。
 * <p>
 * 位于经验条上方的一个进度条：
 * <ul>
 *     <li>蓄力中：橙 → 黄渐变填充，百分比居中显示；</li>
 *     <li>蓄满：填充变成寰宇支配之剑风格的循环彩虹色，并带脉动光晕。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID, value = Dist.CLIENT)
public final class ChargeBarOverlay {

    /** 进度条尺寸（GUI 缩放像素） */
    private static final int BAR_WIDTH = 120;
    private static final int BAR_HEIGHT = 11;

    /** 彩虹循环周期（毫秒） */
    private static final long RAINBOW_CYCLE_MS = 3000L;

    /** 蓄力中和满蓄力的填充色 */
    private static final int CHARGE_FROM = 0xFF7A00; // 深橙
    private static final int CHARGE_TO = 0xFFF35C;   // 亮黄

    private ChargeBarOverlay() {}

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!ClientChargeHandler.isCharging()) return;

        GuiGraphics graphics = event.getGuiGraphics();
        Font font = mc.font;

        float progress = ClientChargeHandler.getChargeProgress();
        boolean full = progress >= 1.0F;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        int x0 = (screenWidth - BAR_WIDTH) / 2;
        int y0 = screenHeight - 46; // 经验条上方

        long now = System.currentTimeMillis();
        float timePhase = (now % RAINBOW_CYCLE_MS) / (float) RAINBOW_CYCLE_MS;

        // ---- 满蓄力：脉动彩虹光晕 ----
        if (full) {
            float pulse = (Mth.sin(timePhase * (float) (Math.PI * 2.0)) + 1.0F) * 0.5F; // 0~1
            int baseAlpha = 50 + (int) (pulse * 90.0F);
            int glowRgb = Mth.hsvToRgb(timePhase, 0.85F, 1.0F);
            for (int layer = 1; layer <= 3; layer++) {
                int alpha = Math.max(baseAlpha - layer * 14, 0);
                outline(graphics,
                        x0 - 1 - layer, y0 - 1 - layer,
                        BAR_WIDTH + 2 + layer * 2, BAR_HEIGHT + 2 + layer * 2,
                        (alpha << 24) | (glowRgb & 0x00FFFFFF));
            }
        }

        // ---- 边框 + 内底 ----
        graphics.fill(x0 - 1, y0 - 1, x0 + BAR_WIDTH + 1, y0 + BAR_HEIGHT + 1, 0xFF101010);
        graphics.fill(x0, y0, x0 + BAR_WIDTH, y0 + BAR_HEIGHT, 0xFF2B2B2B);

        // ---- 填充：逐像素竖条，实现渐变 / 彩虹 ----
        int fillWidth = Math.round(BAR_WIDTH * progress);
        int fy1 = y0 + 1;
        int fy2 = y0 + BAR_HEIGHT - 1;
        for (int i = 0; i < fillWidth; i++) {
            float t = i / (float) BAR_WIDTH;
            int rgb = full
                    ? Mth.hsvToRgb((t + timePhase) % 1.0F, 1.0F, 1.0F)
                    : lerpColor(CHARGE_FROM, CHARGE_TO, t);
            graphics.fill(x0 + i, fy1, x0 + i + 1, fy2, 0xFF000000 | rgb);
        }

        // ---- 顶部高光 ----
        if (fillWidth > 2) {
            graphics.fill(x0 + 1, fy1, x0 + fillWidth - 1, fy1 + 1, 0x66FFFFFF);
        }

        // ---- 文本（居中显示在条内） ----
        String text = full ? "蓄力已满" : ("蓄力 " + (int) (progress * 100) + "%");
        int textColor = full
                ? Mth.hsvToRgb(timePhase, 1.0F, 1.0F)
                : 0xFFFFF35C;
        int textWidth = font.width(text);
        int tx = x0 + (BAR_WIDTH - textWidth) / 2;
        int ty = y0 + (BAR_HEIGHT - font.lineHeight) / 2 + 1;
        graphics.drawString(font, text, tx, ty, textColor, true);
    }

    /** 画一圈 1 像素宽的矩形描边 */
    private static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);             // 上
        g.fill(x, y + h - 1, x + w, y + h, color);     // 下
        g.fill(x, y, x + 1, y + h, color);             // 左
        g.fill(x + w - 1, y, x + w, y + h, color);     // 右
    }

    /** 在 0xRRGGBB 两色之间线性插值 */
    private static int lerpColor(int from, int to, float t) {
        int fr = (from >> 16) & 0xFF, fg = (from >> 8) & 0xFF, fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF, tg = (to >> 8) & 0xFF, tb = to & 0xFF;
        int r = (int) (fr + (tr - fr) * t);
        int g = (int) (fg + (tg - fg) * t);
        int bl = (int) (fb + (tb - fb) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
