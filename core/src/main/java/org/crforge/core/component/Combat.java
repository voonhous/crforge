package org.crforge.core.component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.AttackSequenceHit;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.TargetType;

@Getter
@Builder
public class Combat {

  // -- Config fields (static for the lifetime of this component) --

  @Builder.Default private final int damage = 0;
  @Builder.Default private final float range = 1.0f;
  @Builder.Default private final float sightRange = 5.5f;
  @Builder.Default private final float attackCooldown = 1.0f; // "Hit Speed"

  @Builder.Default
  private final float loadTime = 0f; // Hidden stat: charges up to reduce first hit delay

  @Builder.Default private final float aoeRadius = 0;
  @Builder.Default private final TargetType targetType = TargetType.ALL;

  @Builder.Default private final List<EffectStats> hitEffects = new ArrayList<>();

  private final ProjectileStats projectileStats;

  // Combat modifiers
  @Builder.Default private final int multipleTargets = 0;
  @Builder.Default private final int multipleProjectiles = 0;
  @Builder.Default private final boolean selfAsAoeCenter = false;

  // Buff applied to target when dealing damage (e.g. EWiz stun on hit)
  private final EffectStats buffOnDamage;

  // Area effect spawned on each attack hit (e.g. BattleHealer heal on hit)
  private final AreaEffectStats areaEffectOnHit;

  // Attack dash: short lunge toward target when attack starts (e.g. Bat)
  @Builder.Default private final float attackDashTime = 0f;

  // Attack pushback: self-knockback when firing (e.g. Firecracker recoils backward)
  @Builder.Default private final float attackPushBack = 0f;

  // Attack sequence: per-hit damage values for units with multi-hit combos (e.g. Berserker)
  @Builder.Default private final List<AttackSequenceHit> attackSequence = List.of();

  // Kamikaze: unit dies after delivering its attack (e.g. Battle Ram)
  @Builder.Default private final boolean kamikaze = false;

  // Targeting and combat modifiers
  @Builder.Default private final boolean targetOnlyBuildings = false;
  @Builder.Default private final boolean targetOnlyTroops = false;
  @Builder.Default private final float minimumRange = 0f;
  @Builder.Default private final int crownTowerDamagePercent = 0;

  // Ignore targets that already have this buff applied (e.g. Ram Rider skips bola'd targets)
  private final String ignoreTargetsWithBuff;

  // Ability-driven damage override (e.g. variable damage / inferno)
  // When > 0, used instead of base damage
  @Setter @Builder.Default private int damageOverride = 0;

  // -- Attack state machine (runtime attack sequencing) --

  @Builder.Default private final AttackStateMachine attackState = new AttackStateMachine();

  // -- Target management --

  private Entity currentTarget;
  @Setter private boolean targetLocked;

  // -- Modifier management --

  // Source-tracked combat disable -- any source present means combat is disabled
  @Builder.Default
  private final EnumSet<ModifierSource> combatDisableSources = EnumSet.noneOf(ModifierSource.class);

  // Source-tracked attack speed multipliers -- effective multiplier is the product of all
  @Builder.Default
  private final EnumMap<ModifierSource, Float> attackSpeedMultipliers =
      new EnumMap<>(ModifierSource.class);

  // Units with range >= this threshold use projectile attacks instead of melee
  private static final float RANGED_THRESHOLD = 2.0f;

  /** Returns true if the unit is considered ranged (Range >= 2.0 tiles). */
  public boolean isRanged() {
    return range >= RANGED_THRESHOLD;
  }

  public boolean hasTarget() {
    return currentTarget != null && currentTarget.isAlive();
  }

  public void setCurrentTarget(Entity currentTarget) {
    if (this.currentTarget != currentTarget) {
      this.currentTarget = currentTarget;
      this.targetLocked = false;
      // Reset attack state on retarget
      attackState.setAttacking(false);
      attackState.setCurrentWindup(0);
      // Do NOT reset accumulatedLoadTime here; moving to new target preserves charge.
    }
  }

  public void clearTarget() {
    setCurrentTarget(null);
  }

  // -- Modifier management methods --

  /** Set combat disabled state for a specific source. */
  public void setCombatDisabled(ModifierSource source, boolean disabled) {
    if (disabled) {
      combatDisableSources.add(source);
    } else {
      combatDisableSources.remove(source);
    }
  }

  /** Returns true if any source has disabled combat. */
  public boolean isCombatDisabled() {
    return !combatDisableSources.isEmpty();
  }

  /** Set attack speed multiplier for a specific source. */
  public void setAttackSpeedMultiplier(ModifierSource source, float multiplier) {
    if (multiplier == 1.0f) {
      attackSpeedMultipliers.remove(source);
    } else {
      attackSpeedMultipliers.put(source, multiplier);
    }
  }

  /** Returns the product of all active attack speed multipliers. */
  public float getAttackSpeedMultiplier() {
    if (attackSpeedMultipliers.isEmpty()) {
      return 1.0f;
    }
    float product = 1.0f;
    for (float mult : attackSpeedMultipliers.values()) {
      product *= mult;
    }
    return product;
  }

  /** Clears all modifiers (disable + attack speed) for the given source. */
  public void clearModifiers(ModifierSource source) {
    combatDisableSources.remove(source);
    attackSpeedMultipliers.remove(source);
  }

  /** Returns true if a returning projectile (boomerang) is currently in flight. */
  public boolean isReturningProjectileInFlight() {
    return combatDisableSources.contains(ModifierSource.RETURNING_PROJECTILE);
  }

  // -- Delegation methods to AttackStateMachine for backward compatibility --

  /** Returns the effective damage for the current attack, using attack sequence if available. */
  public int getEffectiveDamage() {
    return attackState.getEffectiveDamage(damage, attackSequence);
  }

  public boolean canAttack() {
    return attackState.canAttack(isCombatDisabled());
  }

  public boolean isWindingUp() {
    return attackState.isWindingUp();
  }

  public boolean isAttacking() {
    return attackState.isAttacking();
  }

  public void setAttacking(boolean attacking) {
    attackState.setAttacking(attacking);
  }

  /**
   * Starts an attack sequence. Calculates windup based on attackCooldown and accumulatedLoadTime.
   */
  public void startAttackSequence() {
    attackState.startAttackSequence(attackCooldown);
  }

  public void finishAttack() {
    attackState.finishAttack(attackSequence);
  }

  /**
   * Resets the attack animation/load time. Used for Stun (Zap) mechanics. Unlike Freeze (which
   * pauses), Stun forces the unit to restart their attack windup. Also unlocks target.
   */
  public void resetAttackState() {
    attackState.resetAttackState();
    this.targetLocked = false; // Stun unlocks target
  }

  /**
   * Updates timers and charge.
   *
   * @param deltaTime Time passed
   * @param canAccumulateLoad If true, we are moving/deploying/idle and can charge up.
   */
  public void update(float deltaTime, boolean canAccumulateLoad) {
    boolean disabled = isCombatDisabled();
    float effectiveDelta = deltaTime * (disabled ? 0 : getAttackSpeedMultiplier());
    attackState.update(effectiveDelta, canAccumulateLoad, loadTime);
  }

  public float getCurrentWindup() {
    return attackState.getCurrentWindup();
  }

  public void setCurrentWindup(float currentWindup) {
    attackState.setCurrentWindup(currentWindup);
  }

  public float getCurrentCooldown() {
    return attackState.getCurrentCooldown();
  }

  public void setCurrentCooldown(float currentCooldown) {
    attackState.setCurrentCooldown(currentCooldown);
  }

  public float getAccumulatedLoadTime() {
    return attackState.getAccumulatedLoadTime();
  }

  public void setAccumulatedLoadTime(float accumulatedLoadTime) {
    attackState.setAccumulatedLoadTime(accumulatedLoadTime);
  }

  public int getAttackSequenceIndex() {
    return attackState.getAttackSequenceIndex();
  }

  public void setAttackSequenceIndex(int attackSequenceIndex) {
    attackState.setAttackSequenceIndex(attackSequenceIndex);
  }
}
