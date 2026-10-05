package org.crforge.core.battle.action;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The context a chain of actions shares: a group makes one, hands on the one it was scheduled with,
 * or drops it, by its context mode; the context then goes with a queue entry to its start, with a
 * run to the actions it schedules and to its stop gate, and with a next action.
 */
class ActionContextTest {

  /** The contexts each leaf started with, in start order: the leaf's name and its context. */
  private final List<String> names = new ArrayList<>();

  private final List<ActionContext> contexts = new ArrayList<>();

  /** A leaf that records the context it starts with. */
  private class Leaf extends RowAction {
    private Leaf(ActionRow row) {
      super(row);
    }

    private Leaf(String name) {
      this(ActionRow.named(name));
    }

    @Override
    public ActionInstance start(ActionHolder holder) {
      throw new AssertionError("started without its context");
    }

    @Override
    public ActionInstance start(
        ActionHolder holder, ActionHolder instigator, ActionContext context) {
      names.add(name());
      contexts.add(context);
      return null;
    }
  }

  /** A leaf that writes a value under a key into the context it starts with. */
  private static final class Writer extends RowAction {
    private final int key;
    private final int value;

    private Writer(String name, int key, int value) {
      super(ActionRow.named(name));
      this.key = key;
      this.value = value;
    }

    @Override
    public ActionInstance start(ActionHolder holder) {
      return null;
    }

    @Override
    public ActionInstance start(
        ActionHolder holder, ActionHolder instigator, ActionContext context) {
      context.write(false, key, value);
      return null;
    }
  }

  private static Group group(String name, Group.ContextMode mode, BattleAction... parts) {
    return new Group(ActionRow.named(name), List.of(parts), List.of(), mode);
  }

  /** One tick of a holder outside a battle: the pending pass, the run pass, the end pass. */
  private static void tick(ActionHolder holder, int tick) {
    holder.pendingPass(1);
    holder.runPass(tick);
    holder.endOfTick();
  }

  @Test
  @DisplayName("a key is the FNV-1a hash of its name, and a read looks in the main board first")
  void aKeyIsItsNamesHash() {
    assertThat(ActionContext.key("a")).isEqualTo(0xe40c292c);
    assertThat(ActionContext.key("victim_hp")).isEqualTo(2024048246);
    ActionContext context = new ActionContext();
    context.write(true, 1, 10);
    assertThat(context.read(1)).isEqualTo(10);
    context.write(false, 1, 20);
    assertThat(context.read(1)).isEqualTo(20);
    assertThat(context.read(2)).isNull();
  }

  @Test
  @DisplayName(
      "a group that creates a context hands one to all its parts, a new one each time it is"
          + " scheduled; one with no mode hands none on; one that inherits hands on what it was"
          + " scheduled with, and makes one when it came with none")
  void theModesDecideTheContextOfTheParts() {
    ActionHolder holder = new ActionHolder();
    Group create = group("create", Group.ContextMode.CREATE, new Leaf("a"), new Leaf("b"));
    holder.schedule(create, 0, true);
    holder.schedule(create, 0, true);

    assertThat(names).containsExactly("a", "b", "a", "b");
    assertThat(contexts.get(0)).isNotNull().isSameAs(contexts.get(1));
    assertThat(contexts.get(2)).isNotNull().isSameAs(contexts.get(3));
    assertThat(contexts.get(0)).isNotSameAs(contexts.get(2));

    names.clear();
    contexts.clear();
    holder.schedule(
        group(
            "outer",
            Group.ContextMode.CREATE,
            new Leaf("own"),
            group("none", Group.ContextMode.NONE, new Leaf("dropped")),
            group("inherit", Group.ContextMode.INHERIT, new Leaf("inherited"))),
        0,
        true);

    assertThat(names).containsExactly("own", "dropped", "inherited");
    assertThat(contexts.get(0)).isNotNull();
    assertThat(contexts.get(1)).isNull();
    assertThat(contexts.get(2)).isSameAs(contexts.get(0));

    names.clear();
    contexts.clear();
    holder.schedule(group("alone", Group.ContextMode.INHERIT, new Leaf("made")), 0, true);
    holder.schedule(group("plain", Group.ContextMode.NONE, new Leaf("bare")), 0, true);

    assertThat(names).containsExactly("made", "bare");
    assertThat(contexts.get(0)).isNotNull();
    assertThat(contexts.get(1)).isNull();
  }

  @Test
  @DisplayName(
      "a queued part starts with the context, and a next action carries it alongside and after"
          + " the run")
  void theContextGoesWithTheQueueAndTheNextAction() {
    ActionHolder holder = new ActionHolder();
    Leaf after = new Leaf("after");
    Leaf alongside = new Leaf("alongside");
    Leaf waits =
        new Leaf(ActionRow.builder().name("waits").nextAction(after).nextActionWait(true).build());
    Leaf leads = new Leaf(ActionRow.builder().name("leads").nextAction(alongside).build());
    holder.schedule(
        new Group(
            ActionRow.named("group"),
            List.of(new Leaf("now"), waits, leads),
            List.of(0, 100, 100),
            Group.ContextMode.CREATE),
        0,
        true);
    for (int t = 1; t <= 4; t++) {
      tick(holder, t);
    }

    assertThat(names).containsExactlyInAnyOrder("now", "waits", "after", "leads", "alongside");
    assertThat(contexts).doesNotContainNull();
    assertThat(contexts.stream().distinct().count()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "an interval fires its action with the context its start carried, and its stop gate reads"
          + " that context: a value its action writes stops it at the next run pass")
  void anIntervalHandsItsContextOnAndIsStoppedByIt() {
    int key = ActionContext.key("AlreadyFarted");
    ActionHolder holder = new ActionHolder();
    List<Integer> fired = new ArrayList<>();
    Writer writer = new Writer("farted", key, 1);
    Leaf seen =
        new Leaf("seen") {
          @Override
          public ActionInstance start(
              ActionHolder h, ActionHolder instigator, ActionContext context) {
            fired.add(holder.getLastTick());
            return super.start(h, instigator, context);
          }
        };
    Group fire = group("fire", Group.ContextMode.INHERIT, seen, writer);
    Interval check =
        new Interval(
            ActionRow.builder()
                .name("check")
                .forceStopIf(
                    () -> {
                      ActionContext context = holder.currentContext();
                      return context != null && context.read(key) != null ? 1 : 0;
                    })
                .build(),
            100,
            50,
            fire,
            0,
            () -> 0,
            () -> 100);
    Leaf first = new Leaf("first");
    holder.schedule(group("egg", Group.ContextMode.CREATE, first, check), 0, true);
    for (int t = 1; t <= 6; t++) {
      tick(holder, t);
    }

    assertThat(names).containsExactly("first", "seen");
    assertThat(contexts.get(1)).isSameAs(contexts.get(0)).isNotNull();
    // Fired in the run pass of tick 1, its parts start in the next pending pass.
    assertThat(fired).as("the ticks the interval's action started on").containsExactly(1);
    assertThat(holder.running()).isEmpty();
  }

  /** An owner whose variables the test reads back. */
  private static final class VariableOwner implements ActionOwner {
    private final Map<Integer, Integer> variables = new HashMap<>();

    @Override
    public HitPoints actionHitPoints() {
      return null;
    }

    @Override
    public int variable(int key) {
      return variables.getOrDefault(key, 0);
    }

    @Override
    public void setVariable(int key, int value) {
      variables.put(key, value);
    }

    @Override
    public void killBy(ActionOwner killer) {}

    @Override
    public void queueTypedHit(ActionOwner source, int amount, DamageType type) {}
  }

  @Test
  @DisplayName(
      "a board write evaluates its value with the context and writes the board it names; with no"
          + " context it does nothing and evaluates nothing")
  void aBoardWriteWritesTheContext() {
    ActionHolder holder = new ActionHolder();
    int key = ActionContext.key("PosX");
    int other = ActionContext.key("PosY");
    BlackboardSetInt main =
        new BlackboardSetInt(
            ActionRow.named("main"), false, key, () -> holder.currentContext() != null ? 7 : -7);
    BlackboardSetInt scratch =
        new BlackboardSetInt(ActionRow.named("scratch"), true, other, () -> 3);
    holder.schedule(
        group("writes", Group.ContextMode.CREATE, main, scratch, new Leaf("reads")), 0, true);

    ActionContext context = contexts.get(0);
    assertThat(context.readBoard(false, key)).isEqualTo(7);
    assertThat(context.readBoard(true, other)).isEqualTo(3);
    assertThat(context.readBoard(false, other)).isNull();
    assertThat(context.read(other)).isEqualTo(3);

    holder.schedule(
        new BlackboardSetInt(
            ActionRow.named("bare"),
            false,
            key,
            () -> {
              throw new AssertionError("evaluated with no context");
            }),
        0,
        true);
  }

  @Test
  @DisplayName(
      "a copy into a variable reads the one board it names, else its default, and writes the"
          + " owner's variable; with no context it writes nothing")
  void aCopyIntoAVariableReadsOneBoard() {
    VariableOwner owner = new VariableOwner();
    ActionHolder holder = new ActionHolder(owner);
    int key = ActionContext.key("tombstone_hp_percent");
    holder.schedule(
        group(
            "copies",
            Group.ContextMode.CREATE,
            new BlackboardSetInt(ActionRow.named("write"), true, key, () -> 5),
            new ContextToVariable(ActionRow.named("fromMain"), false, key, 9, 3),
            new ContextToVariable(ActionRow.named("fromScratch"), true, key, 9, 4)),
        0,
        true);

    assertThat(owner.variable(3)).isEqualTo(9);
    assertThat(owner.variable(4)).isEqualTo(5);

    holder.schedule(new ContextToVariable(ActionRow.named("bare"), true, key, 9, 6), 0, true);
    assertThat(owner.variables).doesNotContainKey(6);
  }
}
