package com.example.elbowstrike;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 管理「被肘飞后在空中旋转」的逻辑（仅普通生物）
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID)
public final class SpinManager {

    /** 每 tick 旋转的角度（度） */
    private static final float SPIN_SPEED = 60.0F;
    /** 默认持续时间（tick） */
    private static final int DEFAULT_DURATION = 120;

    /** 剩余旋转 tick 数 */
    private static final Map<UUID, Integer> TICKS_LEFT = new HashMap<>();

    private SpinManager() {}

    public static void startSpin(LivingEntity entity) {
        startSpin(entity, DEFAULT_DURATION);
    }

    public static void startSpin(LivingEntity entity, int duration) {
        if (entity.level().isClientSide()) return;
        // 玩家由客户端权威，走 ClientSpinHandler
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

            // 只有离地时才旋转
            if (living.onGround()) continue;

            float newYaw = living.getYRot() + SPIN_SPEED;

            living.setYRot(newYaw);
            living.setYHeadRot(newYaw);
            living.yBodyRot = newYaw;

            // 同步旧值，让客户端插值方向正确
            living.yRotO = newYaw - SPIN_SPEED;
            living.yHeadRotO = newYaw - SPIN_SPEED;
            living.yBodyRotO = newYaw - SPIN_SPEED;
        }
    }

    /** 在所有维度里查找指定 UUID 的生物 */
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