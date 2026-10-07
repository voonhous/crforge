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

  /** The entity's targeting columns: its row's, a new row's once the entity takes one. */
  private TargetingConfig config;

  /** Current hit points, read only when the attacker prefers the weakest candidate. */
  private int hitPoints;

  /**
   * True while the entity carries a hit-point object at all. A candidate without one is treated as
   * having the largest possible hit points and is rejected by the validator's final step.
   */
  private boolean hitPointsPresent = true;

  /**
   * The entity's own answer to whether an asker may select, hit or buff it: the validator's last
   * question, asked with the asker and with the flag of an area's query.
   */
  @FunctionalInterface
  public interface Acceptance {

    /**
     * @param asker the entity that asks: a character or tower (type 5), a projectile (type 4) or an
     *     area effect (type 3); null for none
     * @param areaQuery true when an area's damage asks about one of its victims
     * @return true when the asker may take the entity
     */
    boolean accepts(GridEntity asker, boolean areaQuery);
  }

  /** The entity's answer to an asker; every ordinary entity accepts every asker. */
  private Acceptance acceptance = (asker, areaQuery) -> true;

  /**
   * Whether the entity accepts an asker, asked the way the validator asks it: with the asker and
   * the acceptance flag of the call it is deciding, which an area's damage sets.
   *
   * @param asker the entity that asks, or null for none
   * @param acceptanceFlag the flag the validator was called with
   */
  public boolean acceptsAttacker(GridEntity asker, boolean acceptanceFlag) {
    return acceptance.accepts(asker, acceptanceFlag);
  }

  /**
   * Gives the entity one answer for every asker.
   *
   * @param accepts true to accept every asker, false to refuse every one
   */
  public void setAcceptsAttacker(boolean accepts) {
    this.acceptance = (asker, areaQuery) -> accepts;
  }

  /** Damage already on its way to the entity but not yet landed; see {@link GridEntity}. */
  public int getPendingDamageAmount() {
    return entity.getPendingDamageAmount();
  }

  /** How long the pending damage still has to fly, in milliseconds; see {@link GridEntity}. */
  public int getPendingDamageDuration() {
    return entity.getPendingDamageDurationMs();
  }

  /**
   * True when the entity carries a buff component the validator and the priority rule ask about.
   */
  private boolean buffComponentPresent;

  /**
   * The level the validator hands to its pending-damage comparison along with the pending amount:
   * the entity's packed level, at which the comparison reads its row's full hit points.
   */
  private int pendingDamageKey;

  /**
   * Countdown that hides the entity from the selector while it runs, in milliseconds. A candidate
   * with a running countdown is skipped.
   */
  private int hiddenCountdownMs;

  /**
   * The entity's own answer to "is my damage the crown-tower damage", which the direct hit asks
   * before it chooses which of the two damages to deal. A crown tower answers yes; the answer is
   * the entity's, not a configuration column, and what else would answer yes is not documented.
   */
  private boolean crownTowerTarget;

  /**
   * The entity's own answer to "do I count as a summoner tower", which the validator's
   * do-not-target-towers and target-only-towers rules read. It is a per-entity answer and not the
   * configuration column of the same name, although an ordinary tower answers both the same way;
   * what would make them differ is not documented.
   */
  private boolean summonerTowerEntity;

  /** Wraps an entity together with its targeting columns. */
  public TargetView(GridEntity entity, TargetingConfig config) {
    this.entity = entity;
    this.config = config;
    this.summonerTowerEntity = config != null && config.isSummonerTower();
  }

  /** Position along the arena's width, in game units. */
  public int x() {
    return entity.getX();
  }

  /** Position along the arena's length, in game units. */
  public int y() {
    return entity.getY();
  }

  /** Live height above the ground, in game units: the height and its offset together. */
  public int z() {
    return entity.getZ() + entity.getHeightOffset();
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

  /**
   * True for a crown tower, the king tower and the princess towers alike. Query results order them
   * last and the selector notices them from farther away.
   */
  public boolean crownTower() {
    return entity.isCrownTower();
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
   * The tower slot answer: true for the king tower alone, the entity that fills its side's tower
   * slot. The validator's tower filters branch on it; the crown-tower flag, which every tower
   * answers, is a different question.
   */
  public boolean towerFlag() {
    return (entity.getKingCandidate() & 1) != 0;
  }

  /**
   * A candidate flag the validator, the selector and the priority rule read. Ordinary entities
   * answer it; its meaning is not documented.
   */
  public boolean presenceFlag() {
    return (entity.getTargetable() & 1) != 0;
  }

  /**
   * Amount by which the selector lowers this candidate's squared distance before it ranks it, in
   * squared game units. Ordinary entities answer zero.
   */
  public int squaredDistanceReduction() {
    return entity.getSquaredDistanceReduction();
  }

  /**
   * The configuration column that marks a tower which spawns units. The elixir-drain rule reads
   * this column; the two tower-restriction rules read {@link #isSummonerTowerEntity()} instead.
   */
  public boolean summonerTowerColumn() {
    return config != null && config.isSummonerTower();
  }
}
