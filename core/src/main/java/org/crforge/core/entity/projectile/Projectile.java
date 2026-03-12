package org.crforge.core.entity.projectile;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.player.Team;

/** Projectile for ranged attacks. Travels from source to target and deals damage on hit. */
@Getter
public class Projectile {

  private static final float DEFAULT_SPEED = 15f; // Tiles per second
  private static long nextId = 1;
  private final long id;
  private final Entity source;
  private final Entity target;
  private final Team team;
  private final Position position;
  private final int damage;
  private final float aoeRadius;
  private final float speed;
  private final List<EffectStats> effects;

  /**
   * Damage reduction percentage when hitting a Crown Tower. 0 = full damage, -75 = 25% damage.
   * Formula: effectiveDamage = baseDamage * (100 + crownTowerDamagePercent) / 100
   */
  private final int crownTowerDamagePercent;

  // Position-targeted fields (for spell projectiles)
  private final float targetX;
  private final float targetY;
  private final boolean positionTargeted;

  // Non-homing: fixed landing position captured at fire time
  private float fixedTargetX;
  private float fixedTargetY;
  private boolean homing = true;

  // Advanced projectile features
  @Setter private float chainedHitRadius;
  @Setter private int chainedHitCount;
  @Setter private ProjectileStats spawnProjectile;

  // Knockback on hit
  @Setter private float pushback;
  @Setter private boolean pushbackAll;

  // Spawn area effect on impact (Heal Spirit heal zone, etc.)
  @Setter private AreaEffectStats spawnAreaEffect;

  // Origin position (for spawn projectile direction calculation)
  private final float originX;
  private final float originY;

  // Effective speed for chain sub-projectile creation
  private final float projectileSpeed;

  // Chain lightning origin entity (the entity this chain "jumps from")
  @Setter private Entity chainOrigin;

  // Piercing projectile fields: travels through enemies, hitting all in path
  @Setter private boolean piercing;
  @Getter private float piercingDirX;
  @Getter private float piercingDirY;
  private float piercingRange;
  private float distanceTraveled;
  private Set<Long> hitEntities;
  @Setter private boolean aoeToGround;
  @Setter private boolean aoeToAir;

  private boolean active;
  private boolean hit;

  /** Entity-targeted projectile (ranged unit attacks) with crown tower damage modifier. */
  public Projectile(
      Entity source,
      Entity target,
      int damage,
      float aoeRadius,
      float speed,
      List<EffectStats> effects,
      int crownTowerDamagePercent) {
    this.id = nextId++;
    this.source = source;
    this.target = target;
    this.team = source.getTeam();
    this.position = new Position(source.getPosition().getX(), source.getPosition().getY());
    this.damage = damage;
    this.aoeRadius = aoeRadius;
    this.speed = speed > 0 ? speed : DEFAULT_SPEED;
    this.effects = effects != null ? effects : Collections.emptyList();
    this.crownTowerDamagePercent = crownTowerDamagePercent;
    this.targetX = 0;
    this.targetY = 0;
    this.positionTargeted = false;
    this.originX = source.getPosition().getX();
    this.originY = source.getPosition().getY();
    this.projectileSpeed = this.speed;
    this.active = true;
    this.hit = false;
  }

  /** Entity-targeted projectile without crown tower damage modifier (convenience). */
  public Projectile(
      Entity source,
      Entity target,
      int damage,
      float aoeRadius,
      float speed,
      List<EffectStats> effects) {
    this(source, target, damage, aoeRadius, speed, effects, 0);
  }

  /** Position-targeted projectile with crown tower damage modifier (spell projectiles). */
  public Projectile(
      Team team,
      float startX,
      float startY,
      float destX,
      float destY,
      int damage,
      float aoeRadius,
      float speed,
      List<EffectStats> effects,
      int crownTowerDamagePercent) {
    this.id = nextId++;
    this.source = null;
    this.target = null;
    this.team = team;
    this.position = new Position(startX, startY);
    this.damage = damage;
    this.aoeRadius = aoeRadius;
    this.speed = speed > 0 ? speed : DEFAULT_SPEED;
    this.effects = effects != null ? effects : Collections.emptyList();
    this.crownTowerDamagePercent = crownTowerDamagePercent;
    this.targetX = destX;
    this.targetY = destY;
    this.positionTargeted = true;
    this.originX = startX;
    this.originY = startY;
    this.projectileSpeed = this.speed;
    this.active = true;
    this.hit = false;
  }

  /** Position-targeted projectile without crown tower damage modifier (convenience). */
  public Projectile(
      Team team,
      float startX,
      float startY,
      float destX,
      float destY,
      int damage,
      float aoeRadius,
      float speed,
      List<EffectStats> effects) {
    this(team, startX, startY, destX, destY, damage, aoeRadius, speed, effects, 0);
  }

  public Projectile(Entity source, Entity target, int damage, float aoeRadius) {
    this(source, target, damage, aoeRadius, DEFAULT_SPEED, Collections.emptyList());
  }

  public Projectile(Entity source, Entity target, int damage) {
    this(source, target, damage, 0, DEFAULT_SPEED, Collections.emptyList());
  }

  /**
   * Sets whether this projectile homes toward its target's current position. When set to false,
   * captures the target's current position as a fixed landing point. Non-homing projectiles fly to
   * where the target was when fired and deal AOE damage at that point.
   */
  public void setHoming(boolean homing) {
    this.homing = homing;
    if (!homing && target != null) {
      this.fixedTargetX = target.getPosition().getX();
      this.fixedTargetY = target.getPosition().getY();
    }
  }

  public static void resetIdCounter() {
    nextId = 1;
  }

  /** Update projectile position. Returns true if projectile reached target. */
  public boolean update(float deltaTime) {
    if (!active) {
      return false;
    }
    if (piercing) {
      return updatePiercing(deltaTime);
    }
    return positionTargeted ? updatePositionTargeted(deltaTime) : updateEntityTargeted(deltaTime);
  }

  private boolean updateEntityTargeted(float deltaTime) {
    // Non-homing projectiles fly to the fixed position captured at fire time
    if (!homing) {
      return updateFixedTarget(deltaTime);
    }

    // If target is dead, homing projectile disappears
    if (target == null || !target.isAlive()) {
      active = false;
      return false;
    }

    Position targetPos = target.getPosition();
    float dx = targetPos.getX() - position.getX();
    float dy = targetPos.getY() - position.getY();
    float distance = position.distanceTo(targetPos);

    float moveDistance = speed * deltaTime;

    // Use Collision Radius for hit check
    if (distance <= moveDistance || distance <= target.getCollisionRadius()) {
      // Reached target
      hit = true;
      active = false;
      return true;
    }

    // Move toward target
    float ratio = moveDistance / distance;
    position.add(dx * ratio, dy * ratio);

    // Update rotation to face target
    position.setRotation((float) Math.atan2(dy, dx));

    return false;
  }

  /** Non-homing entity-targeted: fly to the fixed position captured at fire time. */
  private boolean updateFixedTarget(float deltaTime) {
    float dx = fixedTargetX - position.getX();
    float dy = fixedTargetY - position.getY();
    float distance = position.distanceTo(fixedTargetX, fixedTargetY);

    float moveDistance = speed * deltaTime;

    if (distance <= moveDistance) {
      position.set(fixedTargetX, fixedTargetY);
      hit = true;
      active = false;
      return true;
    }

    float ratio = moveDistance / distance;
    position.add(dx * ratio, dy * ratio);

    position.setRotation((float) Math.atan2(dy, dx));

    return false;
  }

  private boolean updatePositionTargeted(float deltaTime) {
    float dx = targetX - position.getX();
    float dy = targetY - position.getY();
    float distance = position.distanceTo(targetX, targetY);

    float moveDistance = speed * deltaTime;

    if (distance <= moveDistance) {
      // Snap to target position
      position.set(targetX, targetY);
      hit = true;
      active = false;
      return true;
    }

    // Move toward target position
    float ratio = moveDistance / distance;
    position.add(dx * ratio, dy * ratio);

    // Update rotation to face target
    position.setRotation((float) Math.atan2(dy, dx));

    return false;
  }

  /**
   * Configures this projectile for piercing travel. The projectile moves in a fixed direction,
   * passing through enemies and hitting all in its path until it reaches the specified range.
   */
  public void configurePiercing(
      float dirX, float dirY, float range, boolean aoeToGround, boolean aoeToAir) {
    this.piercing = true;
    this.piercingDirX = dirX;
    this.piercingDirY = dirY;
    this.piercingRange = range;
    this.distanceTraveled = 0f;
    this.hitEntities = new HashSet<>();
    this.aoeToGround = aoeToGround;
    this.aoeToAir = aoeToAir;
  }

  /** Returns true if the given entity has already been hit by this piercing projectile. */
  public boolean hasHitEntity(long entityId) {
    return hitEntities != null && hitEntities.contains(entityId);
  }

  /** Records that the given entity has been hit by this piercing projectile. */
  public void recordHitEntity(long entityId) {
    if (hitEntities != null) {
      hitEntities.add(entityId);
    }
  }

  /**
   * Updates a piercing projectile: moves along its fixed direction and deactivates when the total
   * distance traveled exceeds piercingRange. Hit detection is handled externally by CombatSystem.
   */
  private boolean updatePiercing(float deltaTime) {
    float moveDistance = speed * deltaTime;
    position.add(piercingDirX * moveDistance, piercingDirY * moveDistance);
    distanceTraveled += moveDistance;

    if (distanceTraveled >= piercingRange) {
      active = false;
    }

    // Piercing projectiles never "hit" in the traditional sense -- CombatSystem handles per-tick hits
    return false;
  }

  public boolean hasAoe() {
    return aoeRadius > 0;
  }

  public boolean hasEffects() {
    return !effects.isEmpty();
  }
}
