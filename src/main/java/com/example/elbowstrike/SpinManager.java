package com.example.elbowstrike;

import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID)
public final class SpinManager {

    private static final class SpinData {
        int ticksLeft;
        float currentYaw;
        boolean initialized;

        SpinData(int ticksLeft) {
            this.ticksLeft = ticksLeft;
            this.initialized = false;
        }
    }

    private static final Map<UUID, SpinData> SPINNING = new HashMap<>();

    private SpinManager() {}

    public static void startSpin(LivingEntity entity) {
        startSpin(entity, ElbowStrikeConfig.COMMON.spinDuration.get());
    }

    public static void startSpin(LivingEntity entity, int duration) {
        if (entity.level().isClientSide()) return;
        if (entity instanceof ServerPlayer) return;
        SPINNING.put(entity.getUUID(), new SpinData(duration));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (SPINNING.isEmpty()) return;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        float spinSpeed = ElbowStrikeConfig.COMMON.spinSpeed.get().floatValue();

        Iterator<Map.Entry<UUID, SpinData>> it = SPINNING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, SpinData> entry = it.next();
            SpinData data = entry.getValue();

            if (data.ticksLeft <= 0) {
                it.remove();
                continue;
            }

            LivingEntity living = findEntity(server, entry.getKey());
            if (living == null || !living.isAlive()) {
                it.remove();
                continue;
            }

            data.ticksLeft--;

            if (living.onGround()) continue;

            if (!data.initialized) {
                data.currentYaw = living.getYRot();
                data.initialized = true;
            }

            data.currentYaw += spinSpeed;
            float newYaw = data.currentYaw;

            living.setYRot(newYaw);
            living.setYHeadRot(newYaw);
            living.yBodyRot = newYaw;
            living.yRotO = newYaw - spinSpeed;
            living.yHeadRotO = newYaw - spinSpeed;
            living.yBodyRotO = newYaw - spinSpeed;

            byte yawByte = (byte) (newYaw * 256.0F / 360.0F);
            byte pitchByte = (byte) (living.getXRot() * 256.0F / 360.0F);

            ClientboundMoveEntityPacket.PosRot posRotPacket =
                    new ClientboundMoveEntityPacket.PosRot(
                            living.getId(),
                            (short) 0, (short) 0, (short) 0,
                            yawByte, pitchByte,
                            living.onGround()
                    );

            PacketDistributor.TRACKING_ENTITY.with(() -> living).send(posRotPacket);
        }
    }

    private static LivingEntity findEntity(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(uuid);
            if (e instanceof LivingEntity living
                    && living.isAlive()
                    && !(living instanceof ServerPlayer)) {
                return living;
            }
        }
        return null;
    }
}