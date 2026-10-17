package com.example.elbowstrike.client;

import com.example.elbowstrike.ElbowStrikeMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class KeyBindings {

    public static final String CATEGORY = "key.categories.elbowstrike";

    /**
     * 肘击模式键。
     * <p>
     * 按住此键时：
     * <ul>
     *     <li>左键 → 肘击</li>
     *     <li>右键 → 万象天引</li>
     *     <li>中键 → 雷霆形态</li>
     * </ul>
     * 原版的攻击/使用物品/选取方块动作会被吞掉，不会破坏方块或使用手持物品。
     */
    public static final KeyMapping ELBOW_MODE = new KeyMapping(
            "key.elbowstrike.mode",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            CATEGORY
    );

    private KeyBindings() {}

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(ELBOW_MODE);
    }
}
