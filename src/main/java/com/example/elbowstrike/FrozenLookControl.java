package com.example.elbowstrike;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.LookControl;

/**
 * 什么都不做的 LookControl。
 * 用它临时替换 Mob 的 lookControl，阻止 serverAiStep() 覆盖我们的旋转。
 */
public class FrozenLookControl extends LookControl {

    public FrozenLookControl(Mob mob) {
        super(mob);
    }

    @Override
    public void tick() {
        // 冻结朝向，什么都不做
    }
}