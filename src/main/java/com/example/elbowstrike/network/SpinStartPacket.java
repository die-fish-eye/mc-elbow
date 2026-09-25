package com.example.elbowstrike.network;

import com.example.elbowstrike.client.ClientSpinHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 服务端 -> 客户端：通知本地玩家开始旋转 */
public class SpinStartPacket {

    private final int duration;

    public SpinStartPacket(int duration) {
        this.duration = duration;
    }

    public SpinStartPacket(FriendlyByteBuf buf) {
        this.duration = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(duration);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientSpinHandler.startSpin(duration)
        ));
        context.setPacketHandled(true);
    }
}