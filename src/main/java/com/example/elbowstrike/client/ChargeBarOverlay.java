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
 * 通过 {@link RenderGuiOverlayEvent.Post} 在 HUD 绘制阶段渲染蓄力文字，
 * 位置在经验条上方：
 * <ul>
 *     <li>蓄力中：黄色（0xFFFFAA00）显示百分比</li>
 *     <li>蓄满：寰宇支配之剑风格的彩虹色（色相随时间循环）</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID, value = Dist.CLIENT)
public final class ChargeBarOverlay {

    /** 蓄满时彩虹色一个完整循环的时长（毫秒） */
    private static final long RAINBOW_CYCLE_MS = 3000L;

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

        int percent = (int) (progress * 100);
        String text = "蓄力 " + percent + "%";

        int color = full ? rainbowColor() : 0xFFFFAA00; // 黄色

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int textWidth = font.width(text);
        int x = (screenWidth - textWidth) / 2;
        int y = screenHeight - 42; // 经验条上方

        graphics.drawString(font, text, x, y, color, true);
    }

    /**
     * 寰宇支配之剑风格彩虹色：色相随时间循环。
     * <p>
     * 利用 {@link Mth#hsvToRgb} 把 0~1 的色相映射为 RGB，
     * 每 {@link #RAINBOW_CYCLE_MS} 毫秒完成一次色相循环。
     */
    private static int rainbowColor() {
        float hue = (System.currentTimeMillis() % RAINBOW_CYCLE_MS) / (float) RAINBOW_CYCLE_MS;
        return Mth.hsvToRgb(hue, 1.0F, 1.0F);
    }
}
