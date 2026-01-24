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

  /**
   * Collision result containing push direction and overlap amount.
   */
  private record CollisionResult(float dirX, float dirY, float overlap) {}

  private void resolveCollision(Entity a, Entity b) {
    // 1. Detect collision and calculate push direction
    CollisionResult collision = detectCollision(a, b);
    if (collision == null) {
      return;
    }

    // 2. Calculate how much each entity should be pushed (based on mass)
    float[] pushRatios = calculatePushRatios(a, b);
    if (pushRatios == null) {
      return;
    }
    float ratioA = pushRatios[0];
    float ratioB = pushRatios[1];

    // 3. Apply push to separate entities
    float pushX = collision.overlap * collision.dirX;
    float pushY = collision.overlap * collision.dirY;

    if (a.getMovementType() != MovementType.BUILDING) {
      a.getPosition().add(pushX * ratioA, pushY * ratioA);
    }
    if (b.getMovementType() != MovementType.BUILDING) {
      b.getPosition().add(-pushX * ratioB, -pushY * ratioB);
    }
  }

  /**
   * Detects collision between two entities.
   * Handles circle-circle (troop vs troop) and circle-rect (troop vs building).
   *
   * @return CollisionResult with direction from B toward A, or null if no collision
   */
  private CollisionResult detectCollision(Entity a, Entity b) {
    boolean aIsBuilding = a.getMovementType() == MovementType.BUILDING;
    boolean bIsBuilding = b.getMovementType() == MovementType.BUILDING;

    if (bIsBuilding && !aIsBuilding) {
      // Troop (A) vs Building (B): circle-rectangle collision
      return detectCircleRectCollision(a.getPosition(), a.getSize() / 2f, b.getPosition(), b.getSize());
    } else if (aIsBuilding && !bIsBuilding) {
      // Building (A) vs Troop (B): flip the result direction
      CollisionResult result = detectCircleRectCollision(b.getPosition(), b.getSize() / 2f, a.getPosition(), a.getSize());
      if (result == null) return null;
      return new CollisionResult(-result.dirX, -result.dirY, result.overlap);
    } else {
      // Circle-circle collision (troop vs troop, or building vs building)
      return detectCircleCircleCollision(a.getPosition(), a.getSize() / 2f, b.getPosition(), b.getSize() / 2f);
    }
  }

  private CollisionResult detectCircleCircleCollision(Position posA, float radiusA, Position posB, float radiusB) {
    float dx = posA.getX() - posB.getX();
    float dy = posA.getY() - posB.getY();
    float dist = (float) Math.sqrt(dx * dx + dy * dy);
    float minDist = radiusA + radiusB;
    float overlap = minDist - dist;

    if (overlap <= 0) {
      return null;
    }

    // Normalize direction (from B toward A)
    if (dist > 0.001f) {
      dx /= dist;
      dy /= dist;
    } else {
      dx = 1;
      dy = 0;
    }

    return new CollisionResult(dx, dy, overlap);
  }

  private CollisionResult detectCircleRectCollision(Position circlePos, float radius, Position rectPos, float rectSize) {
    float cx = circlePos.getX();
    float cy = circlePos.getY();
    float rx = rectPos.getX();
    float ry = rectPos.getY();
    float halfSize = rectSize / 2f;

    // Find closest point on rectangle to circle center
    float closestX = clamp(cx, rx - halfSize, rx + halfSize);
    float closestY = clamp(cy, ry - halfSize, ry + halfSize);

    // Distance from circle center to closest point
    float dx = cx - closestX;
    float dy = cy - closestY;
    float distSq = dx * dx + dy * dy;

    if (distSq >= radius * radius) {
      return null; // No collision
    }

    float dist = (float) Math.sqrt(distSq);
    float overlap = radius - dist;

    // Direction from rect toward circle
    if (dist > 0.001f) {
      dx /= dist;
      dy /= dist;
    } else {
      // Circle center inside rectangle - push toward nearest edge
      float toLeft = cx - (rx - halfSize);
      float toRight = (rx + halfSize) - cx;
      float toBottom = cy - (ry - halfSize);
      float toTop = (ry + halfSize) - cy;
      float minEdgeDist = Math.min(Math.min(toLeft, toRight), Math.min(toBottom, toTop));

      if (minEdgeDist == toLeft) { dx = -1; dy = 0; }
      else if (minEdgeDist == toRight) { dx = 1; dy = 0; }
      else if (minEdgeDist == toBottom) { dx = 0; dy = -1; }
      else { dx = 0; dy = 1; }

      overlap = radius + minEdgeDist;
    }

    return new CollisionResult(dx, dy, overlap);
  }

  /**
   * Calculate push ratios based on mass. Lighter objects get pushed more.
   * Immovable objects (mass 0) don't move; the other object takes full push.
   *
   * @return [ratioA, ratioB] or null if both immovable
   */
  private float[] calculatePushRatios(Entity a, Entity b) {
    float massA = getMass(a);
    float massB = getMass(b);
    float totalMass = massA + massB;

    if (totalMass <= 0) {
      return null; // Both immovable
    } else if (massA <= 0) {
      return new float[] {0, 1}; // A immovable, B takes full push
    } else if (massB <= 0) {
      return new float[] {1, 0}; // B immovable, A takes full push
    } else {
      return new float[] {massB / totalMass, massA / totalMass};
    }
  }

  private float clamp(float value, float min, float max) {
    return Math.max(min, Math.min(max, value));
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
