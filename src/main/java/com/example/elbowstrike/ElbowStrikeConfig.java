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
        public final ForgeConfigSpec.DoubleValue thunderFormUpSpeed;
        public final ForgeConfigSpec.DoubleValue thunderFormLightningRadius;
        public final ForgeConfigSpec.IntValue thunderFormLightningInterval;
        public final ForgeConfigSpec.DoubleValue thunderFormLightningDamage;
        public final ForgeConfigSpec.DoubleValue thunderFormImpactRadius;
        public final ForgeConfigSpec.DoubleValue thunderFormImpactDamage;
        public final ForgeConfigSpec.DoubleValue thunderFormImpactKnockback;
        public final ForgeConfigSpec.BooleanValue thunderFormAllowFallDamage;

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
                    "按 K 激活：给玩家一个向上的初速度（伪悬停），",
                    "从激活瞬间开始就在周围不断召唤雷电，",
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

            thunderFormUpSpeed = b
                    .comment(
                            "向上初速度（单位：格/tick）",
                            "含义：激活瞬间给玩家向上的初速度，之后由重力自然减速到顶点，再自然下落。",
                            "参考：1.0 ≈ 跳 4 格；1.5 ≈ 跳 8~10 格（默认）；2.0 ≈ 跳 15 格；3.0 ≈ 跳 30 格。",
                            "注意：太大会导致隧道穿过方块。",
                            "取值范围：0.3 ~ 5.0",
                            "默认值：1.5"
                    )
                    .defineInRange("thunderFormUpSpeed", 1.5D, 0.3D, 5.0D);

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
                            "默认值：10.0"
                    )
                    .defineInRange("thunderFormImpactRadius", 10.0D, 1.0D, 30.0D);

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

            // ─────────────────────────────────────────────
            // 彩蛋：允许摔落伤害
            thunderFormAllowFallDamage = b
                    .comment(
                            "牢大！！！",
                            "false = 屏蔽摔落伤害（默认，安全落地）",
                            "true  = 不再屏蔽，从高处落下会受到坠落伤害",
                            "默认值：false"
                    )
                    .define("thunderFormAllowFallDamage", false);

            b.pop();
            b.pop();
        }
    }
}