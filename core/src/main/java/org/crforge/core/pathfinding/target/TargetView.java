package org.crforge.core.pathfinding.target;

import lombok.Getter;
import lombok.Setter;
import org.crforge.core.pathfinding.GridEntity;

/**
 * One entity as the target selector, the validator and the range test see it.
 *
 * <p>Everything geometric is read live from the {@link GridEntity} it wraps, so a view never goes
 * stale within a tick. The handful of answers whose meaning is not documented are carried as fields
 * here with their standard answers, so a caller can vary them without touching the entity.
 *
 * <p>A view is the identity a reference is compared by: keep exactly one per entity for the whole
 * match.
 */
@Getter
@Setter
public class TargetView {

  /** The entity this view describes. */
  private final GridEntity entity;

  /** The entity's targeting columns. */
  private final TargetingConfig config;

  /** Current hit points, read only when the attacker prefers the weakest candidate. */
  private int hitPoints;

  /**
   * True while the entity carries a hit-point object at all. A candidate without one is treated as
   * having the largest possible hit points and is rejected by the validator's final step.
   */
  private boolean hitPointsPresent = true;

  /**
   * The answer the entity gives when asked whether it accepts this attacker. It is the validator's
   * last question and is not documented further; every ordinary entity answers yes.
   */
  private boolean acceptsAttacker = true;

  /**
   * Damage already on its way to this entity but not yet applied, in hit points. Zero for an entity
   * that is not about to be hit.
   */
  private int pendingDamageAmount;

  /** How long that pending damage has been on its way, in milliseconds. */
  private int pendingDamageDuration;

  /**
   * True when the entity carries a buff component the validator and the priority rule ask about.
   */
  private boolean buffComponentPresent;

  /**
   * Value the validator hands to its pending-damage comparison along with the pending amount; its
   * meaning is not documented.
   */
  private int pendingDamageKey;

  /**
   * Countdown that hides the entity from the selector while it runs, in milliseconds. A candidate
   * with a running countdown is skipped.
   */
  private int hiddenCountdownMs;

  /** Wraps an entity together with its targeting columns. */
  public TargetView(GridEntity entity, TargetingConfig config) {
    this.entity = entity;
    this.config = config;
  }

  /** Position along the arena's width, in game units. */
  public int x() {
    return entity.getX();
  }

  /** Position along the arena's length, in game units. */
  public int y() {
    return entity.getY();
  }

  /** Height above the ground, in game units. */
  public int z() {
    return entity.getZ();
  }

  /** Collision radius, in game units. */
  public int radius() {
    return entity.getCollisionRadius();
  }

  /** Identity of the entity within the match. */
  public int id() {
    return entity.getId();
  }

  /** Readable name, used by fixtures and diagnostics rather than by any rule. */
  public String name() {
    return entity.getName();
  }

  /** True while the entity still has hit points. */
  public boolean alive() {
    return entity.isAlive();
  }

  /** True for a building. */
  public boolean building() {
    return entity.isBuilding();
  }

  /** True for a king tower. Query results order king towers last. */
  public boolean king() {
    return entity.isKing();
  }

  /** True for an air unit. */
  public boolean air() {
    return entity.isAir();
  }

  /** True for a ground unit; an entity is on exactly one of the two layers. */
  public boolean ground() {
    return !entity.isAir();
  }

  /**
   * A candidate flag the validator and the selector read. The crown towers answer it and several
   * tower filters branch on it; its meaning beyond that is not documented.
   */
  public boolean towerFlag() {
    return (entity.getSlot170() & 1) != 0;
  }

  /**
   * A candidate flag the validator, the selector and the priority rule read. Ordinary entities
   * answer it; its meaning is not documented.
   */
  public boolean presenceFlag() {
    return (entity.getSlot190() & 1) != 0;
  }

  /**
   * Amount by which the selector lowers this candidate's squared distance before it ranks it, in
   * squared game units. Ordinary entities answer zero.
   */
  public int squaredDistanceReduction() {
    return entity.getSlot1d8();
  }

  /** True for a tower that spawns units. */
  public boolean summonerTower() {
    return config != null && config.isSummonerTower();
  }
}
