package com.example.elbowstrike;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 管理「被肘飞后在空中旋转」的逻辑（仅普通生物）。
 * 关键点：旋转期间对 Mob 禁用 AI，否则 AI 会在每 tick 覆写 yRot/yHeadRot。
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID)
public final class SpinManager {

    /** 每 tick 旋转的角度（度） */
    private static final float SPIN_SPEED = 32.0F;
    /** 默认持续时间（tick） */
    private static final int DEFAULT_DURATION = 60;

    private static final class SpinData {
        int ticksLeft;
        boolean hadAiDisabled;   // 记录原始 AI 状态，结束后恢复

        SpinData(int ticksLeft, boolean hadAiDisabled) {
            this.ticksLeft = ticksLeft;
            this.hadAiDisabled = hadAiDisabled;
        }
    }

    private static final Map<UUID, SpinData> SPINNING = new HashMap<>();

    private SpinManager() {}

    public static void startSpin(LivingEntity entity) {
        startSpin(entity, DEFAULT_DURATION);
    }

    public static void startSpin(LivingEntity entity, int duration) {
        if (entity.level().isClientSide()) return;

        boolean wasAiDisabled = false;
        if (entity instanceof Mob mob) {
            wasAiDisabled = mob.isNoAi();
            mob.setNoAi(true);   // 关键：临时禁用 AI
        }

        SPINNING.put(entity.getUUID(), new SpinData(duration, wasAiDisabled));
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (SPINNING.isEmpty()) return;

        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;

        // 玩家由客户端处理
        if (entity instanceof ServerPlayer) return;

        SpinData data = SPINNING.get(entity.getUUID());
        if (data == null) return;

        if (!entity.isAlive()) {
            stopSpin(entity, data);
            return;
        }

        if (data.ticksLeft <= 0) {
            stopSpin(entity, data);
            return;
        }
        data.ticksLeft--;

        // 只在离地时强制旋转
        if (entity.onGround()) return;

        float yaw = entity.getYRot() + SPIN_SPEED;

        entity.setYRot(yaw);
        entity.setYHeadRot(yaw);
        entity.yBodyRot = yaw;

        entity.yRotO = yaw - SPIN_SPEED;
        entity.yHeadRotO = yaw - SPIN_SPEED;
        entity.yBodyRotO = yaw - SPIN_SPEED;
    }

    private static void stopSpin(LivingEntity entity, SpinData data) {
        SPINNING.remove(entity.getUUID());
        if (entity instanceof Mob mob && !data.hadAiDisabled) {
            mob.setNoAi(false);  // 恢复 AI
        }
    }
}