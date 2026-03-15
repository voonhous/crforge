package org.crforge.core.entity.structure;

import static org.crforge.core.card.TroopStats.DEFAULT_DEPLOY_TIME;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.HidingAbility;
import org.crforge.core.component.Combat;
import org.crforge.core.component.ElixirCollectorComponent;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.EntityType;

@Getter
@SuperBuilder
public class Building extends AbstractEntity {

  private final Combat combat;
  @Builder.Default private final AbilityComponent ability = null;
  @Builder.Default private final float lifetime = 0f;
  @Builder.Default private final ElixirCollectorComponent elixirCollector = null;

  // Note: We use @Builder.Default for logic fields we want to initialize
  // based on the lifetime passed to builder
  @Builder.Default private float remainingLifetime = 0f;

  // Accumulator for fractional health decay
  @Builder.Default private float decayAccumulator = 0f;

  // Added deploy fields
  @Builder.Default private final float deployTime = DEFAULT_DEPLOY_TIME;

  @Builder.Default private float deployTimer = 1.0f;

  @Override
  public EntityType getEntityType() {
    return EntityType.BUILDING;
  }

  // Used for testing
  public boolean isDeploying() {
    return deployTimer > 0;
  }

  /** Returns true if this building is hidden underground (Tesla hiding mechanic). */
  public boolean isHidden() {
    return ability != null
        && ability.getData() instanceof HidingAbility
        && ability.isHidingUnderground();
  }

  /**
   * Forces this building to reveal from hiding immediately. Used by Freeze to bypass the normal
   * hiding state machine.
   */
  public void forceReveal() {
    if (ability == null || !(ability.getData() instanceof HidingAbility)) {
      return;
    }
    ability.setHidingState(AbilityComponent.HidingState.UP);
    ability.setHidingTimer(0f);
    ability.setUpTimer(0f);
    if (combat != null) {
      combat.clearModifiers(ModifierSource.ABILITY_HIDING);
    }
  }

  @Override
  public boolean isTargetable() {
    return super.isTargetable() && !isHidden();
  }

  public boolean hasLifetime() {
    return lifetime > 0;
  }

  public boolean isExpired() {
    return hasLifetime() && remainingLifetime <= 0;
  }

  @Override
  public void onSpawn() {
    super.onSpawn();
    if (remainingLifetime == 0 && lifetime > 0) {
      remainingLifetime = lifetime;
    }
    if (deployTime <= 0) {
      deployTimer = 0;
      spawned = true; // Instant spawn
    }
  }

  @Override
  public void update(float deltaTime) {
    if (dead) {
      return;
    }

    // Handle deploy timer
    // While deploying, buildings are targetable but do not decay or attack
    if (deployTimer > 0) {
      deployTimer -= deltaTime;
      if (deployTimer <= 0) {
        deployTimer = 0;
        spawned = true;
      }

      // Buildings accumulate load time while deploying
      if (combat != null) {
        combat.update(deltaTime, true);
      }
      return;
    }

    // Reduce lifetime and apply health decay
    if (hasLifetime()) {
      remainingLifetime -= deltaTime;

      // Calculate decay
      // Rate: MaxHP / TotalLifetime (damage per second)
      float decayRate = (float) health.getMax() / lifetime;
      float decayAmount = decayRate * deltaTime;

      decayAccumulator += decayAmount;

      if (decayAccumulator >= 1.0f) {
        int damage = (int) decayAccumulator;
        health.takeDamage(damage);
        decayAccumulator -= damage;
      }

      // Check lifetime expiry
      // If lifetime expires, we force kill by depleting health.
      // We do NOT call markDead() here directly, because GameState.processDeaths()
      // handles the transition from Health<=0 to Dead=true and triggers onDeath events.
      if (remainingLifetime <= 0) {
        remainingLifetime = 0;
        if (health.isAlive()) {
          health.takeDamage(health.getCurrent());
        }
      }
    }
    // Note: We deliberately do not check health.isDead() -> markDead() here.
    // GameState.processDeaths() will handle it.

    // Update combat
    if (combat != null) {
      // Buildings accumulate load time if not attacking
      combat.update(deltaTime, true);
    }
  }
}
