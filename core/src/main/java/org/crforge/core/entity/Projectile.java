package org.crforge.core.entity;

import lombok.Getter;
import org.crforge.core.card.EffectStats;
import org.crforge.core.component.Position;
import org.crforge.core.player.Team;
import java.util.List;
import java.util.Collections;

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

  private boolean active;
  private boolean hit;

  public Projectile(Entity source, Entity target, int damage, float aoeRadius, float speed, List<EffectStats> effects) {
    this.id = nextId++;
    this.source = source;
    this.target = target;
    this.team = source.getTeam();
    this.position = new Position(source.getPosition().getX(), source.getPosition().getY());
    this.damage = damage;
    this.aoeRadius = aoeRadius;
    this.speed = speed > 0 ? speed : DEFAULT_SPEED;
    this.effects = effects != null ? effects : Collections.emptyList();
    this.active = true;
    this.hit = false;
  }

  public Projectile(Entity source, Entity target, int damage, float aoeRadius, List<EffectStats> effects) {
    this(source, target, damage, aoeRadius, DEFAULT_SPEED, effects);
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

    // If target is dead, projectile disappears (for homing projectiles)
    if (target == null || !target.isAlive()) {
      active = false;
      return false;
    }

    Position targetPos = target.getPosition();
    float dx = targetPos.getX() - position.getX();
    float dy = targetPos.getY() - position.getY();
    float distance = (float) Math.sqrt(dx * dx + dy * dy);

    float moveDistance = speed * deltaTime;

    if (distance <= moveDistance || distance <= target.getSize() / 2f) {
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

  public boolean hasAoe() {
    return aoeRadius > 0;
  }

  public boolean hasEffects() {
    return !effects.isEmpty();
  }
}
