package org.crforge.core.combat;

import java.util.Collection;
import java.util.Comparator;
import org.crforge.core.entity.Building;
import org.crforge.core.entity.Entity;
import org.crforge.core.entity.MovementType;
import org.crforge.core.entity.TargetType;
import org.crforge.core.entity.Tower;
import org.crforge.core.entity.Troop;
import org.crforge.core.player.Team;

public class TargetingSystem {

  public void updateTargets(Collection<Entity> entities) {
    for (Entity entity : entities) {
      if (entity instanceof Troop troop && troop.isAlive() && !troop.isDeploying()) {
        updateTroopTarget(troop, entities);
      } else if (entity instanceof Tower tower && tower.isAlive()) {
        updateTowerTarget(tower, entities);
      }
    }
  }

  private void updateTroopTarget(Troop troop, Collection<Entity> entities) {
    // If target is still valid, keep it
    if (troop.hasTarget() && isValidTarget(troop, troop.getCurrentTarget())) {
      return;
    }

    // Find new target
    Entity newTarget = findBestTarget(troop, entities);
    troop.setCurrentTarget(newTarget);
  }

  private void updateTowerTarget(Tower tower, Collection<Entity> entities) {
    // If target is still valid, keep it
    if (tower.hasTarget() && isValidTarget(tower, tower.getCurrentTarget())) {
      return;
    }

    // Find new target
    Entity newTarget = findBestTargetForTower(tower, entities);
    tower.setCurrentTarget(newTarget);
  }

  private Entity findBestTarget(Troop troop, Collection<Entity> entities) {
    Team enemyTeam = troop.getTeam().opposite();
    TargetType targetType = troop.getCombat().getTargetType();
    float sightRange = troop.getCombat().getSightRange();

    return entities.stream()
        .filter(e -> e.getTeam() == enemyTeam)
        .filter(Entity::isTargetable)
        .filter(e -> canTarget(troop, e, targetType))
        .filter(e -> getDistance(troop, e) <= sightRange)
        .min(Comparator.comparingDouble(e -> getDistance(troop, e)))
        .orElse(null);
  }

  private Entity findBestTargetForTower(Tower tower, Collection<Entity> entities) {
    Team enemyTeam = tower.getTeam().opposite();
    float sightRange = tower.getCombat().getSightRange();

    return entities.stream()
        .filter(e -> e.getTeam() == enemyTeam)
        .filter(Entity::isTargetable)
        .filter(e -> !(e instanceof Tower)) // Towers don't attack other towers
        .filter(e -> !(e instanceof Building)) // Towers prefer troops
        .filter(e -> getDistance(tower, e) <= sightRange)
        .min(Comparator.comparingDouble(e -> getDistance(tower, e)))
        .orElse(null);
  }

  private boolean isValidTarget(Entity attacker, Entity target) {
    if (target == null || !target.isTargetable()) {
      return false;
    }

    // Check if target is still in range
    float sightRange;
    TargetType targetType;

    if (attacker instanceof Troop troop) {
      sightRange = troop.getCombat().getSightRange();
      targetType = troop.getCombat().getTargetType();
    } else if (attacker instanceof Tower tower) {
      sightRange = tower.getCombat().getSightRange();
      targetType = TargetType.ALL;
    } else {
      return false;
    }

    float distance = getDistance(attacker, target);
    if (distance > sightRange * 1.5f) { // Allow some leeway before retargeting
      return false;
    }

    if (attacker instanceof Troop troop) {
      return canTarget(troop, target, targetType);
    }

    return true;
  }

  private boolean canTarget(Entity attacker, Entity target, TargetType targetType) {
    MovementType targetMovement = target.getMovementType();

    return switch (targetType) {
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
