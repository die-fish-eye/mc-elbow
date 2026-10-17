package com.example.elbowstrike.client;

import com.example.elbowstrike.ElbowStrikeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiLayersEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.client.gui.overlay.VanillaGuiLayers;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 蓄力条渲染。
 * <p>
 * 通过 {@link RegisterGuiLayersEvent} 注册一个独立的 GUI 层，
 * 渲染在经验条（{@link VanillaGuiLayers#EXPERIENCE_BAR}）之上：
 * <ul>
 *     <li>蓄力中：黄色（0xFFFFAA00）显示百分比</li>
 *     <li>蓄满：寰宇支配之剑风格的彩虹色（色相随时间循环）</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ChargeBarOverlay {

    /** 本模组蓄力条覆盖层的 ID */
    private static final ResourceLocation CHARGE_BAR =
            new ResourceLocation(ElbowStrikeMod.MODID, "charge_bar");

    /** 蓄满时彩虹色一个完整循环的时长（毫秒） */
    private static final long RAINBOW_CYCLE_MS = 3000L;

    private ChargeBarOverlay() {}

    @SubscribeEvent
    public static void onRegisterLayers(RegisterGuiLayersEvent event) {
        // 把蓄力条注册在经验条之上，确保后绘制（不会被经验条挡住）
        event.registerAbove(VanillaGuiLayers.EXPERIENCE_BAR, CHARGE_BAR, CHARGE_BAR_RENDERER);
    }

    /** GUI 层渲染函数：render(ForgeGui, GuiGraphics, partialTick, screenWidth, screenHeight) */
    private static final IGuiOverlay CHARGE_BAR_RENDERER = (forgeGui, graphics, partialTick, screenWidth, screenHeight) -> {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!ClientChargeHandler.isCharging()) return;

        float progress = ClientChargeHandler.getChargeProgress();
        boolean full = progress >= 1.0F;

        Font font = mc.font;
        int percent = (int) (progress * 100);
        String text = "蓄力 " + percent + "%";

        int color = full ? rainbowColor() : 0xFFFFAA00; // 黄色

        int textWidth = font.width(text);
        int x = (screenWidth - textWidth) / 2;
        int y = screenHeight - 42; // 经验条上方

        graphics.drawString(font, text, x, y, color, true);
    };

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
