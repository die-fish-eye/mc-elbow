package com.example.elbowstrike.client;

import com.example.elbowstrike.ElbowStrikeMod;
import com.example.elbowstrike.network.ElbowStrikePacket;
import com.example.elbowstrike.network.NetworkHandler;
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

        while (KeyBindings.ELBOW_STRIKE.consumeClick()) {
            NetworkHandler.CHANNEL.sendToServer(new ElbowStrikePacket());
        }
    }
}