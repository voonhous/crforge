package org.crforge.core.pathfinding;

import lombok.Getter;
import lombok.Setter;

/**
 * The entity-level state that the grid routing, movement, targeting, spatial index and overlay code
 * reads and writes about one thing on the arena.
 *
 * <p>This holds only what belongs to the entity itself: its identity, where it is, which way it
 * faces, what state it is in and the handful of per-entity flags the other passes ask about. The
 * working state of a component - the route it is following, its push accumulators, its attack
 * timers - lives in that component's own class, not here.
 *
 * <p>Units: positions, radii and offsets are integer game units, 1000 per arena tile and 500 per
 * routing cell. Countdowns are milliseconds and advance 50 per tick at 20 ticks per second. The
 * facing pair is a vector scaled to length 256.
 *
 * <p>The class is a plain mutable holder with no behaviour, and is not thread safe.
 */
@Getter
@Setter
public class GridEntity {

  /** Identity of the entity within the match. The overlay's per-side hash folds this value in. */
  private int id;

  /** Readable name, used by fixtures and diagnostics rather than by any rule. */
  private String name;

  /** Which player the entity belongs to, 0 or 1. */
  private int side;

  /**
   * Virtual type of the entity. Characters and crown towers are type 5; the overlay builder and the
   * spatial index's type mask both filter on this value.
   */
  private int type = 5;

  /**
   * Road id (lane) the entity was assigned when it was created, 0 for none. It is written once at
   * creation and never recomputed; the cell cost and the default target selection read it.
   */
  private int lane;

  /** Position along the arena's width, in game units. */
  private int x;

  /** Position along the arena's length, in game units. */
  private int y;

  /** Height above the ground, in game units, non-zero while jumping or flying. */
  private int z;

  /**
   * Height used by the push and avoidance passes when they decide whether two entities occupy the
   * same layer. It is the entity's own height plus whatever it is attached to contributes.
   */
  private int zTotal;

  /** Facing vector, x component, scaled so the pair has length 256. */
  private int dirX;

  /** Facing vector, y component, scaled so the pair has length 256. */
  private int dirY;

  /** Copy of {@link #x} taken when the position was first written. */
  private int prevX;

  /** Copy of {@link #y} taken when the position was first written. */
  private int prevY;

  /** Copy of {@link #z} taken when the position was first written. */
  private int prevZ;

  /** Current state, one of the constants in {@link GridEntityState} (0 to 16). */
  private int state;

  /**
   * Behaviour flags, a 64-bit word. Bits up to 58 are in use, so this must stay a {@code long}: the
   * high bits carry the movement and targeting restrictions such as "may not be pushed by an ally"
   * and "may not move".
   */
  private long flags;

  /**
   * Flags requested during this tick's visits and applied afterwards, a 64-bit word like {@link
   * #flags}.
   */
  private long pendingFlags;

  /** Radius, in game units, of the circle the entity occupies for collision and for the overlay. */
  private int collisionRadius;

  /** Mass, which decides how far a push moves this entity relative to the one pushing it. */
  private int mass;

  /**
   * True for a crown tower: the king tower and the princess towers alike. Query results order crown
   * towers last, a unit notices them from farther away, and a hit on one deals the crown-tower
   * damage.
   */
  private boolean crownTower;

  /** True for a building, which stands still and occludes cells. */
  private boolean building;

  /** True for an air unit, which ignores water and ground obstacles when routing. */
  private boolean air;

  /**
   * True when the entity stamps its footprint into the routing cost overlay. Buildings occlude;
   * ordinary troops do not.
   */
  private boolean occludes;

  /**
   * True while the entity still has hit points. This is the alive answer the target validator asks
   * about a candidate, refreshed once per tick before the component passes run.
   */
  private boolean alive = true;

  /**
   * Remaining deploy time in milliseconds. While it is positive the entity is in {@link
   * GridEntityState#DEPLOYING} and its component visits are skipped.
   */
  private int deployCountdown;

  /**
   * General-purpose elapsed-time accumulator in milliseconds. The state visit adds 50 per tick,
   * except in the states selected by {@link GridEntityState#DELAY_SKIP_MASK}, where the deployment
   * delay accumulator is advanced instead.
   */
  private int delay;

  /**
   * True when the entity has an active movement component. The spatial index widens a moving
   * entity's bucket footprint by half a cell so a query still finds it after it has stepped.
   */
  private boolean movementActive;

  /**
   * Countdown in milliseconds that blocks movement while it is positive, for example the delay
   * after a dash lands. The speed budget and all three movement gates return zero while it runs.
   *
   * <p>The movement component carries its own countdown at the same position in its record; the two
   * must not be conflated. This one is the entity's.
   */
  private int blockCountdownMs;

  /**
   * A per-entity enable bit the push pass checks before it considers the entity at all, both for
   * the pushing entity and for each neighbour. Its writers are not established, so the answer is a
   * supplied one: a troop answers enabled, and an entity whose {@link #building} flag is raised
   * answers disabled, so that a tower or a building never pushes a unit standing beside it and a
   * lone unit walking past one keeps the trajectory the standard game gives it. Troop-to-troop
   * pushes are unaffected.
   */
  private boolean pushEnabled = true;

  /**
   * Byte the follower and the displacement helper write while the entity moves. Its readers are not
   * established.
   */
  private int movingMarker;

  /**
   * The tower slot: set for the king tower alone, the entity that fills its side's tower slot. The
   * validator's tower filters read it, apart from the crown-tower flag, which every tower answers.
   */
  private int kingCandidate;

  /**
   * Candidacy gate the target selector and the avoidance handler read, enabled for every entity
   * when its view is built. Its writers are not established.
   */
  private int targetable;

  /**
   * Amount by which the target selector lowers this candidate's squared distance before it ranks
   * the candidate, in squared game units. Ordinary entities answer zero. Its writers are not
   * established.
   */
  private int squaredDistanceReduction;
}
