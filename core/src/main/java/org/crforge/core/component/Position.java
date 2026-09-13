package org.crforge.core.component;

import lombok.Getter;
import lombok.Setter;
import org.crforge.core.util.GameUnits;

/**
 * Simulation position in integer game units (see {@link GameUnits}).
 *
 * <p>Game logic reads positions only through {@link #getX()} and {@link #getY()}, which are whole
 * game units. Continuous movement (speed times delta time) is usually a fraction of a unit per
 * tick, e.g. a raw speed of 45 moves 37.5 units per 50 ms tick. Truncating each step would silently
 * slow such units down, so {@link #move(float, float)} integrates into a fixed-point accumulator
 * with {@link #SUBUNITS_PER_UNIT} sub-units per game unit and exposes the nearest whole unit. The
 * accumulator is deterministic (pure integer arithmetic after one rounding of each step) and loses
 * at most 1/65536 of a unit per step, with no cumulative drift from truncation.
 *
 * <p>Absolute writes ({@link #set(int, int)}) discard the sub-unit remainder. Whole-unit writes
 * ({@link #add(int, int)}) and per-axis clamping ({@link #clamp(int, int, int, int)}) preserve it
 * on axes they do not clamp, so boundary enforcement does not erase in-progress movement.
 *
 * <p>Native movement integration has not been verified; this carry strategy is a simulator choice
 * that preserves the pre-migration effective speeds.
 */
public class Position {

  /** Fixed-point resolution of the movement accumulator. */
  public static final int SUBUNITS_PER_UNIT = 1 << 16;

  private static final int HALF_UNIT = SUBUNITS_PER_UNIT / 2;

  /**
   * Upper bound on the Euclidean gap between the reported whole-unit position and the internal
   * fixed-point position: half a unit on each axis, sqrt(0.5) game units overall. Per-tick "reaches
   * the destination this step" checks that compare a distance measured from rounded coordinates
   * against a fractional step size add this slack, so rounding does not delay arrival by an extra
   * tick (the snap then moves at most this much further than the exact step would).
   */
  public static final float MAX_ROUNDING_DISTANCE = (float) Math.sqrt(0.5);

  // Fixed-point coordinates: game units * SUBUNITS_PER_UNIT
  private long fixedX;
  private long fixedY;

  /** Facing angle in radians (dimensionless, not a spatial quantity). */
  @Getter @Setter private float rotation;

  public Position(int x, int y) {
    this(x, y, 0f);
  }

  public Position(int x, int y, float rotation) {
    this.fixedX = toFixed(x);
    this.fixedY = toFixed(y);
    this.rotation = rotation;
  }

  /** X coordinate in whole game units (nearest unit, ties toward positive infinity). */
  public int getX() {
    return toUnits(fixedX);
  }

  /** Y coordinate in whole game units (nearest unit, ties toward positive infinity). */
  public int getY() {
    return toUnits(fixedY);
  }

  /** Sets the position to whole game units, discarding any sub-unit movement remainder. */
  public void set(int x, int y) {
    this.fixedX = toFixed(x);
    this.fixedY = toFixed(y);
  }

  /** Copies another position's coordinates, including its sub-unit remainder. */
  public void set(Position other) {
    this.fixedX = other.fixedX;
    this.fixedY = other.fixedY;
  }

  /** Offsets the position by whole game units, preserving the sub-unit remainder. */
  public void add(int dx, int dy) {
    this.fixedX += toFixed(dx);
    this.fixedY += toFixed(dy);
  }

  /**
   * Moves by a fractional displacement in game units, accumulating the fraction so repeated small
   * steps do not lose distance. Use this for velocity integration and other float geometry.
   */
  public void move(float dx, float dy) {
    this.fixedX += Math.round((double) dx * SUBUNITS_PER_UNIT);
    this.fixedY += Math.round((double) dy * SUBUNITS_PER_UNIT);
  }

  /**
   * Clamps each axis to the inclusive game-unit range. An axis that is already inside its range is
   * left untouched (including its sub-unit remainder); a clamped axis lands exactly on the bound.
   */
  public void clamp(int minX, int maxX, int minY, int maxY) {
    if (getX() < minX) {
      fixedX = toFixed(minX);
    } else if (getX() > maxX) {
      fixedX = toFixed(maxX);
    }
    if (getY() < minY) {
      fixedY = toFixed(minY);
    } else if (getY() > maxY) {
      fixedY = toFixed(maxY);
    }
  }

  /** Squared distance to another position in game units squared (overflow-safe). */
  public long distanceSquaredTo(Position other) {
    return GameUnits.distanceSquared(getX(), getY(), other.getX(), other.getY());
  }

  /** Squared distance to a game-unit point in game units squared (overflow-safe). */
  public long distanceSquaredTo(int x, int y) {
    return GameUnits.distanceSquared(getX(), getY(), x, y);
  }

  /** Euclidean distance to another position in (fractional) game units. */
  public float distance(Position other) {
    return (float) Math.sqrt(distanceSquaredTo(other));
  }

  /** Euclidean distance to a game-unit point in (fractional) game units. */
  public float distance(int x, int y) {
    return (float) Math.sqrt(distanceSquaredTo(x, y));
  }

  /** Angle in radians from this position toward another. */
  public float angleTo(Position other) {
    return (float) Math.atan2((double) other.getY() - getY(), (double) other.getX() - getX());
  }

  public Position copy() {
    Position copy = new Position(0, 0, rotation);
    copy.set(this);
    return copy;
  }

  @Override
  public String toString() {
    return "Position(" + getX() + ", " + getY() + ")";
  }

  private static long toFixed(int units) {
    return (long) units * SUBUNITS_PER_UNIT;
  }

  private static int toUnits(long fixed) {
    return Math.toIntExact(Math.floorDiv(fixed + HALF_UNIT, SUBUNITS_PER_UNIT));
  }
}
