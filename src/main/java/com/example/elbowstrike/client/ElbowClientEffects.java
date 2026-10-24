package com.example.elbowstrike.client;

import com.example.elbowstrike.ElbowStrikeConfig;
import com.example.elbowstrike.ElbowStrikeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 蓄力肘击的客户端视觉反馈。
 * <p>
 * 两部分：
 * <ul>
 *     <li><b>蓄力粒子</b>：蓄力过程中在玩家周身生成向内聚拢的粒子，
 *         越接近满蓄力越收拢、粒子越亮（绿星 → 末地烛 → 电火花）；</li>
 *     <li><b>满蓄力提示</b>：蓄满瞬间一次性爆发一圈末地烛 + 音爆粒子 + 提示音。</li>
 * </ul>
 * 全部可通过 {@code config/elbowstrike-common.toml} 的 {@code effects} 段开关。
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID, value = Dist.CLIENT)
public final class ElbowClientEffects {

    /** 本次蓄力是否已触发过「满蓄力」提示 */
    private static boolean fullChargeNotified = false;

    private ElbowClientEffects() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            fullChargeNotified = false;
            return;
        }
        if (!ElbowStrikeConfig.COMMON.enableEffects.get()) return;
        if (!ClientChargeHandler.isCharging()) {
            fullChargeNotified = false;
            return;
        }

        float progress = ClientChargeHandler.getChargeProgress();

        if (ElbowStrikeConfig.COMMON.chargeParticles.get()) {
            spawnChargeParticles(mc, progress);
        }
        if (progress >= 1.0F && !fullChargeNotified) {
            fullChargeNotified = true;
            onFullCharge(mc);
        }
    }

    /** 蓄力中：周身向内聚拢的粒子，颜色随蓄力升级 */
    private static void spawnChargeParticles(Minecraft mc, float progress) {
        Player p = mc.player;
        double cx = p.getX();
        double cy = p.getY() + 1.0D;
        double cz = p.getZ();

        int count = 2 + Math.round(progress * 6.0F);
        for (int i = 0; i < count; i++) {
            double a = mc.level.random.nextDouble() * Math.PI * 2.0D;
            double r = 0.95D - progress * 0.45D; // 越蓄越收拢
            double px = cx + Math.cos(a) * r;
            double pz = cz + Math.sin(a) * r;
            double py = cy + (mc.level.random.nextDouble() - 0.5D) * 1.4D;
            double vx = -Math.cos(a) * 0.09D;
            double vz = -Math.sin(a) * 0.09D;

            if (progress >= 0.999F) {
                mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, px, py, pz, vx, 0.03D, vz);
            } else if (progress > 0.5F) {
                mc.level.addParticle(ParticleTypes.END_ROD, px, py, pz, vx, 0.03D, vz);
            } else {
                mc.level.addParticle(ParticleTypes.CRIT, px, py, pz, vx, 0.03D, vz);
            }
        }
    }

    /** 蓄满瞬间：一圈外扩粒子 + 音爆 + 提示音（一次性） */
    private static void onFullCharge(Minecraft mc) {
        Player p = mc.player;
        double cx = p.getX();
        double cy = p.getY() + 1.0D;
        double cz = p.getZ();

        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2.0D * i / 24.0D;
            mc.level.addParticle(ParticleTypes.END_ROD,
                    cx + Math.cos(a) * 0.85D, cy, cz + Math.sin(a) * 0.85D,
                    Math.cos(a) * 0.15D, 0.06D, Math.sin(a) * 0.15D);
        }
        mc.level.addParticle(ParticleTypes.SONIC_BOOM, cx, cy, cz, 0.0D, 0.0D, 0.0D);
        p.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.8F, 1.4F);
    }
}
