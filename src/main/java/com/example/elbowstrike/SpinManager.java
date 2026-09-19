package com.example.elbowstrike;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 管理「被肘飞后在空中旋转」的逻辑。
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID)
public final class SpinManager {

    /** 每 tick 旋转的角度（度） */
    private static final float SPIN_SPEED = 32.0F;
    /** 默认持续时间（tick） */
    private static final int DEFAULT_DURATION = 60;

    private static final class SpinData {
        final ResourceKey<Level> dimension;
        int ticksLeft;

        SpinData(ResourceKey<Level> dimension, int ticksLeft) {
            this.dimension = dimension;
            this.ticksLeft = ticksLeft;
        }
    }

    private static final Map<UUID, SpinData> SPINNING = new HashMap<>();

    private SpinManager() {}

    public static void startSpin(LivingEntity entity) {
        startSpin(entity, DEFAULT_DURATION);
    }

    public static void startSpin(LivingEntity entity, int duration) {
        SPINNING.put(entity.getUUID(),
                new SpinData(entity.level().dimension(), duration));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SPINNING.isEmpty()) return;

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
            data.ticksLeft--;

            ServerLevel level = server.getLevel(data.dimension);
            if (level == null) {
                it.remove();
                continue;
            }

            Entity entity = level.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                it.remove();
                continue;
            }

            // 核心条件：不在地面上（被肘飞到空中）才旋转
            if (living.onGround()) continue;

            float yaw = living.getYRot() + SPIN_SPEED;
            living.setYRot(yaw);
            living.setYHeadRot(yaw);
            living.yBodyRot = yaw;
        }
    }
}