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
  private final Pathfinder pathfinder;

  public PhysicsSystem(Arena arena, Pathfinder pathfinder) {
    this.arena = arena;
    this.pathfinder = pathfinder;
  }

  public PhysicsSystem(Arena arena) {
    this(arena, new BasePathfinder());
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

    float angle =
        pathfinder.getNextMovementAngle(
            pos,
            troop.getMovementType(),
            targetPos.getX(),
            targetPos.getY(),
            arena);

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

    // Determine which lane the troop is in based on X position
    float centerX = arena.getCenterX();
    boolean isLeftLane = pos.getX() < centerX;

    // Move toward the enemy princess tower in the same lane
    float targetX;
    float targetY;

    if (team == Team.BLUE) {
      // Blue troops attack red towers (at top)
      targetX = isLeftLane ? arena.getRedLeftPrincessTowerX() : arena.getRedRightPrincessTowerX();
      targetY = arena.getRedLeftPrincessTowerY();
    } else {
      // Red troops attack blue towers (at bottom)
      targetX = isLeftLane ? arena.getBlueLeftPrincessTowerX() : arena.getBlueRightPrincessTowerX();
      targetY = arena.getBlueLeftPrincessTowerY();
    }

    float angle =
        pathfinder.getNextMovementAngle(
            pos, troop.getMovementType(), targetX, targetY, arena);

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
    MovementType typeA = a.getMovementType();
    MovementType typeB = b.getMovementType();

    // Air units do not collide with ground units or buildings
    if (typeA == MovementType.AIR) {
      return typeB == MovementType.AIR; // Air only collides with air
    }
    return typeB != MovementType.AIR; // Ground/building does not collide with air

    // Ground and buildings collide with each other
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

    // Calculate push ratios, lighter objects get pushed more
    // If one has mass 0 (immovable), the other gets pushed fully
    float ratioA;
    float ratioB;

    if (totalMass <= 0) {
      // Both immovable, no push
      return;
    } else if (massA <= 0) {
      // A is immovable, B gets pushed fully
      ratioA = 0;
      ratioB = 1;
    } else if (massB <= 0) {
      // B is immovable, A gets pushed fully
      ratioA = 1;
      ratioB = 0;
    } else {
      // Both movable, push proportional to inverse mass
      ratioA = massB / totalMass;
      ratioB = massA / totalMass;
    }

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
