package com.example.elbowstrike.client;

import com.example.elbowstrike.ElbowStrikeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 蓄力条渲染。
 * <p>
 * 注册为一个独立的 GUI 覆盖层，排在经验条（{@link VanillaGuiOverlay#EXPERIENCE_BAR}）之上。
 * 用 {@link RegisterGuiOverlaysEvent} 注册（MOD 总线、每帧只回调一次），
 * 保证渲染时机确定且不重复绘制。
 * <ul>
 *     <li>蓄力中：琥珀 → 亮金渐变填充，一道流动高光扫过；</li>
 *     <li>蓄满：填充变为循环彩虹色 + 脉动光晕。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ChargeBarOverlay {

    private static final String CHARGE_BAR_ID = "charge_bar";

    /** 进度条尺寸（GUI 缩放像素） */
    private static final int BAR_WIDTH = 132;
    private static final int BAR_HEIGHT = 9;

    /** 高光扫过 + 彩虹循环周期（毫秒） */
    private static final long RAINBOW_CYCLE_MS = 3000L;
    private static final long SHINE_CYCLE_MS = 1400L;

    private static final int CHARGE_FROM = 0xE08500; // 琥珀
    private static final int CHARGE_TO = 0xFFE082;   // 亮金

    private ChargeBarOverlay() {}

    @SubscribeEvent
    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        // 排在经验条之上，每帧只回调一次 render
        event.registerAbove(VanillaGuiOverlay.EXPERIENCE_BAR.id(), CHARGE_BAR_ID,
                ChargeBarOverlay::render);
    }

    private static void render(ForgeGui gui, GuiGraphics graphics, float partialTick,
                               int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!ClientChargeHandler.isCharging()) return;

        Font font = mc.font;
        float progress = ClientChargeHandler.getChargeProgress();
        boolean full = progress >= 1.0F;

        int x0 = (screenWidth - BAR_WIDTH) / 2;
        int y0 = screenHeight - 44; // 经验条上方

        long now = System.currentTimeMillis();
        float rainbowPhase = (now % RAINBOW_CYCLE_MS) / (float) RAINBOW_CYCLE_MS;

        // ---- 满蓄力：脉动彩虹光晕（先画，位于条的下层） ----
        if (full) {
            float pulse = (Mth.sin(rainbowPhase * (float) (Math.PI * 2.0)) + 1.0F) * 0.5F;
            int glowRgb = Mth.hsvToRgb(rainbowPhase, 0.9F, 1.0F);
            for (int layer = 1; layer <= 2; layer++) {
                int alpha = (int) (40 + pulse * 70) - layer * 20;
                if (alpha <= 0) continue;
                outline(graphics,
                        x0 - 1 - layer, y0 - 1 - layer,
                        BAR_WIDTH + 2 + layer * 2, BAR_HEIGHT + 2 + layer * 2,
                        (alpha << 24) | glowRgb);
            }
        }

        // ---- 外投影 + 边框 + 内底 ----
        graphics.fill(x0 - 2, y0 - 2, x0 + BAR_WIDTH + 2, y0 + BAR_HEIGHT + 2, 0x40000000);
        graphics.fill(x0 - 1, y0 - 1, x0 + BAR_WIDTH + 1, y0 + BAR_HEIGHT + 1, 0xFF0B0B10);
        graphics.fill(x0, y0, x0 + BAR_WIDTH, y0 + BAR_HEIGHT, 0xFF212128);

        // ---- 填充：逐像素竖条（渐变 + 流动高光） ----
        int fillWidth = Math.round(BAR_WIDTH * progress);
        int fy0 = y0 + 1;
        int fy1 = y0 + BAR_HEIGHT - 1;
        float shineCenter = ((now % SHINE_CYCLE_MS) / (float) SHINE_CYCLE_MS)
                * (BAR_WIDTH + 60.0F) - 30.0F;

        for (int i = 0; i < fillWidth; i++) {
            float t = i / (float) BAR_WIDTH;
            int rgb = full
                    ? Mth.hsvToRgb((t + rainbowPhase) % 1.0F, 1.0F, 1.0F)
                    : lerpColor(CHARGE_FROM, CHARGE_TO, t);

            float d = Math.abs(i - shineCenter);
            if (d < 16.0F) {
                rgb = blendToward(rgb, 0xFFFFFF, (1.0F - d / 16.0F) * 0.5F);
            }
            graphics.fill(x0 + i, fy0, x0 + i + 1, fy1, 0xFF000000 | rgb);
        }

        // ---- 顶部高光 ----
        if (fillWidth > 2) {
            graphics.fill(x0 + 1, fy0, x0 + fillWidth - 1, fy0 + 1, 0x59FFFFFF);
        }

        // ---- 文字（条上方居中，黄色 / 满蓄力彩虹） ----
        String text = full ? "蓄力已满" : ("蓄力 " + (int) (progress * 100) + "%");
        int textColor = full ? Mth.hsvToRgb(rainbowPhase, 1.0F, 1.0F) : 0xFFFFE082;
        int tw = font.width(text);
        graphics.drawString(font, text, (screenWidth - tw) / 2, y0 - 11, textColor, true);
    }

    /** 画一圈 1 像素宽的矩形描边 */
    private static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** 在 0xRRGGBB 两色之间线性插值 */
    private static int lerpColor(int from, int to, float t) {
        int fr = (from >> 16) & 0xFF, fg = (from >> 8) & 0xFF, fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF, tg = (to >> 8) & 0xFF, tb = to & 0xFF;
        return (((int) (fr + (tr - fr) * t)) << 16)
                | (((int) (fg + (tg - fg) * t)) << 8)
                | ((int) (fb + (tb - fb) * t));
    }

    /** 把颜色朝目标色混合 k 比例 */
    private static int blendToward(int rgb, int target, float k) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int tr = (target >> 16) & 0xFF, tg = (target >> 8) & 0xFF, tb = target & 0xFF;
        return (((int) (r + (tr - r) * k)) << 16)
                | (((int) (g + (tg - g) * k)) << 8)
                | ((int) (b + (tb - b) * k));
    }
}
