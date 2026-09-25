package com.example.elbowstrike.client;

import com.example.elbowstrike.ElbowStrikeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

public final class ClientSpinHandler {

    private static int ticksLeft = 0;

    private ClientSpinHandler() {}

    public static void startSpin(int duration) {
        ticksLeft = duration;
    }

    public static void tick() {
        if (ticksLeft <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            ticksLeft = 0;
            return;
        }

        ticksLeft--;

        if (player.onGround()) return;

        float spinSpeed = ElbowStrikeConfig.COMMON.spinSpeed.get().floatValue();
        float yaw = player.getYRot() + spinSpeed;

        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.yBodyRot = yaw;

        player.yRotO = yaw - spinSpeed;
        player.yHeadRotO = yaw - spinSpeed;
        player.yBodyRotO = yaw - spinSpeed;
    }
}