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

        // ---- 雷霆形态 ----
        public final ForgeConfigSpec.BooleanValue enableThunderForm;
        public final ForgeConfigSpec.IntValue thunderFormCooldown;
        public final ForgeConfigSpec.DoubleValue thunderFormFloatSpeed;
        public final ForgeConfigSpec.IntValue thunderFormFloatDuration;
        public final ForgeConfigSpec.DoubleValue thunderFormFallSpeed;
        public final ForgeConfigSpec.DoubleValue thunderFormLightningRadius;
        public final ForgeConfigSpec.IntValue thunderFormLightningInterval;
        public final ForgeConfigSpec.DoubleValue thunderFormLightningDamage;
        public final ForgeConfigSpec.DoubleValue thunderFormImpactRadius;
        public final ForgeConfigSpec.DoubleValue thunderFormImpactDamage;
        public final ForgeConfigSpec.DoubleValue thunderFormImpactKnockback;
        public final ForgeConfigSpec.BooleanValue thunderFormAllowFallDamage;

        // ---- 万象天引 ----
        public final ForgeConfigSpec.BooleanValue enableUniversalPull;
        public final ForgeConfigSpec.IntValue universalPullCooldown;
        public final ForgeConfigSpec.DoubleValue universalPullRange;
        public final ForgeConfigSpec.DoubleValue universalPullCone;
        public final ForgeConfigSpec.IntValue universalPullDuration;
        public final ForgeConfigSpec.DoubleValue universalPullSpeed;
        public final ForgeConfigSpec.DoubleValue universalPullStopDistance;

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
                            "取值范围：1.0 ~ 10.0",
                            "默认值：3.0"
                    )
                    .defineInRange("range", 3.0D, 1.0D, 10.0D);

            cone = b
                    .comment(
                            "前方锥形判定阈值（无量纲，视线与目标方向夹角的余弦值）",
                            "0.5 ≈ 前方 60°；0.7 ≈ 前方 45°；0.3 ≈ 前方 72°",
                            "取值范围：0.0 ~ 1.0",
                            "默认值：0.5"
                    )
                    .defineInRange("cone", 0.5D, 0.0D, 1.0D);

            damage = b
                    .comment(
                            "基础伤害（单位：点，即半颗心）",
                            "参考：2.0 = 1 颗心；20.0 = 10 颗心。",
                            "取值范围：0.0 ~ 1000.0",
                            "默认值：3.0"
                    )
                    .defineInRange("damage", 3.0D, 0.0D, 1000.0D);

            cooldownTicks = b
                    .comment(
                            "冷却时间（单位：tick，20 tick = 1 秒）",
                            "取值范围：0 ~ 200",
                            "默认值：5"
                    )
                    .defineInRange("cooldownTicks", 5, 0, 200);

            b.pop();

            // ============================================================
            // 击退
            // ============================================================
            b.comment(
                    "【击退设置】",
                    "控制被肘击目标的击飞力度，会直接覆写速度，无视击退抗性。"
            ).push("knockback");

            knockbackHorizontal = b
                    .comment(
                            "水平击退力度（单位：格/tick）",
                            "取值范围：0.0 ~ 20.0",
                            "默认值：1.8"
                    )
                    .defineInRange("knockbackHorizontal", 1.8D, 0.0D, 20.0D);

            knockbackVertical = b
                    .comment(
                            "垂直击退力度（单位：格/tick）",
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
                    "目标被击飞后，如果离地，就会持续旋转，落地后停止。"
            ).push("spin");

            spinDuration = b
                    .comment(
                            "旋转持续时间（单位：tick）",
                            "取值范围：0 ~ 600",
                            "默认值：60"
                    )
                    .defineInRange("spinDuration", 60, 0, 600);

            spinSpeed = b
                    .comment(
                            "旋转速度（单位：度/tick）",
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
                    "暴击触发条件（任意一条即算）：",
                    "  1. 下落攻击：玩家在空中下落时肘击。",
                    "  2. 空中追击：被肘击的目标此时已经离地。",
                    "暴击时会增加伤害与击退，并播放暴击粒子与高音调音效。"
            ).push("crit");

            enableCrit = b
                    .comment("是否启用暴击判定，默认值：true")
                    .define("enableCrit", true);

            critDamageMult = b
                    .comment("暴击伤害倍率（乘算），取值范围：1.0 ~ 10.0，默认值：1.5")
                    .defineInRange("critDamageMult", 1.5D, 1.0D, 10.0D);

            critKnockbackHorizontalMult = b
                    .comment("暴击水平击退倍率（乘算），取值范围：1.0 ~ 10.0，默认值：1.3")
                    .defineInRange("critKnockbackHorizontalMult", 1.3D, 1.0D, 10.0D);

            critKnockbackVerticalMult = b
                    .comment("暴击垂直击退倍率（乘算），取值范围：1.0 ~ 10.0，默认值：1.4")
                    .defineInRange("critKnockbackVerticalMult", 1.4D, 1.0D, 10.0D);

            critPitch = b
                    .comment("暴击音效音调，取值范围：0.5 ~ 2.0，默认值：1.4")
                    .defineInRange("critPitch", 1.4D, 0.5D, 2.0D);

            b.pop();

            // ============================================================
            // 招架
            // ============================================================
            b.comment(
                    "【招架设置】",
                    "肘击命中后开启一段短窗口。",
                    "窗口内被近距离生物攻击时触发招架：完全免伤，播放音效，并对攻击者释放一次肘击。",
                    "注意：弹射物（箭、火球）不会触发；一次窗口只能招架一次。"
            ).push("parry");

            enableParry = b
                    .comment("是否启用招架，默认值：true")
                    .define("enableParry", true);

            parryWindowTicks = b
                    .comment(
                            "招架窗口时长（单位：tick，20 tick = 1 秒）",
                            "参考：5 = 0.25 秒（默认）；10 = 0.5 秒。",
                            "取值范围：0 ~ 100",
                            "默认值：5"
                    )
                    .defineInRange("parryWindowTicks", 5, 0, 100);

            parryCounterSpin = b
                    .comment("招架反击时是否让攻击者进入旋转状态，默认值：true")
                    .define("parryCounterSpin", true);

            parryCounterKnockbackMult = b
                    .comment("招架反击击退倍率（乘算），取值范围：0.0 ~ 5.0，默认值：1.5")
                    .defineInRange("parryCounterKnockbackMult", 1.5D, 0.0D, 5.0D);

            b.pop();

            // ============================================================
            // 雷霆形态
            // ============================================================
            b.comment(
                    "【雷霆形态设置】",
                    "按 K 激活：玩家进入缓慢漂浮上升，持续一段时间后迅速坠落。",
                    "漂浮和坠落全程持续在周围召唤雷电。",
                    "落地时产生雷暴，对范围内生物造成伤害与击退。"
            ).push("thunder_form");

            enableThunderForm = b
                    .comment("是否启用雷霆形态，默认值：true")
                    .define("enableThunderForm", true);

            thunderFormCooldown = b
                    .comment(
                            "冷却时间（单位：tick，20 tick = 1 秒）",
                            "取值范围：0 ~ 24000",
                            "默认值：600（30 秒）"
                    )
                    .defineInRange("thunderFormCooldown", 600, 0, 24000);

            thunderFormFloatSpeed = b
                    .comment(
                            "漂浮上升速度（单位：格/tick，正值向上）",
                            "通过 levitation 效果实现，实际速度会被量化到 0.05 的整数倍。",
                            "参考：0.05 = 极慢；0.15 = 缓慢上升（默认）；0.25 = 较快。",
                            "取值范围：0.02 ~ 0.5",
                            "默认值：0.15"
                    )
                    .defineInRange("thunderFormFloatSpeed", 0.15D, 0.02D, 0.5D);

            thunderFormFloatDuration = b
                    .comment(
                            "漂浮持续时间（单位：tick，20 tick = 1 秒）",
                            "参考：100 = 5 秒（默认）；60 = 3 秒；200 = 10 秒。",
                            "取值范围：10 ~ 2400",
                            "默认值：100"
                    )
                    .defineInRange("thunderFormFloatDuration", 100, 10, 2400);

            thunderFormFallSpeed = b
                    .comment(
                            "坠落初速度（单位：格/tick）",
                            "含义：漂浮结束时给玩家一次向下的初速度，之后由重力自然加速。",
                            "参考：1.0 = 稍慢；2.0 = 迅速（默认）；3.0 = 极快。",
                            "取值范围：0.5 ~ 5.0",
                            "默认值：2.0"
                    )
                    .defineInRange("thunderFormFallSpeed", 2.0D, 0.5D, 5.0D);

            thunderFormLightningRadius = b
                    .comment(
                            "雷电生成范围（单位：格）",
                            "含义：以玩家为中心，在此半径内召唤雷电。",
                            "取值范围：5.0 ~ 50.0",
                            "默认值：20.0"
                    )
                    .defineInRange("thunderFormLightningRadius", 20.0D, 5.0D, 50.0D);

            thunderFormLightningInterval = b
                    .comment(
                            "雷电生成间隔（单位：tick）",
                            "参考：5 = 0.25 秒（默认）；10 = 0.5 秒；3 = 极密集。",
                            "取值范围：1 ~ 100",
                            "默认值：5"
                    )
                    .defineInRange("thunderFormLightningInterval", 5, 1, 100);

            thunderFormLightningDamage = b
                    .comment(
                            "每道雷电的伤害（单位：点）",
                            "取值范围：0.0 ~ 100.0",
                            "默认值：6.0（3 颗心）"
                    )
                    .defineInRange("thunderFormLightningDamage", 6.0D, 0.0D, 100.0D);

            thunderFormImpactRadius = b
                    .comment(
                            "落地雷暴半径（单位：格）",
                            "取值范围：1.0 ~ 30.0",
                            "默认值：15.0"
                    )
                    .defineInRange("thunderFormImpactRadius", 15.0D, 1.0D, 30.0D);

            thunderFormImpactDamage = b
                    .comment(
                            "落地雷暴伤害（单位：点）",
                            "取值范围：0.0 ~ 200.0",
                            "默认值：12.0（6 颗心）"
                    )
                    .defineInRange("thunderFormImpactDamage", 12.0D, 0.0D, 200.0D);

            thunderFormImpactKnockback = b
                    .comment(
                            "落地雷暴的击退强度（水平 + 向上）",
                            "取值范围：0.0 ~ 5.0",
                            "默认值：1.5"
                    )
                    .defineInRange("thunderFormImpactKnockback", 1.5D, 0.0D, 5.0D);

            thunderFormAllowFallDamage = b
                    .comment(
                            "【彩蛋】是否允许雷霆形态期间受到摔落伤害",
                            "false = 屏蔽摔落伤害（默认，安全落地）",
                            "true  = 不再屏蔽，从高处落下会受到坠落伤害",
                            "小心。",
                            "默认值：false"
                    )
                    .define("thunderFormAllowFallDamage", false);

            b.pop();

            // ============================================================
            // 万象天引
            // ============================================================
            b.comment(
                    "【万象天引设置】"
            ).push("universal_pull");

            enableUniversalPull = b
                    .comment("是否启用万象天引，默认值：true")
                    .define("enableUniversalPull", true);

            universalPullCooldown = b
                    .comment(
                            "冷却时间（单位：tick，20 tick = 1 秒）",
                            "取值范围：0 ~ 1200",
                            "默认值：60（3 秒）"
                    )
                    .defineInRange("universalPullCooldown", 60, 0, 1200);

            universalPullRange = b
                    .comment(
                            "作用距离（单位：格）",
                            "取值范围：1.0 ~ 50.0",
                            "默认值：15.0"
                    )
                    .defineInRange("universalPullRange", 15.0D, 1.0D, 50.0D);

            universalPullCone = b
                    .comment(
                            "前方锥形判定阈值（无量纲）",
                            "0.7 ≈ 前方 45°；0.5 ≈ 前方 60°；0.0 ≈ 360° 全方位。",
                            "取值范围：-1.0 ~ 1.0",
                            "默认值：0.5（全方位，无需面朝目标）"
                    )
                    .defineInRange("universalPullCone", 0.5D, -1.0D, 1.0D);

            universalPullDuration = b
                    .comment(
                            "拉拽持续时间（单位：tick）",
                            "含义：最多持续多久，目标就到达玩家面前或提前结束。",
                            "取值范围：1 ~ 200",
                            "默认值：20（1 秒）"
                    )
                    .defineInRange("universalPullDuration", 20, 1, 200);

            universalPullSpeed = b
                    .comment(
                            "拉拽速度（单位：格/tick）",
                            "含义：每 tick 给目标施加的朝向玩家的速度大小。",
                            "参考：0.5 = 缓慢拉近；1.0 = 明显拉拽（默认）；2.0 = 猛拉。",
                            "取值范围：0.1 ~ 5.0",
                            "默认值：1.0"
                    )
                    .defineInRange("universalPullSpeed", 1.0D, 0.1D, 5.0D);

            universalPullStopDistance = b
                    .comment(
                            "停止距离（单位：格）",
                            "含义：目标被拉到玩家面前多远停下。",
                            "取值太小会让目标挤进玩家身体，太大则拉不到面前。",
                            "取值范围：1.0 ~ 5.0",
                            "默认值：2.0"
                    )
                    .defineInRange("universalPullStopDistance", 2.0D, 1.0D, 5.0D);

            b.pop();
            b.pop();
        }
    }
}