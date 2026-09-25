package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * One run of an action that lasts. The holder's run pass steps it once per tick. An instance that
 * finishes during its own step stays listed, and keeps contributing its tags, until the next run
 * pass removes it.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the finished flag a step sets, the removal it leads to at the next run pass,"
            + " and the tags of the row set on the run. Not modelled: the start, re-trigger,"
            + " force-stop and on-finish hooks some classes override.")
public abstract class ActionInstance {

  /** The action this is a run of. */
  @Getter private final BattleAction action;

  /** The tags the run sets on its entity while it is listed. */
  @Getter private final long tags;

  /** True once the run has finished; the next run pass removes it. */
  @Getter private boolean finished;

  protected ActionInstance(BattleAction action, long tags) {
    this.action = action;
    this.tags = tags;
  }

  /** One step of the run, from the holder's run pass. */
  protected abstract void update(ActionHolder holder);

  /** Marks the run finished; it is removed by the next run pass. */
  protected void finish() {
    finished = true;
  }
}
