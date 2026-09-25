package com.example.elbowstrike;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 强制旋转被肘击的生物。
 *
 * 关键：把生物的 LookControl 临时换成 FrozenLookControl，
 * 阻止 serverAiStep() 里的 LookControl.tick() 覆盖 yRot / yHeadRot。
 * 旋转结束后恢复原来的 LookControl。
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID)
public final class SpinManager {

    /** 每 tick 旋转的角度（度） */
    private static final float SPIN_SPEED = 32.0F;
    /** 默认持续时间（tick） */
    private static final int DEFAULT_DURATION = 60;

    private static final class SpinData {
        int ticksLeft;
        LookControl originalLookControl;
        boolean swapped;

        SpinData(int ticksLeft) {
            this.ticksLeft = ticksLeft;
            this.swapped = false;
        }
    }

    private static final Map<UUID, SpinData> SPINNING = new HashMap<>();

    private SpinManager() {}

    public static void startSpin(LivingEntity entity) {
        startSpin(entity, DEFAULT_DURATION);
    }

    public static void startSpin(LivingEntity entity, int duration) {
        if (entity.level().isClientSide()) return;
        if (entity instanceof ServerPlayer) return; // 玩家走客户端路径

        // 如果已经在旋转，直接重置计时
        SpinData existing = SPINNING.get(entity.getUUID());
        if (existing != null) {
            existing.ticksLeft = duration;
            return;
        }
        SPINNING.put(entity.getUUID(), new SpinData(duration));
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (SPINNING.isEmpty()) return;

        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;
        if (entity instanceof ServerPlayer) return;

        SpinData data = SPINNING.get(entity.getUUID());
        if (data == null) return;

        // 结束条件：时间到 / 死亡
        if (data.ticksLeft <= 0 || !entity.isAlive()) {
            stopSpin(entity, data);
            return;
        }

        // 首次进入：替换 LookControl（仅 Mob 有）
        if (!data.swapped && entity instanceof Mob mob) {
            data.originalLookControl = mob.getLookControl();
            mob.lookControl = new FrozenLookControl(mob);
            data.swapped = true;
        }

        data.ticksLeft--;

        // 落地后不再旋转（但不停止计时，方便连续肘击）
        if (entity.onGround()) return;

        // 强制旋转
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

        // 恢复原来的 LookControl
        if (data.swapped && entity instanceof Mob mob && data.originalLookControl != null) {
            mob.lookControl = data.originalLookControl;
        }
    }
}