package org.crforge.core.battle.action;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.EntityActions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The action holder's scheduling and run rules, one at a time. */
class ActionHolderTest {

  /** The names of the actions started, in the order they started, with their phase. */
  private final List<String> started = new ArrayList<>();

  private ActionHolder holder() {
    ActionHolder holder = new ActionHolder();
    holder.setListener(
        new ActionHolder.Listener() {
          @Override
          public void started(BattleAction action, int phase) {
            started.add(action.name() + "@" + phase);
          }
        });
    return holder;
  }

  @Test
  @DisplayName(
      "a pending pass takes due entries by swapping the last one in: a b c d start a d c b")
  void thePendingPassSwapsTheLastEntryIn() {
    ActionHolder holder = holder();
    for (String name : List.of("a", "b", "c", "d")) {
      holder.schedule(new InertAction(ActionRow.named(name)), 0);
    }

    holder.pendingPass(EntityActions.PHASE_POST_TICK_INIT);

    assertThat(started).containsExactly("a@1", "d@1", "c@1", "b@1");
  }

  @Test
  @DisplayName(
      "an action with no delay starts at once inside a pending pass and waits for the next one"
          + " outside it")
  void aZeroDelayActionStartsAtOnceOnlyInsideAPendingPass() {
    ActionHolder holder = holder();
    BattleAction later = new InertAction(ActionRow.named("later"));
    BattleAction scheduler =
        new BattleAction() {
          @Override
          public String name() {
            return "scheduler";
          }

          @Override
          public ActionInstance start(ActionHolder h) {
            h.schedule(later, 0);
            return null;
          }
        };

    // Scheduled from inside a pending pass, the second action starts in the same pass.
    holder.schedule(scheduler, 0);
    holder.pendingPass(EntityActions.PHASE_POST_TICK_INIT);
    assertThat(started).containsExactly("later@1", "scheduler@1");

    // Scheduled from outside one, it waits for the next pending pass.
    started.clear();
    holder.schedule(later, 0);
    assertThat(started).isEmpty();
    holder.pendingPass(EntityActions.PHASE_POST_COMPONENT_TICK);
    assertThat(started).containsExactly("later@2");
  }

  @Test
  @DisplayName("a delayed entry waits out its ticks in the end passes")
  void aDelayedEntryWaitsOutItsTicks() {
    ActionHolder holder = holder();
    holder.schedule(new InertAction(ActionRow.named("delayed")), 100);

    holder.pendingPass(EntityActions.PHASE_POST_TICK_INIT);
    holder.endOfTick();
    holder.pendingPass(EntityActions.PHASE_POST_TICK_INIT);
    assertThat(started).isEmpty();
    holder.endOfTick();
    holder.pendingPass(EntityActions.PHASE_POST_TICK_INIT);

    assertThat(started).containsExactly("delayed@1");
  }

  @Test
  @DisplayName(
      "a 100 ms run finishes on its third step, keeps its tags until the next run pass removes"
          + " it")
  void aFinishedRunKeepsItsTagsUntilTheNextRunPass() {
    ActionHolder holder = holder();
    holder.schedule(new WithDuration("run", 100, GameTags.ACTIVATING, null), 0);
    holder.pendingPass(EntityActions.PHASE_POST_TICK_INIT);
    assertThat(holder.tags()).isEqualTo(GameTags.ACTIVATING);

    // The counter is compared before it is advanced: 0, 50 and 100 ms, finished on the third.
    holder.runPass(0);
    holder.runPass(1);
    assertThat(holder.running().get(0).isFinished()).isFalse();
    holder.runPass(2);
    assertThat(holder.running().get(0).isFinished()).isTrue();
    assertThat(holder.tags()).as("finished, still listed").isEqualTo(GameTags.ACTIVATING);

    holder.runPass(3);
    assertThat(holder.running()).isEmpty();
    assertThat(holder.tags()).isZero();
  }

  @Test
  @DisplayName(
      "a wait ends on the run-pass step its condition holds, and its activation starts in the next"
          + " pending pass")
  void aWaitEndsOnTheStepItsConditionHolds() {
    ActionHolder holder = holder();
    boolean[] condition = {false};
    BattleAction effect = new InertAction(ActionRow.named("effect"));
    BattleAction activating = new WithDuration("activating", 3300, GameTags.ACTIVATING, effect);
    holder.schedule(
        new WaitToActivate("wait", () -> condition[0], activating, GameTags.INACTIVE), 0);
    holder.pendingPass(EntityActions.PHASE_POST_TICK_INIT);
    holder.runPass(0);
    assertThat(holder.tags()).isEqualTo(GameTags.INACTIVE);

    condition[0] = true;
    holder.runPass(1);
    assertThat(started).containsExactly("wait@1");
    holder.pendingPass(EntityActions.PHASE_POST_COMPONENT_TICK);

    // The activating run and its effect alongside start in phase 2, after the finished wait, which
    // still sets its tag.
    assertThat(started).containsExactly("wait@1", "activating@2", "effect@2");
    assertThat(holder.tags()).isEqualTo(GameTags.INACTIVE | GameTags.ACTIVATING);
    holder.runPass(2);
    assertThat(holder.tags()).isEqualTo(GameTags.ACTIVATING);
  }
}
