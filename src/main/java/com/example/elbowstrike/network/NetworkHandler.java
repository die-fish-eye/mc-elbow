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
        CHANNEL.messageBuilder(ElbowStrikePacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ElbowStrikePacket::encode)
                .decoder(ElbowStrikePacket::new)
                .consumerMainThread(ElbowStrikePacket::handle)
                .add();
    }
}