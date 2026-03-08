package org.crforge.core.entity.projectile;

import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.player.Team;

/**
 * Projectile for ranged attacks. Travels from source to target and deals damage on hit.
 */
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

  // Advanced projectile features
  @Setter
  private float chainedHitRadius;
  @Setter
  private int chainedHitCount;
  @Setter
  private ProjectileStats spawnProjectile;

  // Origin position (for spawn projectile direction calculation)
  private final float originX;
  private final float originY;

  // Effective speed for chain sub-projectile creation
  private final float projectileSpeed;

  private boolean active;
  private boolean hit;

  /**
   * Entity-targeted projectile (ranged unit attacks) with crown tower damage modifier.
   */
  public Projectile(Entity source, Entity target, int damage, float aoeRadius, float speed,
      List<EffectStats> effects, int crownTowerDamagePercent) {
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

  /**
   * Entity-targeted projectile without crown tower damage modifier (convenience).
   */
  public Projectile(Entity source, Entity target, int damage, float aoeRadius, float speed,
      List<EffectStats> effects) {
    this(source, target, damage, aoeRadius, speed, effects, 0);
  }

  /**
   * Position-targeted projectile (spell projectiles like Fireball/Arrows).
   */
  public Projectile(Team team, float startX, float startY, float destX, float destY,
      int damage, float aoeRadius, float speed, List<EffectStats> effects) {
    this.id = nextId++;
    this.source = null;
    this.target = null;
    this.team = team;
    this.position = new Position(startX, startY);
    this.damage = damage;
    this.aoeRadius = aoeRadius;
    this.speed = speed > 0 ? speed : DEFAULT_SPEED;
    this.effects = effects != null ? effects : Collections.emptyList();
    this.crownTowerDamagePercent = 0;
    this.targetX = destX;
    this.targetY = destY;
    this.positionTargeted = true;
    this.originX = startX;
    this.originY = startY;
    this.projectileSpeed = this.speed;
    this.active = true;
    this.hit = false;
  }

  public Projectile(Entity source, Entity target, int damage, float aoeRadius) {
    this(source, target, damage, aoeRadius, DEFAULT_SPEED, Collections.emptyList());
  }

  public Projectile(Entity source, Entity target, int damage) {
    this(source, target, damage, 0, DEFAULT_SPEED, Collections.emptyList());
  }

  public static void resetIdCounter() {
    nextId = 1;
  }

  /**
   * Update projectile position. Returns true if projectile reached target.
   */
  public boolean update(float deltaTime) {
    if (!active) {
      return false;
    }
    return positionTargeted ? updatePositionTargeted(deltaTime) : updateEntityTargeted(deltaTime);
  }

  private boolean updateEntityTargeted(float deltaTime) {
    // If target is dead, projectile disappears (for homing projectiles)
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

  public boolean hasAoe() {
    return aoeRadius > 0;
  }

  public boolean hasEffects() {
    return !effects.isEmpty();
  }
}
