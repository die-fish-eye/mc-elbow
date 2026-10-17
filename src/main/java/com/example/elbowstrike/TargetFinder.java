package com.example.elbowstrike;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用锥形目标查找工具。
 * <p>
 * 以施法者为中心，在前方 {@code range} 格、锥形阈值 {@code cone}
 * （视线与目标方向夹角的余弦）范围内，找出所有活着且可被拾取的生物。
 * <p>
 * 供 {@link ElbowStrikeHandler}（肘击）与 {@link UniversalPullHandler}
 * （万象天引）共用，避免重复实现同一套判定逻辑。
 */
public final class TargetFinder {

    private TargetFinder() {}

    /**
     * @param caster 施法者（玩家或生物均可）
     * @param range  判定距离（格）
     * @param cone   锥形阈值，0.5 ≈ 前方 60°，1.0 ≈ 正前方
     * @return 命中的目标列表（不包含施法者自身）
     */
    public static List<LivingEntity> findConeTargets(LivingEntity caster,
                                                     double range,
                                                     double cone) {
        Level level = caster.level();
        Vec3 eye = caster.getEyePosition();
        Vec3 look = caster.getLookAngle().normalize();

        // 朝视线方向膨胀的 AABB，减少候选集
        AABB box = caster.getBoundingBox()
                .inflate(range + 1.0D)
                .expandTowards(look.scale(range));

        List<Entity> candidates = level.getEntities(caster, box,
                e -> e instanceof LivingEntity living
                        && living.isAlive()
                        && living.isPickable());

        List<LivingEntity> result = new ArrayList<>();
        for (Entity e : candidates) {
            LivingEntity living = (LivingEntity) e;
            Vec3 center = living.position().add(0.0D, living.getBbHeight() * 0.5D, 0.0D);
            Vec3 to = center.subtract(eye);
            double dist = to.length();

            if (dist > range + living.getBbWidth() * 0.5D) continue;
            if (dist < 1.0E-4D) continue;
            if (to.normalize().dot(look) < cone) continue;

            result.add(living);
        }
        return result;
    }
}
