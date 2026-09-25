package com.example.elbowstrike;

import com.example.elbowstrike.network.NetworkHandler;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(ElbowStrikeMod.MODID)
public class ElbowStrikeMod {

    public static final String MODID = "elbowstrike";

    public ElbowStrikeMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 注册音效
        ModSounds.register(modBus);

        // 注册网络通道
        NetworkHandler.register();
    }
}