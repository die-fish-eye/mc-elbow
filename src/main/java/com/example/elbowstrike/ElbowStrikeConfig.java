package com.example.elbowstrike;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * 肘击模组的配置文件。
 * <p>
 * 生成位置：config/elbowstrike-common.toml
 * <p>
 * 游戏内可通过「Mod 列表 → Elbow Strike → Config」直接编辑。
 * <p>
 * 修改后需要重启游戏（或重进世界）才能生效。
 */
public final class ElbowStrikeConfig {

    public static final ForgeConfigSpec SPEC;
    public static final Common COMMON;

    static {
        Pair<Common, ForgeConfigSpec> pair =
                new ForgeConfigSpec.Builder().configure(Common::new);
        COMMON = pair.getLeft();
        SPEC = pair.getRight();
    }

    private ElbowStrikeConfig() {}

    public static final class Common {

        // ---- 判定 ----
        public final ForgeConfigSpec.DoubleValue range;
        public final ForgeConfigSpec.DoubleValue cone;

        // ---- 击退 ----
        public final ForgeConfigSpec.DoubleValue knockbackHorizontal;
        public final ForgeConfigSpec.DoubleValue knockbackVertical;

        // ---- 伤害与冷却 ----
        public final ForgeConfigSpec.DoubleValue damage;
        public final ForgeConfigSpec.IntValue cooldownTicks;

        // ---- 旋转 ----
        public final ForgeConfigSpec.IntValue spinDuration;
        public final ForgeConfigSpec.DoubleValue spinSpeed;

        // ---- 暴击 ----
        public final ForgeConfigSpec.BooleanValue enableCrit;
        public final ForgeConfigSpec.DoubleValue critDamageMult;
        public final ForgeConfigSpec.DoubleValue critKnockbackHorizontalMult;
        public final ForgeConfigSpec.DoubleValue critKnockbackVerticalMult;
        public final ForgeConfigSpec.DoubleValue critPitch;

        // ---- 招架 ----
        public final ForgeConfigSpec.BooleanValue enableParry;
        public final ForgeConfigSpec.IntValue parryWindowTicks;
        public final ForgeConfigSpec.BooleanValue parryCounterSpin;
        public final ForgeConfigSpec.DoubleValue parryCounterKnockbackMult;

        Common(ForgeConfigSpec.Builder b) {
            b.comment(
                    "Elbow Strike - 肘击模组配置文件",
                    "所有数值修改后需要重启游戏或重进世界才能生效。"
            ).push("elbowstrike");

            // ============================================================
            // 判定
            // ============================================================
            b.comment(
                    "【判定设置】",
                    "控制肘击能否命中目标的判定范围。"
            ).push("general");

            range = b
                    .comment(
                            "肘击判定距离（单位：格）",
                            "含义：从玩家眼睛位置到目标中心的距离上限。",
                            "数值越大，能打到的生物越远。",
                            "取值范围：1.0 ~ 10.0",
                            "默认值：3.0"
                    )
                    .defineInRange("range", 3.0D, 1.0D, 10.0D);

            cone = b
                    .comment(
                            "前方锥形判定阈值（单位：无量纲）",
                            "含义：玩家视线方向与目标方向的夹角余弦值。",
                            "数值越接近 1.0，判定范围越窄（必须正对着）。",
                            "数值越接近 0.0，判定范围越宽（侧面也能打到）。",
                            "参考：0.5 ≈ 前方 60° 锥形；0.7 ≈ 前方 45°；0.3 ≈ 前方 72°。",
                            "取值范围：0.0 ~ 1.0",
                            "默认值：0.5"
                    )
                    .defineInRange("cone", 0.5D, 0.0D, 1.0D);

            damage = b
                    .comment(
                            "基础伤害（单位：点，即半颗心）",
                            "含义：每次肘击对目标造成的伤害。",
                            "参考：1.0 = 半颗心，2.0 = 1 颗心，20.0 = 10 颗心。",
                            "取值范围：0.0 ~ 1000.0",
                            "默认值：3.0（1.5 颗心）"
                    )
                    .defineInRange("damage", 3.0D, 0.0D, 1000.0D);

            cooldownTicks = b
                    .comment(
                            "冷却时间（单位：tick，20 tick = 1 秒）",
                            "含义：两次肘击之间的最小间隔。",
                            "参考：5 tick = 0.25 秒；10 tick = 0.5 秒；20 tick = 1 秒。",
                            "取值范围：0 ~ 200",
                            "默认值：5（约 0.25 秒）"
                    )
                    .defineInRange("cooldownTicks", 5, 0, 200);

            b.pop();

            // ============================================================
            // 击退
            // ============================================================
            b.comment(
                    "【击退设置】",
                    "控制被肘击目标的击飞力度。",
                    "击退会直接覆写目标的速度，无视击退抗性。"
            ).push("knockback");

            knockbackHorizontal = b
                    .comment(
                            "水平击退力度（单位：格/tick）",
                            "含义：目标沿水平方向被推开的初始速度。",
                            "参考：1.0 = 轻微后退；1.8 = 明显击退；3.0 = 大力击飞。",
                            "取值范围：0.0 ~ 20.0",
                            "默认值：1.8"
                    )
                    .defineInRange("knockbackHorizontal", 1.8D, 0.0D, 20.0D);

            knockbackVertical = b
                    .comment(
                            "垂直击退力度（单位：格/tick）",
                            "含义：目标被向上抛起的初始速度，用于让它离地。",
                            "注意：太小可能无法离地，无法触发空中旋转。",
                            "参考：0.25 = 微微抬升；0.45 = 明显离地；0.8 = 抛得很高。",
                            "取值范围：0.0 ~ 10.0",
                            "默认值：0.6"
                    )
                    .defineInRange("knockbackVertical", 0.6D, 0.0D, 10.0D);

            b.pop();

            // ============================================================
            // 旋转
            // ============================================================
            b.comment(
                    "【空中旋转设置】",
                    "目标被击飞后，如果离地，就会持续旋转。",
                    "落地后自动停止旋转。"
            ).push("spin");

            spinDuration = b
                    .comment(
                            "旋转持续时间（单位：tick，20 tick = 1 秒）",
                            "含义：目标从被肘击到停止旋转的最长持续时间。",
                            "注意：如果目标提前落地，会立即停止旋转。",
                            "参考：20 = 1 秒；60 = 3 秒；100 = 5 秒。",
                            "取值范围：0 ~ 600",
                            "默认值：60（3 秒）"
                    )
                    .defineInRange("spinDuration", 60, 0, 600);

            spinSpeed = b
                    .comment(
                            "旋转速度（单位：度/tick）",
                            "含义：每 tick 目标旋转的角度。",
                            "参考：16 = 慢速；32 = 中速；64 = 快速；180 = 每 tick 半圈。",
                            "取值范围：0.0 ~ 360.0",
                            "默认值：50.0"
                    )
                    .defineInRange("spinSpeed", 50.0D, 0.0D, 360.0D);

            b.pop();

            // ============================================================
            // 暴击
            // ============================================================
            b.comment(
                    "【暴击设置】",
                    "暴击触发条件（满足任意一条即算暴击）：",
                    "  1. 下落攻击：玩家在空中下落时肘击。",
                    "  2. 空中追击：被肘击的目标此时已经离地。",
                    "暴击时会增加伤害与击退，并播放暴击粒子与高音调音效。"
            ).push("crit");

            enableCrit = b
                    .comment(
                            "是否启用暴击判定",
                            "true  = 启用暴击（按下方倍率加强）",
                            "false = 禁用暴击（所有肘击一律使用基础数值）",
                            "默认值：true"
                    )
                    .define("enableCrit", true);

            critDamageMult = b
                    .comment(
                            "暴击伤害倍率（乘算）",
                            "含义：暴击时最终伤害 = 基础伤害 × 此倍率。",
                            "参考：1.5 = 提升 50%；2.0 = 翻倍；3.0 = 三倍。",
                            "取值范围：1.0 ~ 10.0",
                            "默认值：1.5"
                    )
                    .defineInRange("critDamageMult", 1.5D, 1.0D, 10.0D);

            critKnockbackHorizontalMult = b
                    .comment(
                            "暴击水平击退倍率（乘算）",
                            "含义：暴击时水平击退 = 基础水平击退 × 此倍率。",
                            "取值范围：1.0 ~ 10.0",
                            "默认值：1.3"
                    )
                    .defineInRange("critKnockbackHorizontalMult", 1.3D, 1.0D, 10.0D);

            critKnockbackVerticalMult = b
                    .comment(
                            "暴击垂直击退倍率（乘算）",
                            "含义：暴击时垂直击退 = 基础垂直击退 × 此倍率。",
                            "取值范围：1.0 ~ 10.0",
                            "默认值：1.4"
                    )
                    .defineInRange("critKnockbackVerticalMult", 1.4D, 1.0D, 10.0D);

            critPitch = b
                    .comment(
                            "暴击音效音调",
                            "含义：暴击时音效播放的 pitch 值。",
                            "参考：0.5 = 低沉；1.0 = 原声；1.4 = 尖锐；2.0 = 非常尖锐。",
                            "取值范围：0.5 ~ 2.0",
                            "默认值：1.4"
                    )
                    .defineInRange("critPitch", 1.4D, 0.5D, 2.0D);

            b.pop();

            // ============================================================
            // 招架
            // ============================================================
            b.comment(
                    "【招架设置】",
                    "肘击后开启一段短窗口（默认 0.5 秒）。",
                    "窗口内被生物攻击时触发招架：完全免伤，播放音效，并对攻击者释放一次肘击。",
                    "注意：弹射物（箭、火球等）不会触发招架；一次窗口只能招架一次。"
            ).push("parry");

            enableParry = b
                    .comment(
                            "是否启用招架",
                            "true  = 启用",
                            "false = 禁用",
                            "默认值：true"
                    )
                    .define("enableParry", true);

            parryWindowTicks = b
                    .comment(
                            "招架窗口时长（单位：tick，20 tick = 1 秒）",
                            "含义：肘击后多久之内被攻击可以触发招架。",
                            "参考：10 = 0.5 秒（默认）；20 = 1 秒。",
                            "取值范围：0 ~ 100",
                            "默认值：3"
                    )
                    .defineInRange("parryWindowTicks", 3, 0, 100);

            parryCounterSpin = b
                    .comment(
                            "招架反击时是否让攻击者进入旋转状态",
                            "true  = 被招架的攻击者会被击飞并旋转",
                            "false = 只击飞，不旋转",
                            "默认值：true"
                    )
                    .define("parryCounterSpin", true);

            parryCounterKnockbackMult = b
                    .comment(
                            "招架反击击退倍率（乘算）",
                            "含义：反击肘击的击退 = 基础击退 × 此倍率。",
                            "参考：1.0 = 与普通肘击相同；1.5 = 明显更强。",
                            "取值范围：0.0 ~ 5.0",
                            "默认值：1.5"
                    )
                    .defineInRange("parryCounterKnockbackMult", 1.5D, 0.0D, 5.0D);

            b.pop();
            b.pop();
        }
    }
}