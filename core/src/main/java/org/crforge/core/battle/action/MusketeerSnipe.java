package org.crforge.core.battle.action;

import java.util.List;
import lombok.Builder;
import lombok.Getter;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The evolved Musketeer's snipe: a run that lasts, keeps a number of rounds and, on every step,
 * looks along its unit's lane for an enemy to snipe. While it holds one it sets the unit's attack
 * sequence index to 1, the snipe entry, whose own range reaches it; while it holds none it sets 0,
 * the normal entry. The step's and the setter notice's stores go through the unit's store with the
 * targeting component's bit ignored; the last round's store asks for the component on. The shot
 * itself is the unit's own attack with the snipe entry, its projectile and its range; the run only
 * chooses the target, sets the index and counts the shots.
 *
 * <p>The run keeps its rounds, a locked mark, the target it holds and the candidate its last look
 * found. The start keeps AmmoCount rounds.
 *
 * <p><b>The step.</b> With the unit's targeting component off the run holds nothing and the index
 * is set to 0. With no rounds left the index is set to 0 and the run finishes. Otherwise the locked
 * mark is read and cleared, and the look is skipped when the unit carries LOCK_TARGET, when it
 * holds a reference under a buff that locks its target, or when its buffs scale a hit step to 0.
 * Otherwise the look keeps or finds the candidate (below). Then:
 *
 * <ul>
 *   <li>With the mark read set and the candidate the target held, the unit takes that target again
 *       through the reference setter, the index is set to 1 and the mark set again.
 *   <li>Otherwise, with no candidate, nothing is taken; with a reference the unit holds inside its
 *       row's Range plus its collision radius, the reference is kept and nothing is taken; else the
 *       unit takes the candidate, the index is set to 1, the mark is set and the candidate becomes
 *       the target held.
 * </ul>
 *
 * <p>Then the close: a step that took a target ends there, unless the unit carries LOCK_TARGET and
 * its reference is not the target held. Every other step drops the unit's reference as it stands
 * when a target is held (no setter: the reference and its pending-damage keep are cleared), holds
 * nothing and sets the index to 0.
 *
 * <p><b>The look.</b> The candidate of the last look is kept while the validator accepts it (its
 * take mode under IgnorePendingDamageTargets), it stands across the lane less than the side clip
 * plus its collision radius from the unit (LockedTargetSnipeSideClip for the target held,
 * SnipeSideClip for any other), and its squared distance from the unit is above the square of
 * SnipeMinRange plus the unit's collision radius. Otherwise the run lists the objects in a box
 * about the unit, half as wide as LockedTargetSnipeSideClip and half as long as SnipeMaxRange, that
 * pass SnipeTargetFilter for the unit's team and row, less those that stand closer than
 * SnipeMinRange: an object whose squared distance from the unit, less its own collision radius
 * squared (never below 0), is below the square of SnipeMinRange plus the unit's collision radius;
 * nearest first. The first the validator accepts and that stands across the lane less than the side
 * clip plus its radius from the unit is the candidate; with none there is no candidate.
 *
 * <p><b>The notices.</b> As one of the unit's attacks ends with a landed hit while a target is
 * held, the unit raises LOCK_TARGET for the next step and a round is spent; the round that empties
 * the run drops the unit's reference as it stands, holds nothing and sets the index to 0. As the
 * unit's reference setter stores a reference other than the target held, or none, the index is set
 * to 0. As an object leaves the battle, the run forgets it as the target held (and the candidate
 * with it) or as the candidate.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled line for line: the rounds, the step's gates, the look's keep test and its box,"
            + " filter, minimum range, validator and side clip, the take through the setter and"
            + " the index, the close and its drop, the hit notice's LOCK_TARGET and round, the"
            + " setter's notice and the leaving object. Held by tv_replay_023, whose evolved"
            + " Musketeer snipes across the lane three times and runs out of rounds, and, for a"
            + " step without a candidate, by evo_musketeer_vs_musketeer, where the enemy stands"
            + " inside the minimum range. Refused: ActionOnSnipe and ActionOnOutOfAmmo,"
            + " which no row sets. Not modelled: the crosshair and ammunition it shows, which is"
            + " presentation.")
public final class MusketeerSnipe extends RowAction {

  /** The run holds no target, or has no candidate. */
  public static final int NONE = -1;

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns the row's own columns
   */
  public MusketeerSnipe(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new Run(this, holder.getOwner(), holder.getOwner().snipeHost(this), columns.ammoCount());
  }

  /**
   * The row's own columns.
   *
   * @param ammoCount the rounds a run starts with
   * @param snipeSideClip how far across the lane, its radius added, a candidate other than the
   *     target held may stand
   * @param lockedTargetSnipeSideClip how far the target held may stand, and half the width of the
   *     box the look lists candidates in
   * @param snipeMaxRange half the length of that box
   * @param snipeMinRange how close a candidate may stand, the unit's collision radius added
   * @param snipeTargetFilter the filter the box's objects pass, asked for the unit's team and row
   * @param ignorePendingDamageTargets true to ask the validator in its take mode, which refuses a
   *     target the damage on its way will kill
   * @param actionOnSnipe the action a shot schedules, or null for none
   * @param actionOnOutOfAmmo the action the last round schedules, or null for none
   */
  @Builder
  public record Columns(
      int ammoCount,
      int snipeSideClip,
      int lockedTargetSnipeSideClip,
      int snipeMaxRange,
      int snipeMinRange,
      GameObjectFilter snipeTargetFilter,
      boolean ignorePendingDamageTargets,
      BattleAction actionOnSnipe,
      BattleAction actionOnOutOfAmmo) {}

  /** What a snipe run asks of the battle about its unit and the objects it looks at. */
  public interface Host {

    /** Whether the unit's targeting component is present and switched on. */
    boolean targetingOn();

    /** Whether the unit's tag word carries LOCK_TARGET. */
    boolean lockTargetTagged();

    /** Whether the unit holds a reference while a buff that locks its target is listed on it. */
    boolean referenceLockedByBuff();

    /** Whether the unit's buffs scale a hit step to 0, as a stun does. */
    boolean hitsStopped();

    /**
     * The objects in the box about the unit, as wide as twice the half width and as long as twice
     * the half length, that pass the filter for the unit's team and row, a building by its square
     * overlapping the box and anything else by its circle meeting it; less those standing closer
     * than the minimum range, which keeps an object only while its squared distance from the unit,
     * less its collision radius squared and never below 0, is at least the square of the minimum
     * range plus the unit's collision radius.
     *
     * @param halfWidth half the box's width
     * @param halfLength half the box's length
     * @param minimumRange the minimum range, the unit's collision radius added
     * @param filter the filter
     * @return the objects' ids, nearest first, objects at the same distance in the box's order
     */
    List<Integer> candidates(
        int halfWidth, int halfLength, int minimumRange, GameObjectFilter filter);

    /**
     * Whether the unit's validator accepts the object as its target.
     *
     * @param id the object
     * @param takeMode true for the take mode, false for the re-check
     */
    boolean validates(int id, boolean takeMode);

    /** Where the object stands across the lane. */
    int x(int id);

    /** The object's collision radius. */
    int radius(int id);

    /** Where the unit stands across the lane. */
    int x();

    /** The unit's collision radius. */
    int radius();

    /** The squared distance between the object's centre and the unit's, saturating. */
    int squaredDistance(int id);

    /** The unit's reference, or {@link #NONE} for none. */
    int reference();

    /**
     * Whether the object stands in the reach of the unit's row: its Range plus its collision
     * radius, the object's radius added, with no minimum.
     */
    boolean inRowReach(int id);

    /** The unit takes the object as its reference through the setter, with its re-check. */
    void take(int id);

    /** Drops the unit's reference as it stands: no setter, the pending-damage keep cleared. */
    void dropReference();

    /** Raises LOCK_TARGET on the unit for the next step. */
    void raiseLockTarget();
  }

  /** One run on the unit: its rounds, its mark, the target it holds and its candidate. */
  public static final class Run extends ActionInstance {

    private final MusketeerSnipe snipe;
    private final ActionOwner owner;
    private final Host host;

    /** The rounds the run has left. */
    @Getter private int rounds;

    /** Set by a step that took a target, read and cleared by the next. */
    @Getter private boolean locked;

    /** The target the run holds, or {@link #NONE}. */
    @Getter private int held = NONE;

    /** The candidate the last look found, or {@link #NONE}. */
    @Getter private int candidate = NONE;

    private Run(MusketeerSnipe snipe, ActionOwner owner, Host host, int rounds) {
      super(snipe);
      this.snipe = snipe;
      this.owner = owner;
      this.host = host;
      this.rounds = rounds;
    }

    @Override
    protected void update(ActionHolder holder) {
      if (!host.targetingOn()) {
        held = NONE;
        owner.setAttackSequenceIndex(0, true);
        return;
      }
      if (rounds <= 0) {
        owner.setAttackSequenceIndex(0, true);
        finish();
        return;
      }
      boolean wasLocked = locked;
      locked = false;
      boolean taken = false;
      if (!host.lockTargetTagged() && !host.referenceLockedByBuff() && !host.hitsStopped()) {
        look();
        if (wasLocked && held != NONE && held == candidate) {
          // The target the last step took is still the candidate: taken again.
          host.take(held);
          owner.setAttackSequenceIndex(1, true);
          taken = true;
          locked = true;
        } else if (candidate != NONE) {
          int reference = host.reference();
          // A reference in the row's own reach is kept; otherwise the candidate is taken.
          if (reference == NONE || !host.inRowReach(reference)) {
            int chosen = candidate;
            host.take(chosen);
            owner.setAttackSequenceIndex(1, true);
            taken = true;
            locked = true;
            held = chosen;
          }
        }
      }
      if (taken && (!host.lockTargetTagged() || host.reference() == held)) {
        return;
      }
      if (held != NONE) {
        host.dropReference();
      }
      held = NONE;
      owner.setAttackSequenceIndex(0, true);
    }

    /** Keeps the last candidate while it still qualifies, or lists the box for a new one. */
    private void look() {
      Columns columns = snipe.getColumns();
      if (candidate != NONE
          && host.validates(candidate, columns.ignorePendingDamageTargets())
          && acrossTheLane(candidate)) {
        int reach = columns.snipeMinRange() + host.radius();
        if (host.squaredDistance(candidate) > reach * reach) {
          return;
        }
      }
      candidate = NONE;
      for (int id :
          host.candidates(
              columns.lockedTargetSnipeSideClip(),
              columns.snipeMaxRange(),
              columns.snipeMinRange(),
              columns.snipeTargetFilter())) {
        if (host.validates(id, columns.ignorePendingDamageTargets()) && acrossTheLane(id)) {
          candidate = id;
          return;
        }
      }
    }

    /**
     * Whether the object stands across the lane less than the side clip, its radius added, from the
     * unit: the locked clip for the target held, the plain one for any other.
     */
    private boolean acrossTheLane(int id) {
      Columns columns = snipe.getColumns();
      int clip = id == held ? columns.lockedTargetSnipeSideClip() : columns.snipeSideClip();
      return Math.abs(host.x(id) - host.x()) < clip + host.radius(id);
    }

    @Override
    protected void attackEnded(ActionHolder holder) {
      if (held == NONE) {
        return;
      }
      host.raiseLockTarget();
      Columns columns = snipe.getColumns();
      if (columns.actionOnSnipe() != null) {
        throw new UnsupportedOperationException(
            snipe.name() + " schedules ActionOnSnipe on a shot, which is not modelled");
      }
      rounds--;
      if (rounds != 0) {
        return;
      }
      held = NONE;
      host.dropReference();
      owner.setAttackSequenceIndex(0, false);
      if (columns.actionOnOutOfAmmo() != null) {
        throw new UnsupportedOperationException(
            snipe.name() + " schedules ActionOnOutOfAmmo on its last round, which is not modelled");
      }
    }

    @Override
    protected void referenceStored(int referenceId) {
      if (referenceId != NONE && referenceId == held) {
        return;
      }
      owner.setAttackSequenceIndex(0, true);
    }

    @Override
    protected void objectLeft(int leftId) {
      if (held == leftId) {
        held = NONE;
        candidate = NONE;
      } else if (candidate == leftId) {
        candidate = NONE;
      }
    }
  }
}
