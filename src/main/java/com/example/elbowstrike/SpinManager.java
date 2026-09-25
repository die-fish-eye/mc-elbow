package com.example.elbowstrike;

import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 强制旋转被肘击的生物。
 *
 * 关键点：
 * 1. 用 ServerTickEvent END，在所有实体 tick 完之后执行（AI 已经跑完了）
 * 2. 改完 yRot/yHeadRot/yBodyRot 后，主动发送 ClientboundMoveEntityPacket.Rot
 *    强制客户端同步旋转，因为 ServerEntity 的被动同步会被下一 tick 的 AI 抵消
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID)
public final class SpinManager {

    /** 每 tick 旋转的角度（度） */
    private static final float SPIN_SPEED = 32.0F;
    /** 默认持续时间（tick） */
    private static final int DEFAULT_DURATION = 60;

    /** 剩余旋转 tick 数 */
    private static final Map<UUID, Integer> TICKS_LEFT = new HashMap<>();

    private SpinManager() {}

    public static void startSpin(LivingEntity entity) {
        startSpin(entity, DEFAULT_DURATION);
    }

    public static void startSpin(LivingEntity entity, int duration) {
        if (entity.level().isClientSide()) return;
        // 玩家朝向由客户端权威，走 ClientSpinHandler
        if (entity instanceof ServerPlayer) return;
        TICKS_LEFT.put(entity.getUUID(), duration);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (TICKS_LEFT.isEmpty()) return;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        Iterator<Map.Entry<UUID, Integer>> it = TICKS_LEFT.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            int left = entry.getValue();

            if (left <= 0) {
                it.remove();
                continue;
            }

            LivingEntity living = findEntity(server, entry.getKey());
            if (living == null) {
                it.remove();
                continue;
            }

            entry.setValue(left - 1);

            // 落地后停止旋转
            if (living.onGround()) continue;

            // ---- 1. 计算新朝向 ----
            float newYaw = living.getYRot() + SPIN_SPEED;
            float currentPitch = living.getXRot();

            // ---- 2. 写入服务端实体状态 ----
            living.setYRot(newYaw);
            living.setYHeadRot(newYaw);
            living.yBodyRot = newYaw;
            living.yRotO = newYaw - SPIN_SPEED;
            living.yHeadRotO = newYaw - SPIN_SPEED;
            living.yBodyRotO = newYaw - SPIN_SPEED;

            // ---- 3. 主动发旋转包，强制客户端同步 ----
            // 角度转字节：yaw * 256 / 360
            byte yawByte = (byte) (newYaw * 256.0F / 360.0F);
            byte pitchByte = (byte) (currentPitch * 256.0F / 360.0F);

            ClientboundMoveEntityPacket.Rot rotPacket = new ClientboundMoveEntityPacket.Rot(
                    living.getId(),
                    yawByte,
                    pitchByte,
                    living.onGround()
            );

            // 发给所有正在追踪该实体的玩家（包括旁观者视角）
            PacketDistributor.TRACKING_ENTITY.with(() -> living).send(rotPacket);
        }
    }

    private static LivingEntity findEntity(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(uuid);
            if (e instanceof LivingEntity living
                    && living.isAlive()
                    && !(living instanceof ServerPlayer)) {
                return living;
            }
        }
        return null;
    }
}