package org.crforge.core.combat;

import java.util.Collection;
import org.crforge.core.component.Combat;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;

public class TargetingSystem {

  // How far beyond sight range a target remains valid before being dropped
  private static final float TARGET_RETENTION_RANGE_MULTIPLIER = 1.5f;

  public void updateTargets(Collection<Entity> entities) {
    for (Entity entity : entities) {
      if (!entity.isAlive()) {
        continue;
      }

      // Inactive towers do not target
      if (entity instanceof Tower tower && !tower.isActive()) {
        continue;
      }

      Combat combat = entity.getCombat();

      // Entity must have combat capabilities to target anything
      if (combat == null) {
        continue;
      }

      // Troops cannot target while deploying or tunneling underground
      if (entity instanceof Troop troop && (troop.isDeploying() || troop.isTunneling())) {
        continue;
      }

      updateEntityTarget(entity, combat, entities);
    }
  }

  private void updateEntityTarget(Entity attacker, Combat combat, Collection<Entity> entities) {
    Entity currentTarget = combat.getCurrentTarget();

    // If target is still valid, keep it
    if (isValidTarget(attacker, combat, currentTarget)) {
      return;
    }

    // Find new target
    Entity newTarget = findBestTarget(attacker, combat, entities);
    combat.setCurrentTarget(newTarget);
  }

  private Entity findBestTarget(Entity attacker, Combat combat, Collection<Entity> entities) {
    Team enemyTeam = attacker.getTeam().opposite();

    Entity best = null;
    float bestDistSq = Float.MAX_VALUE;

    for (Entity e : entities) {
      if (e.getTeam() != enemyTeam || !e.isTargetable()) {
        continue;
      }
      // Invisible troops cannot be targeted by units (but can still be hit by AOE spells)
      if (e instanceof Troop t && t.isInvisible()) {
        continue;
      }
      if (!canTarget(combat, e)) {
        continue;
      }
      float distSq = getDistanceSq(attacker, e);
      // Use edge-to-edge distance for sight range, matching how CombatSystem checks attack range
      float effectiveSightRange =
          combat.getSightRange() + attacker.getCollisionRadius() + e.getCollisionRadius();
      if (distSq <= effectiveSightRange * effectiveSightRange && distSq < bestDistSq) {
        bestDistSq = distSq;
        best = e;
      }
    }
    return best;
  }

  private boolean isValidTarget(Entity attacker, Combat combat, Entity target) {
    if (target == null || !target.isTargetable()) {
      return false;
    }
    // Drop target that went invisible (forces retarget, which will also skip invisible)
    if (target instanceof Troop t && t.isInvisible()) {
      return false;
    }

    // Check if target is still in range (with leeway), using squared distance to avoid sqrt
    float retentionRange =
        combat.getSightRange() * TARGET_RETENTION_RANGE_MULTIPLIER
            + attacker.getCollisionRadius()
            + target.getCollisionRadius();
    float distanceSq = getDistanceSq(attacker, target);
    if (distanceSq > retentionRange * retentionRange) {
      return false;
    }

    return canTarget(combat, target);
  }

  private boolean canTarget(Combat attackerCombat, Entity target) {
    // targetOnlyBuildings: unit ignores all non-building/tower entities (e.g. Giant, Hog Rider)
    if (attackerCombat.isTargetOnlyBuildings() && !(target instanceof Building)) {
      return false;
    }

    TargetType attackerTargetType = attackerCombat.getTargetType();
    MovementType targetMovement = target.getMovementType();

    return switch (attackerTargetType) {
      case ALL -> true;
      case GROUND ->
          targetMovement == MovementType.GROUND || targetMovement == MovementType.BUILDING;
      case AIR -> targetMovement == MovementType.AIR;
      case BUILDINGS -> targetMovement == MovementType.BUILDING;
    };
  }

  /**
   * Checks if the given target type can target the given movement type. Public for reuse by
   * CombatSystem (multiple targets logic).
   */
  public static boolean canTargetMovementType(
      TargetType attackerType, MovementType targetMovement) {
    return switch (attackerType) {
      case ALL -> true;
      case GROUND ->
          targetMovement == MovementType.GROUND || targetMovement == MovementType.BUILDING;
      case AIR -> targetMovement == MovementType.AIR;
      case BUILDINGS -> targetMovement == MovementType.BUILDING;
    };
  }

  private float getDistance(Entity a, Entity b) {
    return a.getPosition().distanceTo(b.getPosition());
  }

  private float getDistanceSq(Entity a, Entity b) {
    return a.getPosition().distanceToSquared(b.getPosition());
  }
}
