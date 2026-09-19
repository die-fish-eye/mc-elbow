package com.example.elbowstrike;

import com.example.elbowstrike.network.NetworkHandler;
import net.minecraftforge.fml.common.Mod;

@Mod(ElbowStrikeMod.MODID)
public class ElbowStrikeMod {

    public static final String MODID = "elbowstrike";

    public ElbowStrikeMod() {
        // 注册网络通道
        NetworkHandler.register();
    }
}