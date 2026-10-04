package org.crforge.core.pathfinding;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The bits of {@link GridEntity#getFlags()} that the routing, movement, push, targeting and state
 * passes read, and the same bits as they are requested on {@link GridEntity#getPendingFlags()}.
 *
 * <p>The word is 64 bits wide and bits up to 58 are in use, so every constant here is a {@code
 * long}. Each bit is defined exactly once, in this class: the passes that read a bit share the
 * definition rather than repeating it, so a bit can never end up with two names.
 *
 * <p>The names describe what each bit does where it is read. Bits with no reader on the paths these
 * packages cover are not listed.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "The flag bits the ported passes read are settled. Nothing here sets a flag, so"
            + " every flag-gated branch runs with the flag clear.")
public final class EntityFlags {

  private EntityFlags() {
    // Constants holder
  }

  /**
   * The entity is running a dash. The targeting visit raises it on the pending flags in state 3.
   */
  public static final long DASHING = 1L << 2;

  /** Set on the entity while its charge is complete. */
  public static final long CHARGING = 1L << 3;

  /**
   * The entity attacked on the step before: a hit not cancelled for distance raises it on the
   * pending flags, so it lasts one step.
   */
  public static final long ATTACKING = 1L << 5;

  /** Movement is forbidden outright. */
  public static final long NO_MOVE = 1L << 6;

  /** The entity may not start a dash. */
  public static final long NO_DASH = 1L << 8;

  /** The entity may not attack at all. */
  public static final long NO_ATTACK = 1L << 10;

  /** The entity keeps the reference it has and the selector returns without choosing. */
  public static final long LOCK_TARGET = 1L << 12;

  /** Collision handling, and with it pushing, is disabled for this entity outright. */
  public static final long NO_CHECK_COLLISIONS = 1L << 14;

  /** Avoidance is disabled for this entity outright. */
  public static final long NO_CHECK_AVOIDANCE = 1L << 15;

  /** The entity may not be pushed by the other side. */
  public static final long NO_PUSHED_BY_ENEMY = 1L << 17;

  /** The entity is hidden: no attacker takes it and the damage entry refuses it. */
  public static final long HIDDEN = 1L << 24;

  /** The entity's ability may not be cast at all. */
  public static final long ABILITY_DISABLED = 1L << 25;

  /** A building the entity's death spawns looks for a free location. */
  public static final long BUILDING_DEATH_SPAWN_FIND_LOCATION = 1L << 26;

  /** The entity's ability waits: a request leaves it pending. */
  public static final long ABILITY_POSTPONED = 1L << 29;

  /**
   * The entity's spawner may not summon: raised on a unit a capture without a buff holds, and on
   * the evolved Goblin Drill while it is underground. The spawner holds its timer while it is up; a
   * firing already due still fires.
   */
  public static final long NO_SUMMON = 1L << 31;

  /** The entity may not use its special attack. */
  public static final long NO_SPECIAL_ATTACK = 1L << 35;

  /**
   * The entity holds a capture: raised for one step on a capturing character as a drag completes (a
   * capturing projectile keeps no tag word).
   */
  public static final long HAS_CAPTURE = 1L << 37;

  /** A reflecting unit does not reflect the entity's attack. */
  public static final long NO_REFLECTED_ATTACK = 1L << 38;

  /** The entity refuses a pushback unless the request lifts the gates. */
  public static final long NO_PUSHBACK = 1L << 41;

  /** The entity takes no damage: the damage entry and a typed hit's pipeline answer zero. */
  public static final long NO_DAMAGE = 1L << 42;

  /**
   * A Giant Buffer's or a Chef's friendly filter passes the entity by: no battle code reads the bit
   * itself, only a filter whose excluded tags name it.
   */
  public static final long NO_GIANTBUFFER_CHEF_ENCHANTMENT = 1L << 44;

  /**
   * The entity is a ground unit to every reader of its layer, an air one's height pulled to 0, as
   * an air-to-ground run holds it; with FORCE_IS_AIR too, its live height decides.
   */
  public static final long FORCE_IS_GROUND = 1L << 40;

  /**
   * The entity is an air unit to every reader of its layer, as a knock lifts it; with
   * FORCE_IS_GROUND too, its live height decides.
   */
  public static final long FORCE_IS_AIR = 1L << 45;

  /** The entity has been captured by the other side. */
  public static final long CAPTURED = 1L << 46;

  /** The entity takes no part in physical interaction with other objects. */
  public static final long DISABLE_PHYSICAL = 1L << 47;

  /** The entity is casting an ability. */
  public static final long CASTING_ABILITY = 1L << 51;

  /** Nothing may take this entity as a target. */
  public static final long UNTARGETABLE = 1L << 52;

  /** The entity may not be pushed by its own side. */
  public static final long NO_PUSHED_BY_ALLY = 1L << 53;

  /** The entity is treated as an obstacle to steer around rather than a body to push. */
  public static final long AVOIDANCE_AS_OBSTACLE = 1L << 54;

  /** The entity's ability cooldown is held. */
  public static final long ABILITY_COOLDOWN_PAUSED = 1L << 55;

  /** A Clone passes the entity by. */
  public static final long NO_CLONE = 1L << 57;

  /** Movement is forbidden except when the entity is pulled by something else. */
  public static final long NO_MOVE_ALLOW_ATTRACT = 1L << 58;
}
