package org.crforge.core.battle.action;

import java.util.List;
import lombok.Builder;
import lombok.Getter;
import org.crforge.core.battle.filter.GameObjectFilter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * A dashing attack chain, as the Golden Knight's ability charges in data version 16.402.18: a run
 * on its character that dashes it at the object its target resolver finds, and ends once the
 * character has landed.
 *
 * <p>The start picks the first object: the resolver's objects around the character in rank order,
 * at most 32, the first the run has not hit that is alive and, with the character's targeting
 * switched on, that the targeting would take. With one the dash begins on it, with the targeting
 * switched on: the movement switched on, the character's own dash ended (its chain's count and
 * first vector cleared, its hit list emptied and its reference given up), the reference set onto
 * the object through the setter's re-check, and the dash started at the object's point, stopping
 * short by its collision radius. That dash is the character's own: a character whose row sets
 * DashCount goes on chaining from one landing to the next as its dashing state ends, exactly as
 * after its ability's dash in the earlier data version. With no object the run stays listed and
 * looks again on each step.
 *
 * <p>Each step, once the dash has begun: while the character is dashing, a dead reference is added
 * to the run's hit list; the first step it is no longer dashing, the run counts the dash as landed.
 * The landing adds the reference, if any, to the hit list, counts the link and, the count reached,
 * gives the reference up and finishes the run.
 *
 * <p>Refused as the row is built: a row of more than one dash, or none, or no resolver; a resolver
 * of more than one strategy; a row that keeps the target after the dash; the row's actions (the
 * chain's begin, reach, target-died, complete and no-target ones), which no reference holds; and
 * the stop gate and the other shared columns the run does not read.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Read line for line: the start, the pick over the ranked resolver list, the begin, the"
            + " step and the landing. Held by ability_golden_knight, tv_replay_019 and"
            + " GoldenKnightChargeTest. Refused: more than one dash, ResetTargetAfterDash cleared, the"
            + " chain's actions, more than one strategy and a stop gate.")
public final class DashingAttackChain extends RowAction {

  /** The most objects the chain's pick ranks. */
  public static final int RANKED = 32;

  /**
   * The row's own columns.
   *
   * @param resolver the target resolver's row name
   * @param filter the resolver's filter
   * @param cone the resolver's Cone shape, a Circle one as a cone that keeps every angle, or null
   *     for a Global one
   * @param strategies the resolver's strategies, as the data names them
   * @param dashCount how many dashes the chain makes
   */
  @Builder
  public record Columns(
      String resolver,
      GameObjectFilter filter,
      ConeShape cone,
      List<String> strategies,
      int dashCount) {

    /** Copies the strategies. */
    public Columns {
      strategies = strategies == null ? List.of() : List.copyOf(strategies);
    }
  }

  /** The row's own columns. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public DashingAttackChain(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return holder.getOwner().dashingAttackChain(this, holder);
  }
}
