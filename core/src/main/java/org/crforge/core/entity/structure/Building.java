package org.crforge.core.entity.structure;

import static org.crforge.core.card.TroopStats.DEFAULT_DEPLOY_TIME;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
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
  @Setter @Builder.Default private float remainingLifetime = 0f;

  // Accumulator for fractional health decay
  @Setter @Builder.Default private float decayAccumulator = 0f;

  // Added deploy fields
  @Builder.Default private final float deployTime = DEFAULT_DEPLOY_TIME;

  @Setter @Builder.Default private float deployTimer = 1.0f;

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
}
