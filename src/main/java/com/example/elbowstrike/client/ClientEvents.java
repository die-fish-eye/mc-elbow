package com.example.elbowstrike.client;

import com.example.elbowstrike.ElbowStrikeConfig;
import com.example.elbowstrike.ElbowStrikeMod;
import com.example.elbowstrike.network.ActivateThunderPacket;
import com.example.elbowstrike.network.ElbowStrikePacket;
import com.example.elbowstrike.network.NetworkHandler;
import com.example.elbowstrike.network.UniversalPullPacket;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {}

    /**
     * 拦截右键（使用物品）：按住 V 时 → 万象天引，吞掉原版使用。
     * <p>
     * 左键不再走这里，改由 {@link #onMouseButtonPre} 处理蓄力按下/松开。
     */
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!KeyBindings.ELBOW_MODE.isDown()) return;

        if (event.isUseItem()) {
            event.setCanceled(true);
            NetworkHandler.CHANNEL.sendToServer(new UniversalPullPacket());
        }
    }

    /**
     * 拦截鼠标按键：
     * <ul>
     *     <li>左键按下 → 开始蓄力；左键松开 → 释放肘击（携带蓄力值）</li>
     *     <li>中键按下 → 雷霆形态</li>
     * </ul>
     * 均需按住 V 才生效，并吞掉原版对应动作。
     */
    @SubscribeEvent
    public static void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        boolean modeHeld = KeyBindings.ELBOW_MODE.isDown();

        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (!modeHeld) return;
            event.setCanceled(true);
            if (event.getAction() == GLFW.GLFW_PRESS) {
                ClientChargeHandler.startCharge();
            } else if (event.getAction() == GLFW.GLFW_RELEASE) {
                if (ClientChargeHandler.isCharging()) {
                    int chargeTicks = ClientChargeHandler.consumeChargeTicks();
                    // 按住前进键（W）蓄力释放 → 突进
                    boolean dash = mc.options.keyUp.isDown();
                    NetworkHandler.CHANNEL.sendToServer(new ElbowStrikePacket(chargeTicks, dash));
                    // 与服务端判定保持一致：只有 2 档及以上（蓄力超过 20%）才会真正突进
                    int maxTicks = ElbowStrikeConfig.COMMON.chargeMaxTicks.get();
                    if (dash && ElbowStrikeConfig.COMMON.enableChargeDash.get()
                            && chargeTicks * 5 >= maxTicks) {
                        ElbowClientEffects.startDashEffect();
                    }
                }
            }
        } else if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            if (!modeHeld) return;
            if (event.getAction() == GLFW.GLFW_PRESS) {
                event.setCanceled(true);
                NetworkHandler.CHANNEL.sendToServer(new ActivateThunderPacket());
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // 若蓄力中但 V 已松开 / 玩家已离开世界，取消蓄力（不释放肘击）
        if (ClientChargeHandler.isCharging()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !KeyBindings.ELBOW_MODE.isDown()) {
                ClientChargeHandler.cancelCharge();
            }
        }

        // 玩家空中旋转
        ClientSpinHandler.tick();
    }
}
