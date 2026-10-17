package com.example.elbowstrike.network;

import com.example.elbowstrike.ElbowStrikeHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** C2S：请求肘击，携带客户端蓄力 tick 数 */
public class ElbowStrikePacket {

    private final int chargeTicks;

    public ElbowStrikePacket(int chargeTicks) {
        this.chargeTicks = chargeTicks;
    }

    public ElbowStrikePacket(FriendlyByteBuf buf) {
        this.chargeTicks = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(chargeTicks);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        ServerPlayer player = context.getSender();
        if (player != null) {
            ElbowStrikeHandler.strike(player, chargeTicks);
        }
        context.setPacketHandled(true);
    }
}
