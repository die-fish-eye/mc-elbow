package com.example.elbowstrike.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * 客户端侧的玩家旋转控制。
 * 玩家朝向由客户端权威，因此必须在本地强制覆写。
 */
public final class ClientSpinHandler {

    private static final float SPIN_SPEED = 32.0F;

    private static int ticksLeft = 0;

    private ClientSpinHandler() {}

    public static void startSpin(int duration) {
        ticksLeft = duration;
    }

    /** 在 ClientTickEvent 每帧调用 */
    public static void tick() {
        if (ticksLeft <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            ticksLeft = 0;
            return;
        }

        ticksLeft--;

        // 与生物一样：离地才旋转
        if (player.onGround()) return;

        float yaw = player.getYRot() + SPIN_SPEED;

        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.yBodyRot = yaw;

        // 让插值也往同一方向走，避免视觉上被拉回
        player.yRotO = yaw - SPIN_SPEED;
        player.yHeadRotO = yaw - SPIN_SPEED;
        player.yBodyRotO = yaw - SPIN_SPEED;
    }
}