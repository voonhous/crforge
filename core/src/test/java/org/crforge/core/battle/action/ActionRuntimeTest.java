package org.crforge.core.battle.action;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;
import org.crforge.core.battle.EntityActions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The action runtime's rules as its recorded cases pin them: scheduling and the millisecond
 * boundary, the three phases, the pause and execute gates, instances and their removal, the forced
 * stop, the singleton re-trigger, the next action both ways, the tags a run carries and the order a
 * pending pass takes its entries in.
 *
 * <p>Every case schedules the way the recorded ones did, as the battle does inside a pending pass:
 * an action whose delay has run out starts at once, in the caller's stack.
 */
class ActionRuntimeTest {

  private final List<String> log = new ArrayList<>();

  /** A row whose columns a case sets, which records what the runtime does with it. */
  private final class Row implements BattleAction {
    private final String name;
    private int delayMs;
    private int phase = EntityActions.PHASE_POST_TICK_INIT;
    private boolean singleton;
    private boolean lasting;
    private BattleAction next;
    private boolean nextWait;
    private long tags;
    private IntSupplier executeIf;
    private IntSupplier forceStopIf;
    private IntSupplier pausedIf;
    private boolean finishOnNextUpdate;

    private Row(String name) {
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public int phase() {
      return phase;
    }

    @Override
    public int delayMs() {
      return delayMs;
    }

    @Override
    public boolean singleton() {
      return singleton;
    }

    @Override
    public BattleAction nextAction() {
      return next;
    }

    @Override
    public boolean nextActionWait() {
      return nextWait;
    }

    @Override
    public long tags() {
      return tags;
    }

    @Override
    public IntSupplier executeIf() {
      return executeIf;
    }

    @Override
    public IntSupplier forceStopIf() {
      return forceStopIf;
    }

    @Override
    public IntSupplier pausedIf() {
      return pausedIf;
    }

    @Override
    public void scheduled(
        ActionHolder holder, int delayMs, boolean immediate, ActionHolder instigator) {
      log.add("scheduled " + name);
    }

    @Override
    public ActionInstance start(ActionHolder holder) {
      log.add("perform " + name);
      if (!lasting) {
        return null;
      }
      log.add("new instance " + name);
      return new ActionInstance(this) {
        @Override
        protected void update(ActionHolder h) {
          log.add("update " + name);
          if (finishOnNextUpdate) {
            finish();
          }
        }

        @Override
        protected void retrigger(ActionHolder h) {
          log.add("retrigger " + name);
        }
      };
    }
  }

  private ActionHolder holder() {
    ActionHolder holder = new ActionHolder();
    holder.setListener(
        new ActionHolder.Listener() {
          @Override
          public void forceStopped(ActionInstance instance) {
            log.add("force stop " + instance.getAction().name());
          }

          @Override
          public void removed(ActionInstance instance) {
            log.add("owner sees instance end " + instance.getAction().name());
          }
        });
    return holder;
  }

  /** Schedules as the recorded cases do: with the action's own delay, starting at once if due. */
  private static void schedule(ActionHolder holder, BattleAction action) {
    holder.schedule(action, ActionHolder.OWN_DELAY, true);
  }

  private List<String> take() {
    List<String> out = List.copyOf(log);
    log.clear();
    return out;
  }

  private static List<String> queue(ActionHolder holder) {
    return holder.queued().stream().map(q -> q.action().name() + " " + q.ticks()).toList();
  }

  private static List<String> running(ActionHolder holder) {
    return holder.running().stream().map(i -> i.getAction().name()).toList();
  }

  @Test
  @DisplayName("a zero delay runs now; a delay in milliseconds queues its ticks, 49 ms included")
  void scheduling() {
    ActionHolder h = holder();
    Row now = new Row("now");
    Row later = new Row("later");
    later.delayMs = 500;

    schedule(h, now);
    assertThat(take())
        .as("a zero delay runs immediately")
        .containsExactly("perform now", "scheduled now");
    assertThat(queue(h)).as("nothing is queued").isEmpty();

    schedule(h, later);
    assertThat(take()).as("a positive delay is queued, not run").containsExactly("scheduled later");
    assertThat(queue(h)).as("500 ms is 10 ticks").containsExactly("later 10");

    ActionHolder h2 = holder();
    h2.schedule(later, 150, true);
    assertThat(queue(h2)).as("the caller's delay wins over the row's").containsExactly("later 3");
    ActionHolder h3 = holder();
    h3.schedule(later, 0, true);
    assertThat(queue(h3)).as("a caller delay of zero runs now").isEmpty();
    take();

    ActionHolder h4 = holder();
    h4.schedule(later, 49, true);
    assertThat(queue(h4)).as("49 ms queues with zero ticks left").containsExactly("later 0");
    take();
    h4.pendingPass(1);
    assertThat(take()).as("and runs on the next pending pass").containsExactly("perform later");
  }

  @Test
  @DisplayName("a pending pass takes only its own phase's due entries; phase 0 goes to any pass")
  void phases() {
    ActionHolder h = holder();
    for (int phase = 1; phase <= 3; phase++) {
      Row row = new Row("phase" + phase);
      row.delayMs = 100;
      row.phase = phase;
      schedule(h, row);
    }
    take();
    assertThat(queue(h)).containsExactly("phase1 2", "phase2 2", "phase3 2");

    for (int step = 0; step < 2; step++) {
      for (int phase = 1; phase <= 3; phase++) {
        h.pendingPass(phase);
      }
      assertThat(take()).as("nothing runs while the delay is above zero").isEmpty();
      h.endOfTick();
    }
    assertThat(queue(h)).containsExactly("phase1 0", "phase2 0", "phase3 0");

    h.pendingPass(1);
    assertThat(take()).containsExactly("perform phase1");
    h.pendingPass(3);
    assertThat(take()).containsExactly("perform phase3");
    h.pendingPass(2);
    assertThat(take()).containsExactly("perform phase2");
    assertThat(queue(h)).isEmpty();

    ActionHolder h2 = holder();
    Row any = new Row("any");
    any.phase = BattleAction.ANY_PHASE;
    h2.schedule(any, 50, true);
    take();
    h2.endOfTick();
    h2.pendingPass(3);
    assertThat(take()).as("phase 0 is taken by phase 3").containsExactly("perform any");
    h2.schedule(any, 50, true);
    take();
    h2.endOfTick();
    h2.pendingPass(1);
    assertThat(take()).as("and by phase 1").containsExactly("perform any");
  }

  @Test
  @DisplayName(
      "a paused entry stays queued and counts past zero; a false execute gate drops the run")
  void gating() {
    ActionHolder h = holder();
    Row paused = new Row("paused");
    paused.delayMs = 100;
    paused.pausedIf = () -> 1;
    Row running = new Row("running");
    running.delayMs = 100;
    running.pausedIf = () -> 0;
    schedule(h, paused);
    schedule(h, running);
    take();
    h.endOfTick();
    h.endOfTick();
    h.pendingPass(1);
    assertThat(take()).containsExactly("perform running");
    assertThat(queue(h)).containsExactly("paused 0");
    h.endOfTick();
    h.endOfTick();
    assertThat(queue(h)).as("no floor").containsExactly("paused -2");

    ActionHolder h2 = holder();
    Row blocked = new Row("blocked");
    blocked.executeIf = () -> 0;
    Row allowed = new Row("allowed");
    allowed.executeIf = () -> 1;
    schedule(h2, blocked);
    assertThat(take()).containsExactly("scheduled blocked");
    schedule(h2, allowed);
    assertThat(take()).containsExactly("perform allowed", "scheduled allowed");
  }

  @Test
  @DisplayName("an instance is stepped once per run pass and removed at the pass after it finishes")
  void instances() {
    ActionHolder h = holder();
    Row lasting = new Row("lasting");
    lasting.lasting = true;
    h.start(lasting);
    assertThat(take()).containsExactly("perform lasting", "new instance lasting");
    assertThat(running(h)).containsExactly("lasting");

    h.runPass(1);
    assertThat(take()).containsExactly("update lasting");
    h.runPass(2);
    assertThat(take()).containsExactly("update lasting");
    assertThat(h.getLastTick()).as("the holder records the tick").isEqualTo(2);

    lasting.finishOnNextUpdate = true;
    h.runPass(3);
    assertThat(take()).containsExactly("update lasting");
    assertThat(running(h)).as("still listed after finishing").containsExactly("lasting");
    h.runPass(4);
    assertThat(take())
        .as("removed before it would be updated again")
        .containsExactly("owner sees instance end lasting");
    assertThat(running(h)).isEmpty();
  }

  @Test
  @DisplayName("a forced stop is checked before the update and removes the run in the same pass")
  void forceStop() {
    ActionHolder h = holder();
    Row stopping = new Row("stopping");
    stopping.lasting = true;
    stopping.forceStopIf = () -> 1;
    Row keeping = new Row("keeping");
    keeping.lasting = true;
    keeping.forceStopIf = () -> 0;
    h.start(keeping);
    h.start(stopping);
    take();

    h.runPass(1);
    assertThat(take())
        .containsExactly(
            "update keeping", "force stop stopping", "owner sees instance end stopping");
    assertThat(running(h)).containsExactly("keeping");
  }

  @Test
  @DisplayName("a singleton re-triggers its live run instead of starting a second")
  void singleton() {
    ActionHolder h = holder();
    Row once = new Row("once");
    once.lasting = true;
    once.singleton = true;
    h.start(once);
    take();
    h.start(once);
    assertThat(take()).containsExactly("retrigger once");
    assertThat(running(h)).containsExactly("once");

    ActionHolder h2 = holder();
    Row many = new Row("many");
    many.lasting = true;
    h2.start(many);
    h2.start(many);
    assertThat(running(h2)).containsExactly("many", "many");
  }

  @Test
  @DisplayName(
      "the next action starts after the run with the wait set, alongside the schedule without")
  void chaining() {
    ActionHolder h = holder();
    Row tail = new Row("tail");
    tail.delayMs = 100;
    Row headWait = new Row("head_wait");
    headWait.next = tail;
    headWait.nextWait = true;
    h.start(headWait);
    assertThat(take()).containsExactly("perform head_wait", "scheduled tail");
    assertThat(queue(h)).containsExactly("tail 2");

    ActionHolder h2 = holder();
    Row head = new Row("head");
    head.next = tail;
    schedule(h2, head);
    assertThat(take()).containsExactly("perform head", "scheduled head", "scheduled tail");
    assertThat(queue(h2)).containsExactly("tail 2");
  }

  @Test
  @DisplayName(
      "alongside, the next action's delay is the carried delay less the row's plus its own")
  void chainingCarriesTheDelay() {
    ActionHolder h = holder();
    Row tail = new Row("tail");
    tail.delayMs = 100;
    Row head = new Row("head");
    head.delayMs = 300;
    head.next = tail;

    h.schedule(head, 1000, true);
    assertThat(queue(h)).containsExactly("head 20", "tail 16");

    // 100 - 300 + 100 is below zero: floored at zero, the tail starts at once.
    take();
    ActionHolder h2 = holder();
    h2.schedule(head, 100, true);
    assertThat(queue(h2)).containsExactly("head 2");
    assertThat(take()).containsExactly("scheduled head", "perform tail", "scheduled tail");
  }

  @Test
  @DisplayName("a run carries its row's tags")
  void tags() {
    ActionHolder h = holder();
    Row tagged = new Row("tagged");
    tagged.lasting = true;
    tagged.tags = 0x1234;
    h.start(tagged);
    assertThat(h.running().get(0).getTags()).isEqualTo(0x1234);
    assertThat(h.running().get(0).getAction()).isSameAs(tagged);
  }

  @Test
  @DisplayName("a pending pass swaps the last entry into the one it takes: a b c d with b later")
  void order() {
    ActionHolder h = holder();
    List<Row> rows = new ArrayList<>();
    for (String name : List.of("a", "b", "c", "d")) {
      Row row = new Row(name);
      row.delayMs = 50;
      row.phase = name.equals("b") ? 2 : 1;
      rows.add(row);
      schedule(h, row);
    }
    take();
    h.endOfTick();
    h.pendingPass(1);
    assertThat(take()).containsExactly("perform a", "perform d", "perform c");
    assertThat(queue(h)).containsExactly("b 0");

    ActionHolder h2 = holder();
    for (Row row : rows) {
      row.phase = 1;
      schedule(h2, row);
    }
    take();
    h2.endOfTick();
    h2.pendingPass(1);
    assertThat(take()).containsExactly("perform a", "perform d", "perform c", "perform b");
    assertThat(queue(h2)).isEmpty();
  }
}
