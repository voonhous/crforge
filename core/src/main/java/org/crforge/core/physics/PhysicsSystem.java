package org.crforge.core.physics;

import java.util.Collection;
import java.util.List;
import org.crforge.core.arena.Arena;
import org.crforge.core.component.Position;
import org.crforge.core.entity.Building;
import org.crforge.core.entity.Entity;
import org.crforge.core.entity.MovementType;
import org.crforge.core.entity.Troop;
import org.crforge.core.player.Team;

public class PhysicsSystem {

  private final Arena arena;

  public PhysicsSystem(Arena arena) {
    this.arena = arena;
  }

  public void update(Collection<Entity> entities, float deltaTime) {
    List<Entity> movableEntities =
        entities.stream()
            .filter(Entity::isAlive)
            .filter(e -> e.getMovementType() != MovementType.BUILDING)
            .toList();

    // Apply movement
    for (Entity entity : movableEntities) {
      if (entity instanceof Troop troop) {
        applyMovement(troop, deltaTime);
      }
    }

    // Resolve collisions
    resolveCollisions(entities);

    // Enforce bounds
    for (Entity entity : movableEntities) {
      enforceBounds(entity);
    }
  }

  private void applyMovement(Troop troop, float deltaTime) {
    if (!troop.getMovement().canMove()) {
      return;
    }
    if (troop.isDeploying()) {
      return;
    }

    // If in attack range, don't move
    if (troop.isInAttackRange()) {
      return;
    }

    Entity target = troop.getCurrentTarget();
    if (target != null && target.isAlive()) {
      moveTowardTarget(troop, target, deltaTime);
    } else {
      moveTowardEnemySide(troop, deltaTime);
    }
  }

  private void moveTowardTarget(Troop troop, Entity target, float deltaTime) {
    Position pos = troop.getPosition();
    Position targetPos = target.getPosition();

    float angle = pos.angleTo(targetPos);
    float speed = troop.getMovement().getEffectiveSpeed();
    float distance = speed * deltaTime;

    float dx = (float) Math.cos(angle) * distance;
    float dy = (float) Math.sin(angle) * distance;

    pos.add(dx, dy);
    pos.setRotation(angle);
  }

  private void moveTowardEnemySide(Troop troop, float deltaTime) {
    Position pos = troop.getPosition();
    Team team = troop.getTeam();

    // Move toward enemy crown tower
    float targetY = (team == Team.BLUE) ? Arena.HEIGHT - 3f : 3f;
    float targetX = arena.getCenterX();

    float angle = (float) Math.atan2(targetY - pos.getY(), targetX - pos.getX());
    float speed = troop.getMovement().getEffectiveSpeed();
    float distance = speed * deltaTime;

    float dx = (float) Math.cos(angle) * distance;
    float dy = (float) Math.sin(angle) * distance;

    pos.add(dx, dy);
    pos.setRotation(angle);
  }

  private void resolveCollisions(Collection<Entity> entities) {
    List<Entity> collidable =
        entities.stream().filter(Entity::isAlive).filter(Entity::isTargetable).toList();

    // Check all pairs
    for (int i = 0; i < collidable.size(); i++) {
      for (int j = i + 1; j < collidable.size(); j++) {
        Entity a = collidable.get(i);
        Entity b = collidable.get(j);

        if (shouldCollide(a, b)) {
          resolveCollision(a, b);
        }
      }
    }
  }

  private boolean shouldCollide(Entity a, Entity b) {
    // Air units don't collide with ground units
    if (a.getMovementType() == MovementType.AIR && b.getMovementType() == MovementType.GROUND) {
      return false;
    }
    return a.getMovementType() != MovementType.GROUND || b.getMovementType() != MovementType.AIR;

    // Buildings always collide with ground troops
  }

  private void resolveCollision(Entity a, Entity b) {
    Position posA = a.getPosition();
    Position posB = b.getPosition();

    float dx = posA.getX() - posB.getX();
    float dy = posA.getY() - posB.getY();
    float dist = (float) Math.sqrt(dx * dx + dy * dy);

    float minDist = (a.getSize() + b.getSize()) / 2f;
    float overlap = minDist - dist;

    if (overlap <= 0) {
      return; // No collision
    }

    // Get masses
    float massA = getMass(a);
    float massB = getMass(b);
    float totalMass = massA + massB;

    if (totalMass <= 0) {
      return;
    }

    // Calculate push ratios (inverse of mass)
    float ratioA = (massB > 0) ? massB / totalMass : 0;
    float ratioB = (massA > 0) ? massA / totalMass : 0;

    // Normalize direction
    if (dist > 0.001f) {
      dx /= dist;
      dy /= dist;
    } else {
      // Overlapping exactly - push in random direction
      dx = 1;
      dy = 0;
    }

    float pushX = overlap * dx;
    float pushY = overlap * dy;

    // Apply push
    if (massA > 0 && a.getMovementType() != MovementType.BUILDING) {
      posA.add(pushX * ratioA, pushY * ratioA);
    }
    if (massB > 0 && b.getMovementType() != MovementType.BUILDING) {
      posB.add(-pushX * ratioB, -pushY * ratioB);
    }
  }

  private float getMass(Entity entity) {
    if (entity instanceof Troop troop) {
      return troop.getMovement().getMass();
    }
    if (entity instanceof Building) {
      return 0; // Buildings are immovable
    }
    return 1;
  }

  private void enforceBounds(Entity entity) {
    Position pos = entity.getPosition();
    float radius = entity.getSize() / 2f;

    float minX = radius;
    float maxX = Arena.WIDTH - radius;
    float minY = radius;
    float maxY = Arena.HEIGHT - radius;

    float x = Math.max(minX, Math.min(maxX, pos.getX()));
    float y = Math.max(minY, Math.min(maxY, pos.getY()));

    pos.set(x, y);
  }
}
