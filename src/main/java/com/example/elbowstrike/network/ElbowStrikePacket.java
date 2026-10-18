package com.example.elbowstrike.network;

import com.example.elbowstrike.ElbowStrikeHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** C2S：请求肘击，携带客户端蓄力 tick 数与是否突进 */
public class ElbowStrikePacket {

    private final int chargeTicks;
    /** 释放时是否按住前进键（W），true 则触发突进 */
    private final boolean dash;

    public ElbowStrikePacket(int chargeTicks, boolean dash) {
        this.chargeTicks = chargeTicks;
        this.dash = dash;
    }

    public ElbowStrikePacket(FriendlyByteBuf buf) {
        this.chargeTicks = buf.readVarInt();
        this.dash = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(chargeTicks);
        buf.writeBoolean(dash);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        ServerPlayer player = context.getSender();
        if (player != null) {
            ElbowStrikeHandler.strike(player, chargeTicks, dash);
        }
        context.setPacketHandled(true);
    }
}
