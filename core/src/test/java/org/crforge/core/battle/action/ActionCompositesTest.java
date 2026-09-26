package org.crforge.core.battle.action;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The composite actions as their recorded cases pin them: the group and the select, which schedule
 * other actions when they are scheduled; the filter and the run on the instigator, which schedule
 * one when they start; and the four that last - the duration, the interval, the wait and the flip
 * flop.
 */
class ActionCompositesTest {

  private final List<String> log = new ArrayList<>();

  /** A leaf that records its start. */
  private final class Leaf implements BattleAction {
    private final String name;

    private Leaf(String name) {
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public ActionInstance start(ActionHolder holder) {
      log.add("perform " + name);
      return null;
    }
  }

  private List<String> take() {
    List<String> out = List.copyOf(log);
    log.clear();
    return out;
  }

  private static List<String> queue(ActionHolder holder) {
    return holder.queued().stream().map(q -> q.action().name() + " " + q.ticks()).toList();
  }

  private static ActionRow row(String name) {
    return ActionRow.named(name);
  }

  // ---------------------------------------------------------------------------------------------
  // Group and select: they act when they are scheduled
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "a group schedules every part at once, in order, each at the group's delay plus its own")
  void group() {
    Leaf a = new Leaf("a");
    Leaf b = new Leaf("b");
    Leaf c = new Leaf("c");
    Group group = new Group(row("group"), List.of(a, b, c), List.of(0, 100, 250));

    ActionHolder h = new ActionHolder();
    h.schedule(group, 0, true);
    assertThat(take())
        .as("the group does nothing but schedule its parts")
        .containsExactly("perform a");
    assertThat(queue(h)).containsExactly("b 2", "c 5");

    ActionHolder h2 = new ActionHolder();
    h2.schedule(group, 1000, true);
    assertThat(queue(h2))
        .as("a delayed group queues its parts at once, each at its own offset")
        .containsExactly("group 20", "a 20", "b 22", "c 25");

    Group short1 = new Group(row("group"), List.of(a, b, c), List.of(0));
    ActionHolder h3 = new ActionHolder();
    h3.schedule(short1, 1000, true);
    assertThat(queue(h3))
        .as("parts past the end of the delay list take the group's delay alone")
        .containsExactly("group 20", "a 20", "b 20", "c 20");

    Group blocked =
        new Group(row("group").toBuilder().executeIf(() -> 0).build(), List.of(a, b, c), List.of());
    ActionHolder h4 = new ActionHolder();
    take();
    h4.schedule(blocked, 0, true);
    assertThat(take()).as("a false start gate stops the group scheduling anything").isEmpty();
    assertThat(queue(h4)).isEmpty();
  }

  @Test
  @DisplayName(
      "a select picks one part: the condition modulo the list, or the first true per-part one")
  void select() {
    Leaf sa = new Leaf("sa");
    Leaf sb = new Leaf("sb");
    Leaf sc = new Leaf("sc");
    List<BattleAction> parts = List.of(sa, sb, sc);
    int[][] cases = {{0, 0}, {1, 1}, {2, 2}, {3, 0}, {5, 2}};
    for (int[] c : cases) {
      int value = c[0];
      new ActionHolder().schedule(new Select(row("select"), parts, () -> value, null), 0, true);
      assertThat(take())
          .as("condition %d", value)
          .containsExactly("perform " + parts.get(c[1]).name());
    }
    new ActionHolder().schedule(new Select(row("select"), parts, () -> -1, null), 0, true);
    assertThat(take()).as("a negative condition selects nothing").isEmpty();

    new ActionHolder()
        .schedule(
            new Select(row("select"), parts, () -> -1, List.of(() -> 0, () -> 1, () -> 1)),
            0,
            true);
    assertThat(take()).as("the first true per-part condition wins").containsExactly("perform sb");
    new ActionHolder()
        .schedule(
            new Select(row("select"), parts, () -> 0, List.of(() -> 0, () -> 0, () -> 0)), 0, true);
    assertThat(take()).as("no true per-part condition selects nothing").isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Filter and run on the instigator: they act when they start
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a filter schedules its true or false branch; its condition defaults to true")
  void filter() {
    Leaf onTrue = new Leaf("true");
    Leaf onFalse = new Leaf("false");
    ActionHolder h = new ActionHolder();

    h.schedule(new Filter(row("filter"), () -> 1, onTrue, onFalse), 0, true);
    h.schedule(new Filter(row("filter"), () -> 0, onTrue, onFalse), 0, true);
    h.schedule(new Filter(row("filter"), null, onTrue, onFalse), 0, true);
    assertThat(queue(h))
        .as("each branch is scheduled, to start at the next pending pass")
        .containsExactly("true 0", "false 0", "true 0");

    ActionHolder h2 = new ActionHolder();
    h2.schedule(new Filter(row("filter"), () -> 1, null, onFalse), 0, true);
    assertThat(queue(h2)).as("a missing branch schedules nothing").isEmpty();
  }

  @Test
  @DisplayName(
      "a run on the instigator schedules its action on the instigator, the owner its instigator")
  void runOnInstigator() {
    Leaf target = new Leaf("t1");
    ActionHolder owner = new ActionHolder();
    ActionHolder instigator = new ActionHolder();

    owner.schedule(new RunOnInstigator(row("run"), target), 0, true, instigator);
    assertThat(queue(owner)).as("nothing on the owner").isEmpty();
    assertThat(queue(instigator)).containsExactly("t1 0");
    assertThat(instigator.queuedInstigators()).containsExactly(owner);

    ActionHolder alone = new ActionHolder();
    alone.schedule(new RunOnInstigator(row("run"), target), 0, true);
    assertThat(queue(alone)).as("with no instigator nothing is scheduled").isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // The four that last
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a duration counts thousandths of a millisecond and ends the step after its length")
  void withDuration() {
    ActionHolder h = new ActionHolder();
    WithDuration hundred = new WithDuration(row("d"), 100, false, () -> 100, false);
    h.start(hundred);
    ActionInstance run = h.running().get(0);
    h.runPass(1);
    assertThat(counter(run)).isEqualTo(50_000);
    h.runPass(2);
    assertThat(counter(run)).isEqualTo(100_000);
    assertThat(run.isFinished()).as("tested before it is advanced").isFalse();
    h.runPass(3);
    assertThat(run.isFinished()).isTrue();
    assertThat(counter(run)).as("and left where it was").isEqualTo(100_000);

    for (int[] c : new int[][] {{500, 250_000}, {50, 25_000}}) {
      int percent = c[0];
      ActionHolder hs = new ActionHolder();
      hs.start(new WithDuration(row("d"), 100, true, () -> percent, false));
      hs.runPass(1);
      assertThat(counter(hs.running().get(0))).as("hit speed %d%%", percent).isEqualTo(c[1]);
    }

    for (boolean reset : new boolean[] {true, false}) {
      WithDuration long1 =
          new WithDuration(
              row("d").toBuilder().singleton(true).build(), 1000, false, () -> 100, reset);
      ActionHolder hr = new ActionHolder();
      hr.start(long1);
      hr.runPass(1);
      hr.start(long1);
      assertThat(counter(hr.running().get(0)))
          .as("re-triggered, reset %s", reset)
          .isEqualTo(reset ? 0 : 50_000);
    }
  }

  @Test
  @DisplayName(
      "an interval counts down from its start and fires every interval, held by its pause tag")
  void interval() {
    Leaf t1 = new Leaf("t1");
    ActionHolder h = new ActionHolder();
    h.start(new Interval(row("i"), 150, 100, t1, 0, () -> 0, () -> 100));
    ActionInstance run = h.running().get(0);
    assertThat(counter(run)).isEqualTo(100);
    h.runPass(1);
    assertThat(counter(run)).isEqualTo(50);
    assertThat(queue(h)).as("nothing fired yet").isEmpty();
    h.runPass(2);
    assertThat(queue(h)).as("reaching zero fires the action").containsExactly("t1 0");
    assertThat(h.queuedInstigators()).as("with its owner as the cause").containsExactly(h);
    assertThat(counter(run)).as("and reloads by adding the interval").isEqualTo(150);
    assertThat(run.isFinished()).as("never ends on its own").isFalse();

    ActionHolder paused = new ActionHolder();
    paused.start(new Interval(row("i"), 150, 50, t1, 0x40, () -> 0x40, () -> 100));
    paused.runPass(1);
    assertThat(counter(paused.running().get(0))).as("a matching pause tag holds it").isEqualTo(50);
    assertThat(queue(paused)).isEmpty();

    ActionHolder other = new ActionHolder();
    other.start(new Interval(row("i"), 150, 50, t1, 0x40, () -> 0x80, () -> 100));
    other.runPass(1);
    assertThat(counter(other.running().get(0))).isEqualTo(150);
    assertThat(queue(other)).containsExactly("t1 0");
  }

  @Test
  @DisplayName("a wait ends the step its condition holds, scheduling its action if it has one")
  void waitToActivate() {
    Leaf t1 = new Leaf("t1");
    int[] condition = {0};
    ActionHolder h = new ActionHolder();
    h.start(new WaitToActivate(row("w"), () -> condition[0], t1));
    ActionInstance run = h.running().get(0);
    h.runPass(1);
    assertThat(run.isFinished()).isFalse();
    condition[0] = 1;
    h.runPass(2);
    assertThat(run.isFinished()).isTrue();
    assertThat(queue(h)).containsExactly("t1 0");

    ActionHolder h2 = new ActionHolder();
    h2.start(new WaitToActivate(row("w"), () -> 1, null));
    h2.runPass(1);
    assertThat(h2.running().get(0).isFinished()).as("ends with no action too").isTrue();

    ActionHolder h3 = new ActionHolder();
    h3.start(new WaitToActivate(row("w"), null, t1));
    h3.runPass(1);
    assertThat(h3.running().get(0).isFinished()).as("the condition defaults to false").isFalse();
  }

  @Test
  @DisplayName(
      "a flip flop turns only after its condition disagrees for the whole time, and never ends")
  void flipFlop() {
    Leaf on = new Leaf("t1");
    Leaf off = new Leaf("t2");
    int[] condition = {0};
    ActionHolder h = new ActionHolder();
    h.start(new FlipFlop(row("f"), () -> condition[0], 100, 150, on, off));
    ActionInstance run = h.running().get(0);
    FlipFlop.Run state = (FlipFlop.Run) run;
    assertThat(state.isActive()).isFalse();
    assertThat(state.getTimerMs()).isZero();

    h.runPass(1);
    assertThat(state.getTimerMs()).as("false while off changes nothing").isZero();
    condition[0] = 1;
    h.runPass(2);
    assertThat(state.getTimerMs()).isEqualTo(50);
    assertThat(state.isActive()).isFalse();
    h.runPass(3);
    assertThat(state.isActive()).as("turns on at the activation time").isTrue();
    assertThat(queue(h)).containsExactly("t1 0");
    assertThat(state.getTimerMs()).isZero();

    h.runPass(4);
    assertThat(state.getTimerMs()).as("true while on changes nothing").isZero();
    condition[0] = 0;
    h.runPass(5);
    assertThat(state.getTimerMs()).isEqualTo(50);
    h.runPass(6);
    assertThat(state.getTimerMs()).isEqualTo(100);
    assertThat(state.isActive()).isTrue();
    h.runPass(7);
    assertThat(state.isActive()).as("turns off at the deactivation time").isFalse();
    assertThat(queue(h)).containsExactly("t1 0", "t2 0");
    assertThat(run.isFinished()).isFalse();

    condition[0] = 1;
    h.runPass(8);
    condition[0] = 0;
    h.runPass(9);
    assertThat(state.getTimerMs()).as("flipping back resets the timer").isZero();
  }

  private static long counter(ActionInstance run) {
    return ((CountingRun) run).counter();
  }
}
