package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * Goblinstein's ability action: a run on the area effect that follows the doctor, which ties the
 * doctor to the unit made with it and makes a death area where that unit leaves.
 *
 * <p>It does nothing as it starts but list its run, which the area effect's owner makes. Its first
 * step connects to the unit the doctor is grouped with: the first of the doctor's group chain, or
 * the one after it when the doctor is the first. Every later step waits for the doctor to cast its
 * ability; the tether that would follow the cast is refused, as no reference holds it. When the
 * connected unit leaves, the run makes its death area at the unit's point, once, and holds it in
 * the unit's place; when the area effect leaves, a death area still held ends.
 *
 * <p>The tether's shape, targets, damage, hit actions and tags are columns of the row that only the
 * tether reads.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the connection on the first step, the wait for the cast, the death area on the"
            + " connected unit's leaving and its end as the owner leaves; held by goblinstein_tower"
            + " and goblinstein_doctor_first. Refused: the tether after the doctor's cast, which"
            + " no reference holds.")
public final class GoblinsteinAbility extends RowAction {

  /**
   * The row's columns the run reads before any tether.
   *
   * @param tetherDurationMs how long a tether lasts once the cast ends
   * @param deathAreaEffect the area effect made where the connected unit leaves, or null for none
   */
  public record Columns(int tetherDurationMs, String deathAreaEffect) {}

  /** The row's columns the run reads. */
  @Getter private final Columns columns;

  /**
   * @param row the row's shared columns
   * @param columns its own columns
   */
  public GoblinsteinAbility(ActionRow row, Columns columns) {
    super(row);
    this.columns = columns;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return holder.getOwner().goblinsteinAbility(this, holder.passPhase());
  }
}
