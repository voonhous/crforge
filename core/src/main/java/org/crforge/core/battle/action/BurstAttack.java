package org.crforge.core.battle.action;

import java.util.List;
import lombok.Builder;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.EntityFlags;

/**
 * The Dagger Duchess's charge counter: a run that lasts for as long as its tower does, spends one
 * charge on every attack that lands, recharges while the tower is not attacking and moves the
 * tower's attack sequence index to the entry that fits the charges left.
 *
 * <p>Its start fills it with the row's maximum charges. The notice that one of the owner's attacks
 * ended with a landed hit spends one charge, when there is one; the hit that spends the last one
 * marks the run depleted, but only when the row's depleted index is above 0, and every other hit
 * clears the mark.
 *
 * <p>Each step, in order:
 *
 * <ol>
 *   <li>With the owner's targeting component on and its attack timer above 0 - the tower is
 *       attacking - or with the charges full, the recharge timer is cleared.
 *   <li>Otherwise the recharge timer takes the 50 ms of a step, scaled by the owner's buffs as its
 *       attack timer is. Once it is past the row's recharge time, the charges rise by the row's
 *       increment, capped at the maximum, and the mark is cleared once they are two or more; the
 *       timer keeps what it ran past the recharge time, or is cleared when the rise reached the
 *       maximum.
 *   <li>The run carries NO_ATTACK while it holds no charge, so a tower with none cannot attack; the
 *       tag reaches the tower as the next step's tags are folded in.
 *   <li>With the targeting component on, the index is set: the row's depleted index while marked,
 *       otherwise the row's list of indices at the charges less one, capped at the list's last, and
 *       the list's first at one charge or none. The store keeps only an index below the length of
 *       the tower's order.
 * </ol>
 *
 * <p>The shipped row's list [2, 0, 1, 0, 1, 0, 1, 0] and eight charges give the entries 0, 1, 0, 1,
 * 0, 1, 0 for the first seven hits, 2 for the last charge and the depleted index 3 after it. The
 * entries differ only in how fast they step the attack timer.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the start's charges, the notice's spend and mark, the step's"
            + " attacking and full tests, the scaled recharge with its remainder, cap and mark"
            + " clearing, the NO_ATTACK tag at no charge and the index it sets from the list or"
            + " the depleted index. Held by the Dagger Duchess runs in which a Giant takes the"
            + " tower through its eight charges, its depleted hits and its recharges. Not"
            + " modelled: the charge counter it shows above the tower, which is presentation.")
public final class BurstAttack extends RowAction {

  /** The step the recharge timer takes, before the owner's buffs scale it, in milliseconds. */
  private static final int STEP_MS = 50;

  /** The fewest charges at which a recharge clears the depleted mark. */
  private static final int CHARGES_CLEARING_DEPLETION = 2;

  /**
   * The row's own columns.
   *
   * @param maxChargeCount the charges a full run holds, and the run starts with
   * @param rechargeTimeMs how long the recharge timer runs before the charges rise, in milliseconds
   * @param rechargeIncrement how many charges one recharge adds
   * @param attackSequenceIndices the index the run sets at each count of charges, from one charge
   *     up; the first also at no charge
   * @param depletedAttackSequenceIndex the index the run sets while depleted; 0 or below for a row
   *     that is never marked depleted
   */
  @Builder
  public record Columns(
      int maxChargeCount,
      int rechargeTimeMs,
      int rechargeIncrement,
      List<Integer> attackSequenceIndices,
      int depletedAttackSequenceIndex) {

    public Columns {
      attackSequenceIndices = List.copyOf(attackSequenceIndices);
    }
  }

  /** What the run reads of and does to its owner. */
  public interface Host {

    /** True while the owner's targeting component is on. */
    boolean targetingActive();

    /** The owner's attack timer, in milliseconds; above 0 while it is attacking. */
    int attackTimerMs();

    /**
     * A time step scaled by the owner's buffs, as its attack timer's is.
     *
     * @param stepMs the step before scaling, in milliseconds
     */
    int timeStep(int stepMs);

    /**
     * Stores the owner's attack sequence index, kept only below the length of its order.
     *
     * @param index the index
     */
    void setAttackSequenceIndex(int index);
  }

  private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns the row's own columns
   */
  public BurstAttack(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new Run(this, holder.getOwner().burstAttackHost(this), columns);
  }

  /** One run on a tower: its charges, its recharge timer and its depleted mark. */
  public static final class Run extends ActionInstance {

    private final Host host;
    private final Columns columns;

    /** The charges the run holds. */
    private int charges;

    /** How long the run has recharged since the charges last rose, in milliseconds. */
    private int rechargeMs;

    /** True once the last charge was spent, until a recharge brings two or more. */
    private boolean depleted;

    private Run(BattleAction action, Host host, Columns columns) {
      super(action);
      this.host = host;
      this.columns = columns;
      this.charges = columns.maxChargeCount();
    }

    /** The charges the run holds. */
    public int charges() {
      return charges;
    }

    /** True while the run is marked depleted. */
    public boolean depleted() {
      return depleted;
    }

    @Override
    protected void update(ActionHolder holder) {
      boolean active = host.targetingActive();
      boolean attacking = active && host.attackTimerMs() > 0;
      if (attacking || charges >= columns.maxChargeCount()) {
        rechargeMs = 0;
      } else {
        rechargeMs += host.timeStep(STEP_MS);
        if (rechargeMs > columns.rechargeTimeMs()) {
          int raised = charges + columns.rechargeIncrement();
          charges = Math.min(columns.maxChargeCount(), raised);
          if (charges >= CHARGES_CLEARING_DEPLETION) {
            depleted = false;
          }
          rechargeMs =
              raised < columns.maxChargeCount() ? rechargeMs - columns.rechargeTimeMs() : 0;
        }
      }
      if (charges == 0) {
        addTags(EntityFlags.NO_ATTACK);
      } else {
        clearTags(EntityFlags.NO_ATTACK);
      }
      List<Integer> indices = columns.attackSequenceIndices();
      if (!active || indices.isEmpty()) {
        return;
      }
      int index;
      if (depleted) {
        index = columns.depletedAttackSequenceIndex();
      } else {
        int at = charges > 1 ? Math.min(charges - 1, indices.size() - 1) : 0;
        index = indices.get(at);
      }
      host.setAttackSequenceIndex(index);
    }

    @Override
    protected void attackEnded(ActionHolder holder) {
      if (charges <= 0) {
        return;
      }
      charges--;
      depleted = columns.depletedAttackSequenceIndex() > 0 && charges == 0;
    }
  }
}
