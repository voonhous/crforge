package org.crforge.core.entity.unit;

import static org.crforge.core.card.TroopStats.DEFAULT_DEPLOY_TIME;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.GiantBuffState;
import org.crforge.core.card.Card;
import org.crforge.core.card.TransformationConfig;
import org.crforge.core.component.AttachedComponent;
import org.crforge.core.component.Combat;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.EntityType;
import org.crforge.core.entity.base.MovementType;

@Getter
@SuperBuilder
public class Troop extends AbstractEntity {

  @Builder.Default private final Combat combat = Combat.builder().build();

  @Builder.Default private final float deployTime = DEFAULT_DEPLOY_TIME;

  @Setter @Builder.Default private float deployTimer = 1.0f;

  @Builder.Default private final AbilityComponent ability = null;

  // Marks this entity as a clone (1 HP, cannot be re-cloned)
  @Builder.Default private final boolean clone = false;

  // Attached to parent entity (e.g. Ram Rider on Ram, Spear Goblins on Goblin Giant)
  @Builder.Default private final AttachedComponent attached = null;

  // River jump state: true while the troop is leaping over the river
  @Setter private boolean jumping;

  // Tunnel state: true while the troop is traveling underground (Miner)
  @Setter private boolean tunneling;

  // Air-to-ground timer: while > 0, air units are treated as ground for targeting (Vines)
  @Setter @Builder.Default private float groundedTimer = 0f;

  // Morph context: carried by tunnel dig troops so the building can be created on arrival
  @Setter @Builder.Default private Card morphCard = null;
  @Setter @Builder.Default private int morphLevel = 0;

  // GiantBuffer buff state: active buff from a friendly GiantBuffer
  @Setter @Builder.Default private GiantBuffState giantBuff = null;

  // HP-threshold transformation config (e.g. GoblinDemolisher -> kamikaze form at 50% HP)
  @Builder.Default private final TransformationConfig transformConfig = null;

  // Whether this troop has already transformed (prevents re-transformation)
  @Setter @Builder.Default private boolean transformed = false;

  // Lifetime countdown for troops with limited duration (e.g. kamikaze form's 20s lifeTime)
  // 0 = no lifetime limit
  @Setter @Builder.Default private float lifeTimer = 0f;

  /** Returns true if this troop is currently invisible (stealth ability active). */
  public boolean isInvisible() {
    return ability != null && ability.isInvisible();
  }

  /** Returns true if this troop is attached to a parent entity. */
  public boolean isAttached() {
    return attached != null;
  }

  @Override
  public EntityType getEntityType() {
    return EntityType.TROOP;
  }

  @Override
  public MovementType getMovementType() {
    // While jumping, behave as AIR for pathfinding, collision, and targeting
    if (jumping) {
      return MovementType.AIR;
    }
    // Vines air-to-ground: while grounded timer is active, air units behave as ground
    if (groundedTimer > 0 && super.getMovementType() == MovementType.AIR) {
      return MovementType.GROUND;
    }
    return super.getMovementType();
  }

  public boolean isDeploying() {
    return deployTimer > 0;
  }

  public boolean isInAttackRange() {
    if (combat == null) {
      return false;
    }
    Entity currentTarget = combat.getCurrentTarget();
    if (currentTarget == null) {
      return false;
    }
    float distance = position.distanceTo(currentTarget.getPosition());
    // Collision Radius used for attack range calculation
    float effectiveRange =
        combat.getRange() + getCollisionRadius() + currentTarget.getCollisionRadius();
    return distance <= effectiveRange;
  }

  public float getDistanceToTarget() {
    if (combat == null) {
      return Float.MAX_VALUE;
    }
    Entity currentTarget = combat.getCurrentTarget();
    if (currentTarget == null) {
      return Float.MAX_VALUE;
    }
    return position.distanceTo(currentTarget.getPosition());
  }

  @Override
  public void update(float deltaTime) {
    if (dead) {
      return;
    }

    // Handle deploy timer
    if (deployTimer > 0) {
      deployTimer -= deltaTime;
      if (deployTimer <= 0) {
        deployTimer = 0;
        spawned = true;
      }
      // Troops accumulate load time while deploying
      if (combat != null) {
        combat.update(deltaTime, true);
      }
      return;
    }

    // Lifetime countdown: kill troop when timer expires (e.g. kamikaze form's 20s lifeTime)
    if (lifeTimer > 0) {
      lifeTimer -= deltaTime;
      if (lifeTimer <= 0) {
        health.kill();
        return;
      }
    }

    // Decrement grounded timer (Vines air-to-ground)
    if (groundedTimer > 0) {
      groundedTimer -= deltaTime;
    }

    // Update combat
    if (combat != null) {
      // Pass true to allow accumulating load time if not attacking
      combat.update(deltaTime, true);
    }
  }

  @Override
  public void onSpawn() {
    super.onSpawn();
    if (deployTime <= 0) {
      deployTimer = 0;
    }
  }

  @Override
  public boolean isTargetable() {
    return super.isTargetable() && !jumping && !tunneling && !isAttached();
  }
}
