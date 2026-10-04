package org.crforge.core.battle.action;

import java.util.List;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The Skeleton Barrel's pop action, its starting action, and the evolved Skeleton Balloon's. It has
 * no perform of its own, but its class asks for a run: the run is started, listed and stepped every
 * tick while its owner stands, and does nothing; it never finishes by itself. The balloons it pops
 * as the hit points fall, their frames and their effects reach only the run's view object, so
 * nothing the battle reads changes.
 *
 * <p>As it starts the run counts the row's balloons left. A singleton row's second start
 * re-triggers the run, which drops a container: the area effect of the container list at the index
 * of the list's length less the balloons left, at the owner's point moved by that index's offsets,
 * the one along the length turned toward the enemy side, for the owner's side and level, the owner
 * its parent; then one balloon fewer is left. The evolved Skeleton Balloon's health trigger
 * re-triggers it while it lives, and its death action as it dies.
 *
 * <p>With its owner alive a container drops whatever is left. With its owner dead, one balloon left
 * drops the last container, and none left drops nothing.
 *
 * <p>Refused as the run is re-triggered, not reached by a measured case: a re-trigger with no
 * balloon left while the owner lives, whose index the run turns to the list's last and whose count
 * goes below zero, and one with two or more left as the owner is dead, which drops the row's double
 * container when it names one, and else every container left in turn. Refused as the row is built:
 * a singleton with more balloons than containers or offsets, whose index would fall outside the
 * lists.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled for the plain row, whose run is stepped doing nothing, held by"
            + " skeleton_barrel_tower and skeleton_barrel_shot_down. Settled for a singleton"
            + " row's re-trigger with balloons left while the owner lives and with one left as it"
            + " is dead, each dropping one container at its offsets, held by"
            + " evo_skeletonballoon_vs_musketeer. Refused: a re-trigger with none left while the"
            + " owner lives and one with two or more left as it is dead.")
public final class PopBalloons extends RowAction {

  /** The area effects it drops, one per balloon, in the order they drop. */
  private final List<String> containers;

  /** The area effect dropped for two or more balloons left as the owner is dead, or null. */
  private final String doubleContainer;

  /** How many balloons the run starts with. */
  private final int totalBalloons;

  /** How far along the width from the owner each container drops. */
  private final List<Integer> offsetsX;

  /** How far along the length from the owner each container drops, toward the enemy side. */
  private final List<Integer> offsetsY;

  /**
   * A row that drops no container: the Skeleton Barrel's.
   *
   * @param row the row's shared columns
   */
  public PopBalloons(ActionRow row) {
    this(row, List.of(), null, 0, List.of(), List.of());
  }

  /**
   * @param row the row's shared columns
   * @param containers the area effects it drops, one per balloon
   * @param doubleContainer the area effect dropped for two or more left as the owner is dead, or
   *     null
   * @param totalBalloons how many balloons the run starts with
   * @param offsetsX how far along the width from the owner each container drops
   * @param offsetsY how far along the length each drops, toward the enemy side
   */
  public PopBalloons(
      ActionRow row,
      List<String> containers,
      String doubleContainer,
      int totalBalloons,
      List<Integer> offsetsX,
      List<Integer> offsetsY) {
    super(row);
    this.containers = List.copyOf(containers);
    this.doubleContainer = doubleContainer;
    this.totalBalloons = totalBalloons;
    this.offsetsX = List.copyOf(offsetsX);
    this.offsetsY = List.copyOf(offsetsY);
    if (row.singleton()
        && totalBalloons > 0
        && (totalBalloons > containers.size()
            || offsetsX.size() < containers.size()
            || offsetsY.size() < containers.size())) {
      throw new UnsupportedOperationException(
          row.name()
              + " has "
              + totalBalloons
              + " balloons for "
              + containers.size()
              + " containers and "
              + offsetsX.size()
              + " / "
              + offsetsY.size()
              + " offsets, whose index would fall outside the lists; not modelled");
    }
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new ActionInstance(this) {

      /** The balloons left, counted down by each container dropped. */
      private int left = totalBalloons;

      @Override
      protected void update(ActionHolder h) {
        // Its step is the base one, which does nothing; only its view object reads the balloons.
      }

      @Override
      protected void retrigger(ActionHolder h) {
        ActionOwner owner = h.getOwner();
        if (owner.actionAlive()) {
          if (left <= 0) {
            throw new UnsupportedOperationException(
                name()
                    + " is re-triggered with no balloon left while its owner lives, which drops"
                    + " the last container again; not modelled");
          }
          drop(owner);
          return;
        }
        if (left >= 2) {
          throw new UnsupportedOperationException(
              name()
                  + " is re-triggered with "
                  + left
                  + " balloons left as its owner is dead, which drops "
                  + (doubleContainer != null
                      ? "the double container " + doubleContainer
                      : "every container left")
                  + "; not modelled");
        }
        if (left == 1) {
          drop(owner);
        }
      }

      /** Drops the container of the balloons left, at its offsets, and counts one balloon off. */
      private void drop(ActionOwner owner) {
        int index = containers.size() - left;
        owner.dropContainer(
            PopBalloons.this, containers.get(index), offsetsX.get(index), offsetsY.get(index));
        left--;
      }
    };
  }
}
