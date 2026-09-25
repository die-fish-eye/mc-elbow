package com.example.elbowstrike.network;

import com.example.elbowstrike.ElbowStrikeMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class NetworkHandler {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ElbowStrikeMod.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private NetworkHandler() {}

    public static void register() {
        int id = 0;

        // C2S：请求肘击
        CHANNEL.messageBuilder(ElbowStrikePacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ElbowStrikePacket::encode)
                .decoder(ElbowStrikePacket::new)
                .consumerMainThread(ElbowStrikePacket::handle)
                .add();

        // S2C：通知玩家开始旋转
        CHANNEL.messageBuilder(SpinStartPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SpinStartPacket::encode)
                .decoder(SpinStartPacket::new)
                .consumerMainThread(SpinStartPacket::handle)
                .add();

        // C2S：请求激活雷霆形态
        CHANNEL.messageBuilder(ActivateThunderPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ActivateThunderPacket::encode)
                .decoder(ActivateThunderPacket::new)
                .consumerMainThread(ActivateThunderPacket::handle)
                .add();
    }
}