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

    public static final KeyMapping ELBOW_STRIKE = new KeyMapping(
            "key.elbowstrike.strike",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            CATEGORY
    );

    public static final KeyMapping THUNDER_FORM = new KeyMapping(
            "key.elbowstrike.thunder_form",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            CATEGORY
    );

    public static final KeyMapping UNIVERSAL_PULL = new KeyMapping(
            "key.elbowstrike.universal_pull",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            CATEGORY
    );

    private KeyBindings() {}

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(ELBOW_STRIKE);
        event.register(THUNDER_FORM);
        event.register(UNIVERSAL_PULL);
    }
}