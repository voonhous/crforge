package org.crforge.core.battle.action;

import lombok.Getter;
import org.crforge.core.battle.BattleEntity;
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
            + " of an attack that landed, the notice of a character its entity's spawner made, the"
            + " owner-leave before any notice and the stop as the"
            + " entity leaves after them, and the notice of an ability its player paid for, which"
            + " do nothing unless"
            + " the class overrides them. Not modelled: the start and on-finish hooks some classes"
            + " override.")
public abstract class ActionInstance {

  /** The action this is a run of. */
  @Getter private final BattleAction action;

  /** The tags the run sets on its entity while it is listed. */
  @Getter private long tags;

  /** True once the run has finished; the next run pass removes it. */
  @Getter private boolean finished;

  /**
   * The context the run's start carried, or null for none: the stop gate is asked with it, and a
   * run that schedules actions as it steps may hand it on.
   */
  private ActionContext context;

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
   * What a second start of a singleton row, caused by an entity, does to the run already listed. By
   * default what {@link #retrigger(ActionHolder)} does, whatever caused it.
   *
   * @param holder the holder the run is listed on
   * @param instigator the holder of the entity that caused the second start, or null for none
   */
  protected void retrigger(ActionHolder holder, ActionHolder instigator) {
    retrigger(holder);
  }

  /**
   * Whether a second start of a singleton row, caused by the given entity, is the same run as this
   * one. By default any start of the row is: only the row is compared, as the base slot does. A
   * class whose runs are told apart by what caused them answers for itself.
   *
   * @param instigator the holder of the entity that caused the start, or null for none
   */
  public boolean sameRun(ActionHolder instigator) {
    return true;
  }

  /**
   * What the run does as an object leaves the battle. By default nothing: the run carries on as it
   * was, whatever it caused or held.
   *
   * @param leftId the id of the object that left
   */
  protected void objectLeft(int leftId) {}

  /**
   * What the run does as its entity's spawner makes a character. By default nothing, as the base
   * slot does; only a run listening for destroyed objects answers it.
   *
   * @param childId the id of the character made
   */
  protected void childSpawned(int childId) {}

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
   * What the run makes of a buff about to be applied to its entity, before any gate of the apply.
   * By default the buff passes unchanged, as the base slot does.
   *
   * @param buff the buff's row name
   * @return the buff to apply in its place
   */
  protected String offeredBuff(String buff) {
    return buff;
  }

  /**
   * What the run does as a hit reaches its entity's damage entry, after the two sides' percentages
   * and before the bookkeeping. By default nothing, as the base slot does; only a counter answers
   * it, and may change the hit's amount.
   *
   * @param holder the entity's holder
   * @param hit the hit
   */
  protected void damageHeard(ActionHolder holder, DamageHeard hit) {}

  /**
   * What the run does as its entity's player pays for a unit's ability. By default nothing, as the
   * base slot does; only a champion slot answers it.
   *
   * @param holder the entity's holder
   * @param unit the unit whose ability was paid for
   */
  protected void abilityPaid(ActionHolder holder, BattleEntity unit) {}

  /**
   * What the run does as its entity leaves the battle and its holder lets it go, after every notice
   * of the leaving. By default nothing, as the base slot does.
   *
   * @param holder the entity's holder
   */
  protected void stop(ActionHolder holder) {}

  /** The context the run's start carried, or null for none. */
  protected ActionContext context() {
    return context;
  }

  /** Gives the run the context its start carried, as the holder lists it. */
  void setContext(ActionContext context) {
    this.context = context;
  }

  /** Marks the run finished; it is removed by the next run pass. */
  protected void finish() {
    finished = true;
  }

  /** Sets the row's tags on the run as it starts, or tags the run sets itself. */
  protected void addTags(long more) {
    tags |= more;
  }

  /** Takes tags the run set itself off it again. */
  void clearTags(long mask) {
    tags &= ~mask;
  }
}
