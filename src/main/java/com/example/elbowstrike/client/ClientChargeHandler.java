package com.example.elbowstrike.client;

import com.example.elbowstrike.ElbowStrikeConfig;

/**
 * 客户端蓄力状态管理。
 * <p>
 * 按住 V + 左键时调用 {@link #startCharge()} 开始计时，
 * 松开左键时调用 {@link #consumeChargeTicks()} 取出蓄力 tick 数并停止。
 * 渲染层通过 {@link #isCharging()} / {@link #getChargeProgress()} 显示蓄力条。
 */
public final class ClientChargeHandler {

    /** 蓄力档位总数（档位 1 ~ 5，5 = 满蓄力） */
    public static final int CHARGE_TIERS = 5;

    private static boolean charging = false;
    private static long chargeStartMs = 0L;

    private ClientChargeHandler() {}

    /** 开始蓄力（左键按下且 V 按住时调用） */
    public static void startCharge() {
        charging = true;
        chargeStartMs = System.currentTimeMillis();
    }

    /** 取消蓄力（例如松开 V），不释放肘击 */
    public static void cancelCharge() {
        charging = false;
        chargeStartMs = 0L;
    }

    public static boolean isCharging() {
        return charging;
    }

    /**
     * 当前蓄力进度（0.0 ~ 1.0），满蓄力为 1.0。
     * 供渲染层使用，平滑增长。
     */
    public static float getChargeProgress() {
        if (!charging) return 0.0F;
        int maxTicks = ElbowStrikeConfig.COMMON.chargeMaxTicks.get();
        if (maxTicks <= 0) return 1.0F;
        long elapsedMs = System.currentTimeMillis() - chargeStartMs;
        float maxMs = maxTicks * 50.0F; // 1 tick = 50 ms
        return Math.min(elapsedMs / maxMs, 1.0F);
    }

    /**
     * 当前蓄力档位（1 ~ {@value #CHARGE_TIERS}），未蓄力时为 0。
     * 蓄力值被量化到 5 档，渲染层与伤害计算共用同一套档位。
     */
    public static int getChargeTier() {
        if (!charging) return 0;
        return tierOf(getChargeProgress());
    }

    /** 把 0~1 的连续进度映射为 1~5 档 */
    public static int tierOf(float progress) {
        int tier = (int) (progress * CHARGE_TIERS) + 1;
        return Math.max(1, Math.min(tier, CHARGE_TIERS));
    }

    /**
     * 取出当前蓄力 tick 数（0 ~ chargeMaxTicks），并停止蓄力。
     * 在松开左键释放肘击时调用。
     */
    public static int consumeChargeTicks() {
        float progress = getChargeProgress();
        int ticks = (int) (progress * ElbowStrikeConfig.COMMON.chargeMaxTicks.get());
        charging = false;
        chargeStartMs = 0L;
        return ticks;
    }
}
