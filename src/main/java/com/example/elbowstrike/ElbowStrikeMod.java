package com.example.elbowstrike;

import com.example.elbowstrike.network.NetworkHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
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

        // 注册配置文件：config/elbowstrike-common.toml
        ModLoadingContext.get().registerConfig(
                ModConfig.Type.COMMON,
                ElbowStrikeConfig.SPEC
        );

        // 让 SpinManager 的事件监听器注册到 Forge 事件总线
        MinecraftForge.EVENT_BUS.register(SpinManager.class);
    }
}