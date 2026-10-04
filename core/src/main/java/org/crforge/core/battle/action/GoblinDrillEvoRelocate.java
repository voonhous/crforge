package org.crforge.core.battle.action;

import java.util.List;
import lombok.Builder;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The evolved Goblin Drill's relocation: a run on its building that hides it underground each time
 * its hit points fall to a threshold, and brings it up again a fixed number of steps further along
 * a square ring around the enemy tower it stands by.
 *
 * <p>As it starts it schedules its first-appear action on the building, and finds where the
 * building stands on the ring of one of the enemy side's towers: a square of five tiles a side
 * around the tower, walked in steps of one tile. Which way it walks is decided once: with
 * distance-based positioning, toward whichever of the two points the steps reach lies farther from
 * the enemy king, forward on a tie; without it, by the quarter of the arena the building stands in.
 *
 * <p>Each threshold in turn hides it once its share of the maximum falls to or below it: its state
 * becomes the underground one, it is untargetable for a tick, the threshold's hide action runs, and
 * its new point is chosen. While underground its spawner holds its timer; with two steps of the
 * hide time left it moves to the new point in one write, and once the hide time is over it deploys
 * again and the threshold's reappear action, when the row has one, runs. While its share is still
 * above the threshold but its hit points less the damage on its way to it are not, it is
 * untargetable for a tick.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the first-appear action, the ring point and its index, the"
            + " threshold test on the share and on the hit points less the pending damage, the"
            + " hide, the steps along the ring, the move two steps before the end, the reappear"
            + " and the spawner's hold. Held by evo_goblindrill_vs_musketeer (two hides, walking"
            + " forward). Not modelled: the hide, reappear and target effects. Refused: a"
            + " building on no ring point, the backward walk and the quarter rule, which no case"
            + " holds, the character spawns on a hide or a reappear, reappear actions, the shared"
            + " columns its run does not read, and an owner other than a building.")
public final class GoblinDrillEvoRelocate extends RowAction {

  /**
   * The row's own columns.
   *
   * @param distanceBased true to choose the walking direction by the distance to the enemy king
   * @param stepsToMove the ring steps one hide moves the building
   * @param hideTimeMs how long the building stays underground
   * @param hideHpThresholds the hit-point shares, in whole percent, that hide it, in order
   * @param firstAppearAction the action scheduled on the building as the run starts, or null
   * @param hideActions the action each hide runs, by threshold
   * @param reappearActions the action each reappearance runs, by threshold
   */
  @Builder
  public record Columns(
      boolean distanceBased,
      int stepsToMove,
      int hideTimeMs,
      List<Integer> hideHpThresholds,
      BattleAction firstAppearAction,
      List<BattleAction> hideActions,
      List<BattleAction> reappearActions) {

    public Columns {
      hideHpThresholds = List.copyOf(hideHpThresholds);
      hideActions = List.copyOf(hideActions);
      reappearActions = List.copyOf(reappearActions);
    }
  }

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public GoblinDrillEvoRelocate(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return holder.getOwner().goblinDrillRelocate(this, holder);
  }
}
