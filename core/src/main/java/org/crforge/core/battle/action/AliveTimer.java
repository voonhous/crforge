package org.crforge.core.battle.action;

import java.util.List;
import lombok.Getter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * Actions run at ages of an area effect: a run on the area effect that, at each update, finds the
 * last age in its list the area effect has reached and, once it moves past the one before,
 * schedules the action at that place on the area effect, the area effect its own cause. Without
 * repeats an action is run only past the count already run. A list whose ages and actions differ in
 * count, or an empty one, finishes the run as it starts.
 *
 * <p>Refused as the row is built: an action that is not an effect, which only shows something, and
 * the shared columns its run does not read.
 */
@Fidelity(
    status = FidelityStatus.TRACED,
    note =
        "Settled line for line: the age as the lifetime less the countdown, the walk to the last"
            + " age reached, the action at a new place scheduled on the area effect, the count"
            + " without repeats, the finish of a list that does not match; held by"
            + " baby_dragon_ev1_wind. Refused: an action other than an effect, and a run on an"
            + " owner other than an area effect.")
public final class AliveTimer extends RowAction {

  /** The ages, in milliseconds, in order. */
  @Getter private final List<Integer> agesMs;

  /** The action at each age. */
  @Getter private final List<BattleAction> actions;

  /** True when an action may be run again at a place already passed. */
  @Getter private final boolean allowRepeat;

  /**
   * @param row the row's shared columns
   * @param agesMs the ages, in order
   * @param actions the action at each age
   * @param allowRepeat true when an action may be run again at a place already passed
   */
  public AliveTimer(
      ActionRow row, List<Integer> agesMs, List<BattleAction> actions, boolean allowRepeat) {
    super(row);
    this.agesMs = List.copyOf(agesMs);
    this.actions = List.copyOf(actions);
    this.allowRepeat = allowRepeat;
  }

  @Override
  public ActionInstance start(ActionHolder holder) {
    return new Run(holder.getOwner().aliveAge());
  }

  /** The age of the owner, asked at each update. */
  public interface Age {

    /** The owner's age: its lifetime less its countdown, in milliseconds. */
    int ageMs();
  }

  /** One run: the place the last update found and the count of actions run, both -1 at first. */
  final class Run extends ActionInstance {

    private final Age age;
    private int index = -1;
    private int count = -1;

    private Run(Age age) {
      super(AliveTimer.this);
      this.age = age;
      if (agesMs.isEmpty() || actions.size() != agesMs.size()) {
        finish();
      }
    }

    @Override
    protected void update(ActionHolder holder) {
      int previous = index;
      index = -1;
      int now = age.ageMs();
      int n = agesMs.size();
      int found = -1;
      if (n >= 1 && now >= agesMs.get(0)) {
        int i = 0;
        while (i != n - 1 && now >= agesMs.get(i + 1)) {
          i++;
        }
        index = i;
        found = i;
      }
      if (found <= previous || !allowRepeat && found <= count) {
        return;
      }
      count++;
      holder.getOwner().aliveTimerFired(actions.get(found).name());
      holder.schedule(actions.get(found), ActionHolder.OWN_DELAY, false, holder);
    }
  }
}
