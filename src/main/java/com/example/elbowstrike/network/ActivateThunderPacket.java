package com.example.elbowstrike.network;

import com.example.elbowstrike.ThunderFormManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** C2S：请求激活雷霆形态 */
public class ActivateThunderPacket {

    public ActivateThunderPacket() {}

    public ActivateThunderPacket(FriendlyByteBuf buf) {}

    public void encode(FriendlyByteBuf buf) {}

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        ServerPlayer player = context.getSender();
        if (player != null) {
            ThunderFormManager.tryActivate(player);
        }
        context.setPacketHandled(true);
    }
}