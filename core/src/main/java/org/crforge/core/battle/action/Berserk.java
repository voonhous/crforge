package org.crforge.core.battle.action;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The Berserker's starting action: a run that lasts and flips its unit's attack sequence index on
 * every attack that lands. Its start sets the index to 0. The notice that one of the unit's attacks
 * ended with a landed hit sets it to 0 when it is above 0, else one up, so it runs 0, 1, 0, 1. Both
 * go through the unit's store, which keeps the index below the length of its order, with the
 * targeting component's bit ignored.
 *
 * <p>The row reads no column, its step does nothing and it never finishes by itself. The
 * Berserker's three attack sequence entries are equal, so the index it flips changes neither the
 * damage of a hit nor the timer of the next attack.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the start's 0, the flip on the notice of every landed attack, the"
            + " store with the component's bit ignored and the step that does nothing; held,"
            + " through the attacks the index picks, by the reference battles card_Berserker and"
            + " random_battle16_s0007. Refused: a row that sets any column besides its class, and"
            + " a run started beside an enchanting buff, whose own count sets the Berserker's"
            + " index.")
public final class Berserk extends RowAction {

  /** What the run did to the index, as the battle's observers are told. */
  public enum Event {
    /** The run started and set the index to 0. */
    START,
    /** An attack landed and the run flipped the index. */
    NOTICE
  }

  /**
   * @param row the row's shared columns
   */
  public Berserk(ActionRow row) {
    super(row);
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    for (ActionInstance instance : holder.running()) {
      if (instance instanceof GiantBufferBuff.Run) {
        throw new UnsupportedOperationException(
            name() + " started beside an enchanting buff, which is not modelled");
      }
    }
    ActionOwner owner = holder.getOwner();
    int before = owner.attackSequenceIndex();
    owner.setAttackSequenceIndex(0, true);
    owner.berserked(Event.START, before, owner.attackSequenceIndex());
    return new Run(this, owner);
  }

  /** One run on the unit: the owner whose index it flips. */
  private static final class Run extends ActionInstance {

    private final ActionOwner owner;

    private Run(BattleAction action, ActionOwner owner) {
      super(action);
      this.owner = owner;
    }

    @Override
    protected void update(ActionHolder holder) {
      // Its step is the base one, which does nothing.
    }

    @Override
    protected void attackEnded(ActionHolder holder) {
      int before = owner.attackSequenceIndex();
      owner.setAttackSequenceIndex(before > 0 ? 0 : before + 1, true);
      owner.berserked(Event.NOTICE, before, owner.attackSequenceIndex());
    }
  }
}
