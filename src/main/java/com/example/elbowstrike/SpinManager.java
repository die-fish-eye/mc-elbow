package com.example.elbowstrike;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 管理「被肘飞后在空中旋转」的逻辑（仅普通生物，玩家由客户端处理）。
 * 使用 LivingTickEvent，确保在 AI 覆盖朝向之后、发包之前修改 yRot。
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
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (SPINNING.isEmpty()) return;

        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;

        // 玩家由客户端权威控制，旋转由 ClientSpinHandler 处理，这里跳过
        if (entity instanceof ServerPlayer) {
            SPINNING.remove(entity.getUUID());
            return;
        }

        SpinData data = SPINNING.get(entity.getUUID());
        if (data == null) return;

        // 清理无效状态
        if (!entity.isAlive() || !entity.level().dimension().equals(data.dimension)) {
            SPINNING.remove(entity.getUUID());
            return;
        }

        if (data.ticksLeft <= 0) {
            SPINNING.remove(entity.getUUID());
            return;
        }
        data.ticksLeft--;

        // 核心条件：只有离地（被肘飞）时才强制旋转
        if (entity.onGround()) return;

        float yaw = entity.getYRot() + SPIN_SPEED;

        entity.setYRot(yaw);
        entity.setYHeadRot(yaw);
        entity.yBodyRot = yaw;

        entity.yRotO = yaw - SPIN_SPEED;
        entity.yHeadRotO = yaw - SPIN_SPEED;
        entity.yBodyRotO = yaw - SPIN_SPEED;
    }
}