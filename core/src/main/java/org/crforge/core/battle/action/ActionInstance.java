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
            + " singleton row's second start sends it, and the notices of an object leaving and"
            + " of an attack that landed, the owner-leave before any notice and the stop as the"
            + " entity leaves after them, which do nothing unless"
            + " the class overrides them. Not modelled: the start and on-finish hooks some classes"
            + " override.")
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

  /**
   * What the run does as an object leaves the battle. By default nothing: the run carries on as it
   * was, whatever it caused or held.
   *
   * @param leftId the id of the object that left
   */
  protected void objectLeft(int leftId) {}

  /**
   * What the run does as its entity leaves the battle, before any entity hears of it. By default
   * nothing, as the base slot does.
   *
   * @param holder the entity's holder
   */
  protected void ownerLeaving(ActionHolder holder) {}

  /**
   * What the run does as one of its entity's attacks ends with a landed hit. By default nothing, as
   * the base slot does.
   *
   * @param holder the entity's holder
   */
  protected void attackEnded(ActionHolder holder) {}

  /**
   * What the run does as its entity leaves the battle and its holder lets it go, after every notice
   * of the leaving. By default nothing, as the base slot does.
   *
   * @param holder the entity's holder
   */
  protected void stop(ActionHolder holder) {}

  /** Marks the run finished; it is removed by the next run pass. */
  protected void finish() {
    finished = true;
  }

  /** Sets the row's tags on the run as it starts, or tags the run sets itself. */
  void addTags(long more) {
    tags |= more;
  }

  /** Takes tags the run set itself off it again. */
  void clearTags(long mask) {
    tags &= ~mask;
  }
}
