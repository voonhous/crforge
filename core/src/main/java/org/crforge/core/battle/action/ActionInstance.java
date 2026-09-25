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
            + " the row's tags the holder sets on the run as it starts, and the re-trigger a"
            + " singleton row's second start sends it. Not modelled: the start and on-finish"
            + " hooks some classes override.")
public abstract class ActionInstance {

  /** The action this is a run of. */
  @Getter private final BattleAction action;

  /** The tags the run sets on its entity while it is listed. */
  @Getter private long tags;

  /** True once the run has finished; the next run pass removes it. */
  @Getter private boolean finished;

  protected ActionInstance(BattleAction action) {
    this.action = action;
  }

  /** One step of the run, from the holder's run pass. */
  protected abstract void update(ActionHolder holder);

  /**
   * What a second start of a singleton row does to the run already listed. By default nothing: the
   * run carries on as it was.
   */
  protected void retrigger(ActionHolder holder) {}

  /** Marks the run finished; it is removed by the next run pass. */
  protected void finish() {
    finished = true;
  }

  /** Sets the row's tags on the run as it starts. */
  void addTags(long more) {
    tags |= more;
  }
}
