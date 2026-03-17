package org.crforge.core.engine;

import java.util.List;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.BuffApplication;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.ScaledDamageTier;
import org.crforge.core.component.Position;
import org.crforge.core.effect.BuffDefinition;
import org.crforge.core.effect.BuffRegistry;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.player.Team;

/** Creates AreaEffect entities with level-scaled damage, buff-derived DPS, and laser ball tiers. */
class AreaEffectFactory {

  private final GameState state;

  AreaEffectFactory(GameState state) {
    this.state = state;
  }

  void deployAreaEffect(
      Team team, AreaEffectStats stats, float x, float y, Rarity rarity, int level) {
    int scaledDamage =
        stats.getDamage() > 0 ? LevelScaling.scaleCard(stats.getDamage(), rarity, level) : 0;
    int resolvedCtdp = stats.getCrownTowerDamagePercent();
    int buildingDmgPct = 0;

    // Resolve values from BuffDefinitions in the buff applications
    for (BuffApplication buffApp : stats.getBuffApplications()) {
      BuffDefinition buffDef = BuffRegistry.get(buffApp.buffName());
      if (buffDef == null) {
        continue;
      }

      // DPS derivation from any buff with damagePerSecond
      if (scaledDamage == 0 && buffDef.getDamagePerSecond() > 0) {
        float hitSpeed = stats.getHitSpeed() > 0 ? stats.getHitSpeed() : 1.0f;
        int baseDamage = Math.round(buffDef.getDamagePerSecond() * hitSpeed);
        scaledDamage = LevelScaling.scaleCard(baseDamage, rarity, level);
      }

      // Crown tower damage percent
      if (resolvedCtdp == 0 && buffDef.getCrownTowerDamagePercent() != 0) {
        resolvedCtdp = buffDef.getCrownTowerDamagePercent();
      }

      // Building damage percent
      if (buildingDmgPct == 0 && buffDef.getBuildingDamagePercent() != 0) {
        buildingDmgPct = buffDef.getBuildingDamagePercent();
      }
    }

    // DOT scaling for targeted effects (Vines)
    int scaledDotDamage = 0;
    int scaledCrownTowerDotDamage = 0;
    if (stats.getTargetCount() > 0) {
      for (BuffApplication buffApp : stats.getBuffApplications()) {
        BuffDefinition bd = BuffRegistry.get(buffApp.buffName());
        if (bd != null && bd.getDamagePerSecond() > 0) {
          float hitFrequency = bd.getHitFrequency() > 0 ? bd.getHitFrequency() : 1.0f;
          scaledDotDamage =
              LevelScaling.scaleCard(
                  Math.round(bd.getDamagePerSecond() * hitFrequency), rarity, level);
          if (bd.getCrownTowerDamagePerHit() > 0) {
            scaledCrownTowerDotDamage =
                LevelScaling.scaleCard(bd.getCrownTowerDamagePerHit(), rarity, level);
          }
          break;
        }
      }
    }

    // Absolute CT damage for general ticking AEOs
    if (stats.getTargetCount() == 0 && scaledCrownTowerDotDamage == 0) {
      for (BuffApplication buffApp : stats.getBuffApplications()) {
        BuffDefinition bd = BuffRegistry.get(buffApp.buffName());
        if (bd != null && bd.getCrownTowerDamagePerHit() > 0) {
          scaledCrownTowerDotDamage =
              LevelScaling.scaleCard(bd.getCrownTowerDamagePerHit(), rarity, level);
          break;
        }
      }
    }

    // Scale laser ball damage tiers (DarkMagic)
    // damageTier DPS and CT values are at Common L1 base, not the card's rarity base.
    // We must scale using COMMON rarity with the effective card level (clamped to rarity min).
    // IMPORTANT: scale damagePerSecond FIRST, then multiply by hitFrequency. This avoids
    // intermediate rounding errors that cause +/-1 deviations from expected values.
    List<ScaledDamageTier> scaledTiers = List.of();
    int totalLaserScans = 0;
    if (!stats.getDamageTiers().isEmpty()) {
      totalLaserScans = stats.computeTotalLaserScans();
      int effectiveLevel = Math.max(level, LevelScaling.getMinLevel(rarity));
      scaledTiers =
          stats.getDamageTiers().stream()
              .map(
                  tier -> {
                    // Scale raw DPS as Common L1 base, then convert to per-hit
                    int scaledDPS =
                        LevelScaling.scaleCard(
                            tier.damagePerSecond(), Rarity.COMMON, effectiveLevel);
                    int scaledDamagePerHit = (int) (scaledDPS * tier.hitFrequency());
                    int scaledCtPerHit =
                        tier.crownTowerDamagePerHit() > 0
                            ? LevelScaling.scaleCard(
                                tier.crownTowerDamagePerHit(), Rarity.COMMON, effectiveLevel)
                            : 0;
                    return new ScaledDamageTier(
                        scaledDamagePerHit, scaledCtPerHit, tier.maxTargets());
                  })
              .toList();
    }

    AreaEffect effect =
        AreaEffect.builder()
            .name(stats.getName())
            .team(team)
            .position(new Position(x, y))
            .stats(stats)
            .scaledDamage(scaledDamage)
            .resolvedCrownTowerDamagePercent(resolvedCtdp)
            .buildingDamagePercent(buildingDmgPct)
            .scaledDotDamage(scaledDotDamage)
            .scaledCrownTowerDotDamage(scaledCrownTowerDotDamage)
            .scaledDamageTiers(scaledTiers)
            .totalLaserScans(totalLaserScans)
            .remainingLifetime(stats.getLifeDuration())
            .rarity(rarity)
            .level(level)
            .build();

    state.spawnEntity(effect);
  }
}
