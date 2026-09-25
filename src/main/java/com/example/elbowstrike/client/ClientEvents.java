package com.example.elbowstrike.client;

import com.example.elbowstrike.ElbowStrikeMod;
import com.example.elbowstrike.network.ActivateThunderPacket;
import com.example.elbowstrike.network.ElbowStrikePacket;
import com.example.elbowstrike.network.NetworkHandler;
import com.example.elbowstrike.network.UniversalPullPacket;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // 按键 -> 发包
        while (KeyBindings.ELBOW_STRIKE.consumeClick()) {
            NetworkHandler.CHANNEL.sendToServer(new ElbowStrikePacket());
        }

        while (KeyBindings.THUNDER_FORM.consumeClick()) {
            NetworkHandler.CHANNEL.sendToServer(new ActivateThunderPacket());
        }

        while (KeyBindings.UNIVERSAL_PULL.consumeClick()) {
            NetworkHandler.CHANNEL.sendToServer(new UniversalPullPacket());
        }

        // 玩家空中旋转
        ClientSpinHandler.tick();
    }
}