package org.crforge.core.entity.effect;

import java.util.HashSet;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.component.Combat;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.EntityType;

/**
 * An area-of-effect zone placed on the arena. Handles both one-shot effects (Zap, EWiz deploy) and
 * ticking effects (Poison, Earthquake, Freeze).
 *
 * <p>One-shot: lifeDuration near zero, applies damage/buff once on the first tick.
 *
 * <p>Ticking: applies damage/buff every hitSpeed seconds for lifeDuration seconds.
 */
@Getter
@SuperBuilder
public class AreaEffect extends AbstractEntity {

  private final AreaEffectStats stats;

  /** Level-scaled damage. Overrides stats.getDamage() when level scaling is applied. */
  @Builder.Default private final int scaledDamage = 0;

  /**
   * Crown tower damage percent resolved from BuffDefinition at deploy time. Overrides
   * stats.getCrownTowerDamagePercent() when non-zero.
   */
  @Builder.Default private final int resolvedCrownTowerDamagePercent = 0;

  /**
   * Building damage percent bonus from BuffDefinition (e.g. Earthquake 350 = 4.5x to buildings).
   */
  @Builder.Default private final int buildingDamagePercent = 0;

  /** Remaining lifetime in seconds. Decremented each tick. */
  @Setter private float remainingLifetime;

  /** Accumulator for ticking effects. When >= hitSpeed, a tick is applied. */
  @Builder.Default @Setter private float tickAccumulator = 0f;

  /** Whether the initial (first-frame) application has occurred. */
  @Builder.Default @Setter private boolean initialApplied = false;

  /**
   * Tracks entity IDs already hit by this effect. Used by hitBiggestTargets (Lightning) to ensure
   * each tick strikes a different target.
   */
  @Builder.Default private final Set<Long> hitEntityIds = new HashSet<>();

  /** Dedup flag: true once controlsBuff cleanup has been performed on expiry. */
  @Builder.Default @Setter private boolean buffsCleaned = false;

  /** Accumulator for spawn delay timing. Incremented each tick until spawn fires. */
  @Builder.Default @Setter private float spawnDelayAccumulator = 0f;

  /** Whether the delayed character spawn has been triggered. Prevents re-firing. */
  @Builder.Default @Setter private boolean spawnTriggered = false;

  /** Rarity of the caster card, used to level-scale the spawned character. */
  @Builder.Default private final Rarity rarity = Rarity.COMMON;

  /** Level of the caster card, used to level-scale the spawned character. */
  @Builder.Default private final int level = 1;

  @Override
  public EntityType getEntityType() {
    return EntityType.SPELL;
  }

  @Override
  public Combat getCombat() {
    return null;
  }

  @Override
  public boolean isTargetable() {
    // Area effects cannot be targeted by other entities
    return false;
  }

  @Override
  public void update(float deltaTime) {
    if (dead) {
      return;
    }
    remainingLifetime -= deltaTime;
    if (remainingLifetime <= 0) {
      // One-shot effects must survive until AreaEffectSystem applies them.
      // Without this guard, effects with very short lifeDuration (e.g. Zap 0.001s)
      // die during the entity update step before AreaEffectSystem processes them.
      if (isOneShot() && !initialApplied) {
        return;
      }
      // Keep alive if a character spawn is pending (e.g. Royal Delivery spawns at 2.05s
      // but lifeDuration is 2.0s)
      if (stats.getSpawnCharacter() != null && !spawnTriggered) {
        return;
      }
      markDead();
    }
  }

  /**
   * Returns the effective damage per application, using scaledDamage if set, otherwise falling back
   * to stats.getDamage().
   */
  public int getEffectiveDamage() {
    return scaledDamage > 0 ? scaledDamage : stats.getDamage();
  }

  /**
   * Returns the effective crown tower damage percent, using the resolved value from BuffDefinition
   * if set, otherwise falling back to stats.getCrownTowerDamagePercent().
   */
  public int getEffectiveCrownTowerDamagePercent() {
    return resolvedCrownTowerDamagePercent != 0
        ? resolvedCrownTowerDamagePercent
        : stats.getCrownTowerDamagePercent();
  }

  /** Whether this is a one-shot effect (lifeDuration very small or hitSpeed is 0). */
  public boolean isOneShot() {
    return stats.getHitSpeed() <= 0;
  }
}
