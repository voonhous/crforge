package org.crforge.core.combat;

import java.util.Collection;
import java.util.Comparator;
import org.crforge.core.component.Combat;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;

public class TargetingSystem {

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

      // Special Case: Troops cannot target while deploying
      if (entity instanceof Troop troop && troop.isDeploying()) {
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
    float sightRange = combat.getSightRange();

    return entities.stream()
        .filter(e -> e.getTeam() == enemyTeam)
        .filter(Entity::isTargetable)
        .filter(e -> canTarget(combat, e))
        .filter(e -> getDistance(attacker, e) <= sightRange)
        .min(Comparator.comparingDouble(e -> getDistance(attacker, e)))
        .orElse(null);
  }

  private boolean isValidTarget(Entity attacker, Combat combat, Entity target) {
    if (target == null || !target.isTargetable()) {
      return false;
    }

    // Check if target is still in range (with leeway)
    float distance = getDistance(attacker, target);
    if (distance > combat.getSightRange() * 1.5f) {
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

  private float getDistance(Entity a, Entity b) {
    return a.getPosition().distanceTo(b.getPosition());
  }
}
