package org.crforge.core.physics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
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
import org.crforge.core.util.GameUnits;

/**
 * Handles all physics interactions in the game, including movement, collision detection, collision
 * resolution, and arena boundary enforcement.
 */
public class PhysicsSystem {

  private static final float SLIDE_FACTOR = 0.5f;

  // River zone boundaries for jump detection in game units (same as BasePathfinder)
  private static final int RIVER_Y_MIN = (Arena.RIVER_Y - 1) * GameUnits.UNITS_PER_TILE; // 15000
  private static final int RIVER_Y_MAX = (Arena.RIVER_Y + 1) * GameUnits.UNITS_PER_TILE; // 17000

  // Bridge X extents in game units (lower bound inclusive, upper bound exclusive)
  private static final int LEFT_BRIDGE_MIN_X = Arena.LEFT_BRIDGE_X * GameUnits.UNITS_PER_TILE;
  private static final int LEFT_BRIDGE_MAX_X =
      (Arena.LEFT_BRIDGE_X + Arena.BRIDGE_WIDTH) * GameUnits.UNITS_PER_TILE;
  private static final int RIGHT_BRIDGE_MIN_X = Arena.RIGHT_BRIDGE_X * GameUnits.UNITS_PER_TILE;
  private static final int RIGHT_BRIDGE_MAX_X =
      (Arena.RIGHT_BRIDGE_X + Arena.BRIDGE_WIDTH) * GameUnits.UNITS_PER_TILE;

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
   * Troops whose position is written elsewhere. A troop this answers true for takes no part in this
   * system at all: it is not moved, it is left out of collision separation, and its position is not
   * clamped to the arena bounds. The default answers false for every troop, which is the behaviour
   * of a match in which this system owns all movement.
   */
  @Setter private Predicate<Troop> externallyManaged = troop -> false;

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
      if (e.getMovementType() != MovementType.BUILDING && !isExternallyManaged(e)) {
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
    // Attached units have their position set by AttachedUnitSystem
    if (troop.isAttached()) {
      return;
    }

    // Knockback overrides all normal movement
    if (troop.getMovement().isKnockedBack()) {
      troop.getMovement().tickKnockback(troop.getPosition(), deltaTime);
      return;
    }

    // Attack dash: lunge toward target during attack windup
    if (troop.getMovement().isAttackDashing()) {
      troop.getMovement().tickAttackDash(troop.getPosition(), deltaTime);
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

    // Tunnel movement is handled by AbilitySystem -- skip normal pathfinding
    if (troop.isTunneling()) {
      return;
    }

    // Freeze movement while waiting for returning projectile (e.g. Executioner stands still)
    // pingpongMovingShooter units are exempt -- they keep walking while the projectile is out
    if (troop.getCombat() != null
        && troop.getCombat().isReturningProjectileInFlight()
        && (troop.getCombat().getProjectileStats() == null
            || troop.getCombat().getProjectileStats().getPingpongMovingShooter() <= 0)) {
      return;
    }

    // Don't move if already in range to attack current target,
    // unless a returning projectile is in flight (pingpong shooter keeps walking while bola is out)
    if (troop.isInAttackRange()) {
      if (troop.getCombat() == null || !troop.getCombat().isReturningProjectileInFlight()) {
        return;
      }
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

    // Jump-enabled and hovering troops path straight to target (like AIR) so they reach
    // the river at any point instead of routing to bridges.
    MovementType pathfindType =
        (troop.getMovement().isJumpEnabled() || troop.getMovement().isHovering())
            ? MovementType.AIR
            : troop.getMovementType();

    float angle =
        pathfinder.getNextMovementAngle(
            pos, pathfindType, targetPos.getX(), targetPos.getY(), arena);

    applyVelocity(troop, angle, deltaTime);
  }

  private void moveTowardEnemySide(Troop troop, Collection<Entity> allEntities, float deltaTime) {
    Position pos = troop.getPosition();
    Team team = troop.getTeam();
    Team enemyTeam = team.opposite();

    int centerX = arena.getCenterX();
    boolean isLeftLane = pos.getX() < centerX;

    int targetX;
    int targetY;

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

    // Jump-enabled and hovering troops path straight to target (like AIR) so they reach
    // the river at any point instead of routing to bridges.
    MovementType pathfindType =
        (troop.getMovement().isJumpEnabled() || troop.getMovement().isHovering())
            ? MovementType.AIR
            : troop.getMovementType();

    float angle = pathfinder.getNextMovementAngle(pos, pathfindType, targetX, targetY, arena);

    applyVelocity(troop, angle, deltaTime);
  }

  private void applyVelocity(Troop troop, float angle, float deltaTime) {
    // Speed is game units per second; the per-tick step is usually fractional (e.g. 37.5 units),
    // so it is integrated through Position's fixed-point carry rather than truncated.
    float speed = troop.getMovement().getEffectiveSpeed();
    float distance = speed * deltaTime;

    float dx = (float) Math.cos(angle) * distance;
    float dy = (float) Math.sin(angle) * distance;

    troop.getPosition().move(dx, dy);
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

    int y = troop.getPosition().getY();
    int x = troop.getPosition().getX();

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

  /** Returns true if the given X coordinate (game units) is within a bridge's horizontal bounds. */
  private static boolean isOnBridge(int x) {
    return (x >= LEFT_BRIDGE_MIN_X && x < LEFT_BRIDGE_MAX_X)
        || (x >= RIGHT_BRIDGE_MIN_X && x < RIGHT_BRIDGE_MAX_X);
  }

  private void resolveCollisions(Collection<Entity> entities) {
    // Build collidable list without stream (input is already alive from cache)
    List<Entity> collidable = new ArrayList<>();
    for (Entity e : entities) {
      if (e.isTargetable() && !isExternallyManaged(e)) {
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
    // Attached units do not collide
    if (isAttached(a) || isAttached(b)) {
      return false;
    }

    // Dashing entities skip collision (like reference JS "noCol" flag)
    if (isDashing(a) || isDashing(b)) {
      return false;
    }

    // Knocked-back entities skip collision
    if (isKnockedBack(a) || isKnockedBack(b)) {
      return false;
    }

    // Invisible entities pass through everything
    if (isInvisible(a) || isInvisible(b)) {
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

  private boolean isInvisible(Entity entity) {
    return entity instanceof Troop troop && troop.isInvisible();
  }

  private boolean isAttached(Entity entity) {
    return entity instanceof Troop troop && troop.isAttached();
  }

  /**
   * Collision result containing the unit push direction and overlap amount (fractional game units).
   */
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

    // Apply position updates. Pushes are fractional game units and accumulate through the
    // position's sub-unit carry, so small separations are not rounded away.
    if (a.getMovementType() != MovementType.BUILDING) {
      a.getPosition().move(pushX * ratioA, pushY * ratioA);
    }
    if (b.getMovementType() != MovementType.BUILDING) {
      b.getPosition().move(-pushX * ratioB, -pushY * ratioB);
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
      Position posA, int radiusA, Position posB, int radiusB) {
    long dx = (long) posA.getX() - posB.getX();
    long dy = (long) posA.getY() - posB.getY();
    long distSq = dx * dx + dy * dy;
    long minDist = (long) radiusA + radiusB;

    // Exact integer check: touching circles (distance == sum of radii) do not collide
    if (distSq >= minDist * minDist) {
      return null;
    }

    double dist = Math.sqrt(distSq);
    float overlap = (float) (minDist - dist);

    // Normalize direction (from B toward A). Coincident centers (the only case where integer
    // positions give no direction; previously any distance under 0.001 tiles = 1 game unit) use a
    // fixed default direction.
    if (distSq > 0) {
      return new CollisionResult((float) (dx / dist), (float) (dy / dist), overlap);
    }
    return new CollisionResult(1f, 0f, overlap);
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

  /** True when the entity is a troop whose position is written outside this system. */
  private boolean isExternallyManaged(Entity entity) {
    return entity instanceof Troop troop && externallyManaged.test(troop);
  }

  private void enforceBounds(Entity entity) {
    // Use Collision Radius for bounds check. Only out-of-bounds axes are modified, so the sub-unit
    // movement carry of an in-bounds entity is preserved.
    int radius = entity.getCollisionRadius();
    entity
        .getPosition()
        .clamp(radius, Arena.WIDTH_UNITS - radius, radius, Arena.HEIGHT_UNITS - radius);
  }
}
