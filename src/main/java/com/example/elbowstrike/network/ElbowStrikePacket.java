package com.example.elbowstrike.network;

import com.example.elbowstrike.ElbowStrikeHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ElbowStrikePacket {

    public ElbowStrikePacket() {}

    public ElbowStrikePacket(FriendlyByteBuf buf) {}

    public void encode(FriendlyByteBuf buf) {}

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        ServerPlayer player = context.getSender();
        if (player != null) {
            ElbowStrikeHandler.strike(player);
        }
        context.setPacketHandled(true);
    }
}