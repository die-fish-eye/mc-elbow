package com.example.elbowstrike.network;

import com.example.elbowstrike.UniversalPullHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** C2S：请求万象天引 */
public class UniversalPullPacket {

    public UniversalPullPacket() {}

    public UniversalPullPacket(FriendlyByteBuf buf) {}

    public void encode(FriendlyByteBuf buf) {}

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        ServerPlayer player = context.getSender();
        if (player != null) {
            UniversalPullHandler.activate(player);
        }
        context.setPacketHandled(true);
    }
}