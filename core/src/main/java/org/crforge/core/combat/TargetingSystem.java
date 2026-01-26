package org.crforge.core.combat;

import java.util.Collection;
import java.util.Comparator;
import org.crforge.core.component.Combat;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;

public class TargetingSystem {

  public void updateTargets(Collection<Entity> entities) {
    for (Entity entity : entities) {
      if (entity instanceof Troop troop && troop.isAlive() && !troop.isDeploying()) {
        updateEntityTarget(troop, entities);
      } else if (entity instanceof Building building && building.isAlive()) {
        // Handles both Tower and regular Buildings (like Cannon)
        updateEntityTarget(building, entities);
      }
    }
  }

  private void updateEntityTarget(Entity entity, Collection<Entity> entities) {
    // Need to cast to access currentTarget methods
    Entity currentTarget = null;
    if (entity instanceof Troop t) {
      currentTarget = t.getCurrentTarget();
    }
    if (entity instanceof Building b) {
      currentTarget = b.getCurrentTarget();
    }

    // If target is still valid, keep it
    if (isValidTarget(entity, currentTarget)) {
      return;
    }

    // Find new target
    Entity newTarget = findBestTarget(entity, entities);

    if (entity instanceof Troop t) {
      t.setCurrentTarget(newTarget);
    }
    if (entity instanceof Building b) {
      b.setCurrentTarget(newTarget);
    }
  }

  private Entity findBestTarget(Entity attacker, Collection<Entity> entities) {
    Combat combat = attacker.getCombat();
    if (combat == null) {
      return null;
    }

    Team enemyTeam = attacker.getTeam().opposite();
    float sightRange = combat.getSightRange();

    return entities.stream()
        .filter(e -> e.getTeam() == enemyTeam)
        .filter(Entity::isTargetable)
        .filter(e -> canTarget(attacker, e))
        .filter(e -> getDistance(attacker, e) <= sightRange)
        .min(Comparator.comparingDouble(e -> getDistance(attacker, e)))
        .orElse(null);
  }

  private boolean isValidTarget(Entity attacker, Entity target) {
    if (target == null || !target.isTargetable()) {
      return false;
    }

    Combat combat = attacker.getCombat();
    if (combat == null) {
      return false;
    }

    // Check if target is still in range (with leeway)
    float distance = getDistance(attacker, target);
    if (distance > combat.getSightRange() * 1.5f) {
      return false;
    }

    return canTarget(attacker, target);
  }

  private boolean canTarget(Entity attacker, Entity target) {
    TargetType attackerTargetType = attacker.getCombat().getTargetType();
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
