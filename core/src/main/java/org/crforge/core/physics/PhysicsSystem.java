package org.crforge.core.physics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.Setter;
import org.crforge.core.arena.Arena;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;

/**
 * Handles all physics interactions in the game, including movement, collision detection, collision
 * resolution, and arena boundary enforcement.
 */
public class PhysicsSystem {

  private static final float SLIDE_FACTOR = 0.5f;
  // Minimum distance to compute meaningful collision direction; below this, use default direction
  private static final float COLLISION_EPSILON = 0.001f;

  // River zone boundaries for jump detection (same as BasePathfinder)
  private static final float RIVER_Y_MIN = Arena.RIVER_Y - 1.0f; // 15.0
  private static final float RIVER_Y_MAX = Arena.RIVER_Y + 1.0f; // 17.0

  // Speed multiplier applied while a troop is jumping over the river
  private static final float JUMP_SPEED_MULTIPLIER = 4f / 3f;

  private final Arena arena;
  private final Pathfinder pathfinder;

  /**
   * Optional GameState reference for O(1) tower lookups. If set, princess tower alive checks use
   * the typed tower list instead of scanning all entities.
   */
  @Setter private GameState gameState;

  /**
   * Creates a PhysicsSystem with a specific Arena and Pathfinder.
   *
   * @param arena The game arena.
   * @param pathfinder The pathfinding strategy to use.
   */
  public PhysicsSystem(Arena arena, Pathfinder pathfinder) {
    this.arena = arena;
    this.pathfinder = pathfinder;
  }

  /**
   * Creates a PhysicsSystem with a default BasePathfinder.
   *
   * @param arena The game arena.
   */
  public PhysicsSystem(Arena arena) {
    this(arena, new BasePathfinder());
  }

  /**
   * Updates the physics state for all entities.
   *
   * @param entities All entities in the game.
   * @param deltaTime Time elapsed since last update (in seconds).
   */
  public void update(Collection<Entity> entities, float deltaTime) {
    // Build movable entity list without stream (input is already alive from cache)
    List<Entity> movableEntities = new ArrayList<>();
    for (Entity e : entities) {
      if (e.getMovementType() != MovementType.BUILDING) {
        movableEntities.add(e);
      }
    }

    // 1. Apply movement based on pathfinding
    for (Entity entity : movableEntities) {
      if (entity instanceof Troop troop) {
        applyMovement(troop, entities, deltaTime);
      }
    }

    // 2. Resolve collisions between entities
    resolveCollisions(entities);

    // 3. Keep entities within arena bounds
    for (Entity entity : movableEntities) {
      enforceBounds(entity);
    }
  }

  private void applyMovement(Troop troop, Collection<Entity> allEntities, float deltaTime) {
    // Knockback overrides all normal movement
    if (troop.getMovement().isKnockedBack()) {
      troop.getMovement().tickKnockback(troop.getPosition(), deltaTime);
      return;
    }

    if (!troop.getMovement().canMove() || troop.isDeploying()) {
      return;
    }

    // Update river jump state before pathfinding so the troop gets AIR movement type
    updateJumpState(troop);

    // Dash movement is handled by AbilitySystem -- skip normal pathfinding
    if (troop.getAbility() != null && troop.getAbility().isDashing()) {
      return;
    }

    // Don't move if already in range to attack current target
    if (troop.isInAttackRange()) {
      return;
    }

    Entity target = troop.getCombat() != null ? troop.getCombat().getCurrentTarget() : null;
    if (target != null && target.isAlive()) {
      moveTowardTarget(troop, target, deltaTime);
    } else {
      moveTowardEnemySide(troop, allEntities, deltaTime);
    }
  }

  private void moveTowardTarget(Troop troop, Entity target, float deltaTime) {
    Position pos = troop.getPosition();
    Position targetPos = target.getPosition();

    float angle =
        pathfinder.getNextMovementAngle(
            pos, troop.getMovementType(), targetPos.getX(), targetPos.getY(), arena);

    applyVelocity(troop, angle, deltaTime);
  }

  private void moveTowardEnemySide(Troop troop, Collection<Entity> allEntities, float deltaTime) {
    Position pos = troop.getPosition();
    Team team = troop.getTeam();
    Team enemyTeam = team.opposite();

    float centerX = arena.getCenterX();
    boolean isLeftLane = pos.getX() < centerX;

    float targetX;
    float targetY;

    // Check if Princess Tower in this lane is alive
    boolean princessAlive;
    if (gameState != null) {
      princessAlive = gameState.isPrincessTowerAlive(enemyTeam, isLeftLane, centerX);
    } else {
      // Fallback for tests without GameState
      princessAlive = false;
      for (Entity e : allEntities) {
        if (e.getTeam() == enemyTeam && e instanceof Tower tower && tower.isPrincessTower()) {
          boolean towerIsLeft = tower.getPosition().getX() < centerX;
          if (towerIsLeft == isLeftLane) {
            princessAlive = true;
            break;
          }
        }
      }
    }

    if (team == Team.BLUE) {
      if (princessAlive) {
        targetX = isLeftLane ? arena.getRedLeftPrincessTowerX() : arena.getRedRightPrincessTowerX();
        targetY = arena.getRedLeftPrincessTowerY();
      } else {
        targetX = arena.getRedCrownTowerX();
        targetY = arena.getRedCrownTowerY();
      }
    } else {
      if (princessAlive) {
        targetX =
            isLeftLane ? arena.getBlueLeftPrincessTowerX() : arena.getBlueRightPrincessTowerX();
        targetY = arena.getBlueLeftPrincessTowerY();
      } else {
        targetX = arena.getBlueCrownTowerX();
        targetY = arena.getBlueCrownTowerY();
      }
    }

    float angle =
        pathfinder.getNextMovementAngle(pos, troop.getMovementType(), targetX, targetY, arena);

    applyVelocity(troop, angle, deltaTime);
  }

  private void applyVelocity(Troop troop, float angle, float deltaTime) {
    float speed = troop.getMovement().getEffectiveSpeed();
    float distance = speed * deltaTime;

    float dx = (float) Math.cos(angle) * distance;
    float dy = (float) Math.sin(angle) * distance;

    troop.getPosition().add(dx, dy);
    troop.getPosition().setRotation(angle);
  }

  /**
   * Updates the jumping state for a troop based on its position relative to the river. Jump-enabled
   * troops entering the river zone outside of bridge positions will leap over, gaining AIR movement
   * type and a speed boost. The jump ends when the troop exits the river zone.
   */
  private void updateJumpState(Troop troop) {
    if (!troop.getMovement().isJumpEnabled()) {
      return;
    }

    float y = troop.getPosition().getY();
    float x = troop.getPosition().getX();

    boolean inRiverZone = y >= RIVER_Y_MIN && y <= RIVER_Y_MAX;
    boolean onBridge = isOnBridge(x);

    if (inRiverZone && !onBridge) {
      if (!troop.isJumping()) {
        troop.setJumping(true);
        troop.getMovement().setSpeedMultiplier(ModifierSource.ABILITY_JUMP, JUMP_SPEED_MULTIPLIER);
      }
    } else {
      if (troop.isJumping()) {
        troop.setJumping(false);
        troop.getMovement().clearModifiers(ModifierSource.ABILITY_JUMP);
      }
    }
  }

  /** Returns true if the given X coordinate is within a bridge's horizontal bounds. */
  private static boolean isOnBridge(float x) {
    return (x >= Arena.LEFT_BRIDGE_X && x < Arena.LEFT_BRIDGE_X + Arena.BRIDGE_WIDTH)
        || (x >= Arena.RIGHT_BRIDGE_X && x < Arena.RIGHT_BRIDGE_X + Arena.BRIDGE_WIDTH);
  }

  private void resolveCollisions(Collection<Entity> entities) {
    // Build collidable list without stream (input is already alive from cache)
    List<Entity> collidable = new ArrayList<>();
    for (Entity e : entities) {
      if (e.isTargetable()) {
        collidable.add(e);
      }
    }

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
    // Dashing entities skip collision (like reference JS "noCol" flag)
    if (isDashing(a) || isDashing(b)) {
      return false;
    }

    // Knocked-back entities skip collision
    if (isKnockedBack(a) || isKnockedBack(b)) {
      return false;
    }

    MovementType typeA = a.getMovementType();
    MovementType typeB = b.getMovementType();

    // Air units do not collide with ground units or buildings
    if (typeA == MovementType.AIR) {
      return typeB == MovementType.AIR;
    }
    return typeB != MovementType.AIR;
  }

  private boolean isDashing(Entity entity) {
    return entity instanceof Troop troop
        && troop.getAbility() != null
        && troop.getAbility().isDashing();
  }

  private boolean isKnockedBack(Entity entity) {
    return entity.getMovement() != null && entity.getMovement().isKnockedBack();
  }

  /** Collision result containing push direction and overlap amount. */
  private record CollisionResult(float dirX, float dirY, float overlap) {}

  /**
   * Resolves collision between two entities by pushing them apart. If one entity is a building,
   * applies sliding physics to the other.
   */
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

    // Apply sliding physics if colliding with a static building
    Vector2 slidingAdjustment = calculateSliding(a, b, collision);
    if (slidingAdjustment != null) {
      pushX += slidingAdjustment.x;
      pushY += slidingAdjustment.y;
    }

    // Apply position updates
    if (a.getMovementType() != MovementType.BUILDING) {
      a.getPosition().add(pushX * ratioA, pushY * ratioA);
    }
    if (b.getMovementType() != MovementType.BUILDING) {
      b.getPosition().add(-pushX * ratioB, -pushY * ratioB);
    }
  }

  /** Helper class for vector operations to keep resolveCollision clean. */
  private static class Vector2 {

    float x, y;

    Vector2(float x, float y) {
      this.x = x;
      this.y = y;
    }
  }

  /**
   * Calculates the sliding vector if one entity is a building and the other is a troop. This helps
   * troops slide around buildings instead of getting stuck.
   *
   * @return A Vector2 representing the adjustment to the push vector, or null if no sliding
   *     applies.
   */
  private Vector2 calculateSliding(Entity a, Entity b, CollisionResult collision) {
    boolean aIsBuilding = a.getMovementType() == MovementType.BUILDING;
    boolean bIsBuilding = b.getMovementType() == MovementType.BUILDING;

    // Sliding only applies when exactly one entity is a building
    if (aIsBuilding == bIsBuilding) {
      return null;
    }

    Entity mover = aIsBuilding ? b : a;

    // Normal vector pointing FROM static object TO mover
    // collision.dir points from B to A
    float normalX = bIsBuilding ? collision.dirX : -collision.dirX;
    float normalY = bIsBuilding ? collision.dirY : -collision.dirY;

    // Tangent vector (-y, x)
    float tanX = -normalY;
    float tanY = normalX;

    // Compare tangent with mover's intended direction
    float rot = mover.getPosition().getRotation();
    float moveX = (float) Math.cos(rot);
    float moveY = (float) Math.sin(rot);
    float dot = moveX * tanX + moveY * tanY;

    float slideX;
    float slideY;

    // Apply slide in the direction that matches movement
    if (dot >= 0) {
      slideX = tanX * collision.overlap * SLIDE_FACTOR;
      slideY = tanY * collision.overlap * SLIDE_FACTOR;
    } else {
      slideX = -tanX * collision.overlap * SLIDE_FACTOR;
      slideY = -tanY * collision.overlap * SLIDE_FACTOR;
    }

    // If 'a' is the mover, we add the slide directly.
    // If 'b' is the mover, we need to subtract because the final application logic subtracts the
    // push vector for 'b'.
    // (See resolveCollision: b.add(-pushX, -pushY))
    if (a == mover) {
      return new Vector2(slideX, slideY);
    } else {
      return new Vector2(-slideX, -slideY);
    }
  }

  private CollisionResult detectCollision(Entity a, Entity b) {
    return detectCircleCircleCollision(
        a.getPosition(), a.getCollisionRadius(), b.getPosition(), b.getCollisionRadius());
  }

  private CollisionResult detectCircleCircleCollision(
      Position posA, float radiusA, Position posB, float radiusB) {
    float dx = posA.getX() - posB.getX();
    float dy = posA.getY() - posB.getY();
    float distSq = dx * dx + dy * dy;
    float minDist = radiusA + radiusB;

    // Quick check with squared distance
    if (distSq >= minDist * minDist) {
      return null;
    }

    float dist = (float) Math.sqrt(distSq);
    float overlap = minDist - dist;

    // Normalize direction (from B toward A)
    if (dist > COLLISION_EPSILON) {
      dx /= dist;
      dy /= dist;
    } else {
      dx = 1;
      dy = 0;
    }

    return new CollisionResult(dx, dy, overlap);
  }

  private float[] calculatePushRatios(Entity a, Entity b) {
    float massA = getMass(a);
    float massB = getMass(b);
    float totalMass = massA + massB;

    if (totalMass <= 0) {
      return null;
    } else if (massA <= 0) {
      return new float[] {0, 1}; // A is immovable
    } else if (massB <= 0) {
      return new float[] {1, 0}; // B is immovable
    } else {
      return new float[] {massB / totalMass, massA / totalMass};
    }
  }

  private float getMass(Entity entity) {
    if (entity instanceof Troop troop) {
      return troop.getMovement().getMass();
    }
    // Buildings are effectively infinite mass for collision resolution purposes
    if (entity instanceof Building) {
      return 0;
    }
    return 1;
  }

  private void enforceBounds(Entity entity) {
    Position pos = entity.getPosition();
    // Use Collision Radius for bounds check
    float radius = entity.getCollisionRadius();

    float minX = radius;
    float maxX = Arena.WIDTH - radius;
    float minY = radius;
    float maxY = Arena.HEIGHT - radius;

    float x = Math.max(minX, Math.min(maxX, pos.getX()));
    float y = Math.max(minY, Math.min(maxY, pos.getY()));

    pos.set(x, y);
  }
}
