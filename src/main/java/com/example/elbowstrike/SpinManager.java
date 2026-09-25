package com.example.elbowstrike;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 管理「被肘飞后在空中旋转」的逻辑（仅普通生物）。
 * 关键：LivingTickEvent 在 LivingEntity.tick() 的最末尾触发（aiStep 之后），
 * 所以在这里覆写 yRot/yHeadRot/yBodyRot 不会被 AI 覆盖。
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID)
public final class SpinManager {

    /** 每 tick 旋转的角度（度） */
    private static final float SPIN_SPEED = 45.0F;
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
        TICKS_LEFT.put(entity.getUUID(), duration);
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (TICKS_LEFT.isEmpty()) return;

        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;

        // 玩家由客户端权威，跳过
        if (entity instanceof ServerPlayer) return;

        Integer left = TICKS_LEFT.get(entity.getUUID());
        if (left == null) return;

        if (left <= 0 || !entity.isAlive()) {
            TICKS_LEFT.remove(entity.getUUID());
            return;
        }
        TICKS_LEFT.put(entity.getUUID(), left - 1);

        // 只在离地时强制旋转
        if (entity.onGround()) return;

        float yaw = entity.getYRot() + SPIN_SPEED;

        entity.setYRot(yaw);
        entity.setYHeadRot(yaw);
        entity.yBodyRot = yaw;

        // 关键：同步旧值，否则客户端插值方向会被 AI 上一 tick 的值拉回去
        entity.yRotO = yaw - SPIN_SPEED;
        entity.yHeadRotO = yaw - SPIN_SPEED;
        entity.yBodyRotO = yaw - SPIN_SPEED;
    }
}