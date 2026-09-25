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
 * 2. 自己维护累积角度 currentYaw，不依赖 living.getYRot()，避免 AI 覆盖
 * 3. 用 ClientboundMoveEntityPacket.PosRot 同步位置+旋转，避免贴图卡在原位
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID)
public final class SpinManager {

    private static final float SPIN_SPEED = 32.0F;
    private static final int DEFAULT_DURATION = 60;

    private static final class SpinData {
        int ticksLeft;
        float currentYaw;
        boolean initialized;

        SpinData(int ticksLeft) {
            this.ticksLeft = ticksLeft;
            this.initialized = false;
        }
    }

    private static final Map<UUID, SpinData> SPINNING = new HashMap<>();

    private SpinManager() {}

    public static void startSpin(LivingEntity entity) {
        startSpin(entity, DEFAULT_DURATION);
    }

    public static void startSpin(LivingEntity entity, int duration) {
        if (entity.level().isClientSide()) return;
        if (entity instanceof ServerPlayer) return;
        SPINNING.put(entity.getUUID(), new SpinData(duration));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (SPINNING.isEmpty()) return;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        Iterator<Map.Entry<UUID, SpinData>> it = SPINNING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, SpinData> entry = it.next();
            SpinData data = entry.getValue();

            if (data.ticksLeft <= 0) {
                it.remove();
                continue;
            }

            LivingEntity living = findEntity(server, entry.getKey());
            if (living == null || !living.isAlive()) {
                it.remove();
                continue;
            }

            data.ticksLeft--;

            if (living.onGround()) continue;

            if (!data.initialized) {
                data.currentYaw = living.getYRot();
                data.initialized = true;
            }

            data.currentYaw += SPIN_SPEED;
            float newYaw = data.currentYaw;

            // 写入服务端实体状态
            living.setYRot(newYaw);
            living.setYHeadRot(newYaw);
            living.yBodyRot = newYaw;
            living.yRotO = newYaw - SPIN_SPEED;
            living.yHeadRotO = newYaw - SPIN_SPEED;
            living.yBodyRotO = newYaw - SPIN_SPEED;

            // 用 PosRot 包同步位置+旋转，避免贴图卡在原位
            byte yawByte = (byte) (newYaw * 256.0F / 360.0F);
            byte pitchByte = (byte) (living.getXRot() * 256.0F / 360.0F);

            ClientboundMoveEntityPacket.PosRot posRotPacket =
                    new ClientboundMoveEntityPacket.PosRot(
                            living.getId(),
                            (short) 0,  // 位置增量设为0，让客户端自己插值
                            (short) 0,
                            (short) 0,
                            yawByte,
                            pitchByte,
                            living.onGround()
                    );

            PacketDistributor.TRACKING_ENTITY.with(() -> living).send(posRotPacket);
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