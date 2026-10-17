package com.example.elbowstrike.client;

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
     * 拦截左键 / 右键。
     * <p>
     * 按住 {@link KeyBindings#ELBOW_MODE} 时：
     * <ul>
     *     <li>左键（攻击）→ 肘击，并吞掉原版攻击</li>
     *     <li>右键（使用物品）→ 万象天引，并吞掉原版使用</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!KeyBindings.ELBOW_MODE.isDown()) return;

        if (event.isAttack()) {
            event.setCanceled(true);
            NetworkHandler.CHANNEL.sendToServer(new ElbowStrikePacket());
        } else if (event.isUseItem()) {
            event.setCanceled(true);
            NetworkHandler.CHANNEL.sendToServer(new UniversalPullPacket());
        }
    }

    /**
     * 拦截鼠标按键，处理中键。
     * <p>
     * 按住 {@link KeyBindings#ELBOW_MODE} 时：中键按下 → 雷霆形态，并吞掉原版选取方块。
     */
    @SubscribeEvent
    public static void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!KeyBindings.ELBOW_MODE.isDown()) return;

        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE
                && event.getAction() == GLFW.GLFW_PRESS) {
            event.setCanceled(true);
            NetworkHandler.CHANNEL.sendToServer(new ActivateThunderPacket());
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // 玩家空中旋转
        ClientSpinHandler.tick();
    }
}
