package com.example.elbowstrike;

import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
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
 * 核心思路：
 * 1. 自己维护累积角度 currentYaw，不依赖 living.getYRot()，
 *    这样 AI 每 tick 把 yRot 改回去也不会影响我们的旋转进度。
 * 2. 在 ServerTickEvent END 阶段（所有实体 tick + AI 都跑完了）
 *    强制设置 yRot / yHeadRot / yBodyRot。
 * 3. 同时发两个包：
 *    - ClientboundTeleportEntityPacket：同步位置 + yRot，避免贴图停在原地
 *    - ClientboundMoveEntityPacket.Rot：同步 yHeadRot，让头部也一起转
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID)
public final class SpinManager {

    /** 每 tick 旋转的角度（度） */
    private static final float SPIN_SPEED = 32.0F;
    /** 默认持续时间（tick） */
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
        // 玩家由客户端权威处理，走 ClientSpinHandler
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

            // 落地后停止旋转
            if (living.onGround()) continue;

            // 首次进入旋转时，记录初始角度
            if (!data.initialized) {
                data.currentYaw = living.getYRot();
                data.initialized = true;
            }

            // 累积我们自己的旋转角度，不受 AI 影响
            data.currentYaw += SPIN_SPEED;
            float newYaw = data.currentYaw;

            // ---- 写入服务端实体状态 ----
            living.setYRot(newYaw);
            living.setYHeadRot(newYaw);
            living.yBodyRot = newYaw;

            living.yRotO = newYaw - SPIN_SPEED;
            living.yHeadRotO = newYaw - SPIN_SPEED;
            living.yBodyRotO = newYaw - SPIN_SPEED;

            // ---- 主动发包给客户端 ----

            // 1) 传送包：同步位置 + yRot，避免客户端贴图停在原地
            ClientboundTeleportEntityPacket tpPacket =
                    new ClientboundTeleportEntityPacket(living);
            PacketDistributor.TRACKING_ENTITY.with(() -> living).send(tpPacket);

            // 2) 旋转包：同步 yHeadRot，让头一起转
            byte yawByte = (byte) (newYaw * 256.0F / 360.0F);
            byte pitchByte = (byte) (living.getXRot() * 256.0F / 360.0F);
            ClientboundMoveEntityPacket.Rot rotPacket =
                    new ClientboundMoveEntityPacket.Rot(
                            living.getId(),
                            yawByte,
                            pitchByte,
                            living.onGround()
                    );
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