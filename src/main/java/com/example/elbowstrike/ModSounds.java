package com.example.elbowstrike;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ElbowStrikeMod.MODID);

    /** 对应 sounds/man.ogg，注册名为 elbowstrike:man */
    public static final RegistryObject<SoundEvent> ELBOW_STRIKE =
            SOUND_EVENTS.register("man", () ->
                    SoundEvent.createVariableRangeEvent(
                            new ResourceLocation(ElbowStrikeMod.MODID, "man")
                    )
            );

    private ModSounds() {}

    public static void register(IEventBus eventBus) {
        SOUND_EVENTS.register(eventBus);
    }
}