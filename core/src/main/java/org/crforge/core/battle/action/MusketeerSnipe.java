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
 * the normal entry. Both stores go through the unit's store with the targeting component's bit
 * ignored.
 *
 * <p>The start keeps AmmoCount rounds. A step with no rounds left sets the index to 0 and finishes
 * the run. Otherwise the step looks for candidates: the objects in a box about the unit, half as
 * wide as LockedTargetSnipeSideClip and half as long as SnipeMaxRange, that pass SnipeTargetFilter
 * for the unit's team and row, less those that stand closer than SnipeMinRange: an object whose
 * squared distance from the unit, less its own collision radius squared (never below 0), is below
 * the square of SnipeMinRange plus the unit's collision radius. With no candidate the step holds no
 * target and sets the index to 0.
 *
 * <p>A candidate is refused: which one the run takes (the nearest that the target validator keeps
 * and that stands within SnipeSideClip of the unit's line), the target it then gives the unit, the
 * locked shot, the round each shot spends and the actions it schedules are not modelled. So is
 * every step that would skip the look: the refusal is made on any candidate, whether or not the
 * step would have looked for one.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the rounds the start keeps, the finish once none is left, the box, filter and"
            + " minimum range the look applies, and the index set to 0 on a step without a"
            + " candidate; held by evo_musketeer_vs_musketeer, where the enemy stands inside the"
            + " minimum range. Refused: any candidate, so the choice of target, the locked shot,"
            + " the rounds it spends and its two actions are not modelled.")
public final class MusketeerSnipe extends RowAction {

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
    return new Run(this, holder.getOwner(), columns.ammoCount());
  }

  /**
   * The row's own columns.
   *
   * @param ammoCount the rounds a run starts with
   * @param lockedTargetSnipeSideClip half the width of the box the look lists candidates in
   * @param snipeMaxRange half the length of that box
   * @param snipeMinRange how close a candidate may stand, the unit's collision radius added
   * @param snipeTargetFilter the filter the box's objects pass, asked for the unit's team and row
   */
  @Builder
  public record Columns(
      int ammoCount,
      int lockedTargetSnipeSideClip,
      int snipeMaxRange,
      int snipeMinRange,
      GameObjectFilter snipeTargetFilter) {}

  /** One run on the unit: the rounds it has left. */
  public static final class Run extends ActionInstance {

    private final MusketeerSnipe snipe;
    private final ActionOwner owner;

    /** The rounds the run has left. */
    @Getter private final int rounds;

    private Run(MusketeerSnipe snipe, ActionOwner owner, int rounds) {
      super(snipe);
      this.snipe = snipe;
      this.owner = owner;
      this.rounds = rounds;
    }

    @Override
    protected void update(ActionHolder holder) {
      if (rounds <= 0) {
        owner.setAttackSequenceIndex(0, true);
        finish();
        return;
      }
      Columns columns = snipe.getColumns();
      List<Integer> candidates =
          owner.snipeCandidates(
              columns.lockedTargetSnipeSideClip(),
              columns.snipeMaxRange(),
              columns.snipeMinRange(),
              columns.snipeTargetFilter());
      if (!candidates.isEmpty()) {
        throw new UnsupportedOperationException(
            snipe.name()
                + " finds a snipe target, object "
                + candidates.get(0)
                + ", whose shot is not modelled");
      }
      // No target held: the normal entry.
      owner.setAttackSequenceIndex(0, true);
    }
  }
}
