package com.example.elbowstrike;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 招架管理器。
 * <p>
 * 肘击后开启一个短窗口（默认 0.5 秒）。
 * 窗口内被生物攻击时触发招架：
 * <ol>
 *     <li>完全免疫该次伤害；</li>
 *     <li>播放招架音效；</li>
 *     <li>对攻击者释放一次肘击（伤害 + 击退 + 可选旋转）。</li>
 * </ol>
 * 一次窗口只挡一次。
 * <p>
 * 注意：本类通过 {@code @Mod.EventBusSubscriber} 自动注册到 Forge 事件总线，
 * 不要在 {@link ElbowStrikeMod} 里再手动 register，否则事件会触发两次。
 */
@Mod.EventBusSubscriber(modid = ElbowStrikeMod.MODID)
public final class ParryManager {

    /** 玩家 UUID -> 招架窗口到期的 gameTime */
    private static final Map<UUID, Long> PARRY_WINDOWS = new HashMap<>();

    private ParryManager() {}

    /**
     * 肘击时调用（无论是否命中目标都开启窗口，
     * 因为窗口描述的是"肘击动作后 0.5 秒"）。
     */
    public static void openWindow(ServerPlayer player) {
        if (!ElbowStrikeConfig.COMMON.enableParry.get()) return;
        int duration = ElbowStrikeConfig.COMMON.parryWindowTicks.get();
        if (duration <= 0) return;
        long expireAt = player.level().getGameTime() + duration;
        PARRY_WINDOWS.put(player.getUUID(), expireAt);
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;

        UUID uuid = player.getUUID();
        Long expireAt = PARRY_WINDOWS.get(uuid);
        if (expireAt == null) return;

        long now = player.level().getGameTime();
        if (now > expireAt) {
            PARRY_WINDOWS.remove(uuid);
            return;
        }

        DamageSource source = event.getSource();

        // 弹射物（箭、火球、雪球…）不触发招架
        if (source.getDirectEntity() instanceof Projectile) return;

        Entity attackerEntity = source.getEntity();
        if (!(attackerEntity instanceof LivingEntity attacker)) return;
        if (attacker == player) return;

        // 消耗窗口：一次肘击只能招架一次
        PARRY_WINDOWS.remove(uuid);

        // 1) 完全免疫
        event.setAmount(0.0F);
        event.setCanceled(true);

        // 2) 招架音效（复用肘击音效，音调更高以示区别）
        player.level().playSound(
                null,
                player.getX(), player.getY() + 1.0D, player.getZ(),
                ModSounds.ELBOW_STRIKE.get(),
                SoundSource.PLAYERS,
                1.0F, 1.8F
        );

        // 3) 反击肘击
        ElbowStrikeHandler.parryCounter(player, attacker);
    }

    /** 玩家退出时清掉残留窗口，避免 UUID 泄漏 */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            PARRY_WINDOWS.remove(sp.getUUID());
        }
    }
}