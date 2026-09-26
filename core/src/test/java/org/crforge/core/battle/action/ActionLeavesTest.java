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
 * The leaf actions that act on their own owner, as their recorded cases pin them: setting a
 * variable, setting a shield, and running actions as the owner's health falls past thresholds.
 */
class ActionLeavesTest {

  /** An owner with a variable map and, when given, hit points. */
  private static final class Owner implements ActionOwner {
    private final Map<Integer, Integer> variables = new HashMap<>();
    private final HitPoints hitPoints;

    private Owner(HitPoints hitPoints) {
      this.hitPoints = hitPoints;
    }

    @Override
    public HitPoints actionHitPoints() {
      return hitPoints;
    }

    @Override
    public int variable(int key) {
      return variables.getOrDefault(key, 0);
    }

    @Override
    public void setVariable(int key, int value) {
      variables.put(key, value);
    }
  }

  /** A leaf that does nothing, whose name the queue shows. */
  private static BattleAction leaf(String name) {
    return new BattleAction() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public ActionInstance start(ActionHolder holder) {
        return null;
      }
    };
  }

  private static HitPoints hitPoints(int current, int maximum) {
    HitPoints hp = new HitPoints(maximum);
    hp.setHitPoints(current);
    return hp;
  }

  private static List<String> queue(ActionHolder holder) {
    return holder.queued().stream().map(q -> q.action().name()).toList();
  }

  // ---------------------------------------------------------------------------------------------
  // SetVariable
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a variable is written to the owner's map, replaced by a second write, and 0 unread")
  void setVariable() {
    Owner owner = new Owner(null);
    ActionHolder h = new ActionHolder(owner);
    List<String> evaluated = new ArrayList<>();

    h.start(
        new SetVariable(
            ActionRow.named("set"),
            () -> {
              evaluated.add("value");
              return 7;
            },
            41));
    assertThat(evaluated).as("the value is evaluated").containsExactly("value");
    assertThat(owner.variable(41)).isEqualTo(7);
    assertThat(owner.variable(42)).as("a key never written reads as zero").isZero();

    h.start(new SetVariable(ActionRow.named("set"), () -> 9, 41));
    assertThat(owner.variable(41)).as("writing again replaces the value").isEqualTo(9);
    h.start(new SetVariable(ActionRow.named("set"), () -> 3, 0));
    assertThat(owner.variable(0)).as("a second key is a second entry").isEqualTo(3);
    assertThat(owner.variable(41)).isEqualTo(9);

    evaluated.clear();
    h.start(
        new SetVariable(
            ActionRow.named("set"),
            () -> {
              evaluated.add("value");
              return 1;
            },
            SetVariable.NO_VARIABLE));
    assertThat(evaluated).as("without a variable nothing is evaluated").isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // SetShield
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a shield is its percentage of max hit points, clamped to 0..100 and truncated")
  void setShield() {
    int[][] cases = {
      {40, 1000, 400},
      {100, 1000, 1000},
      {150, 1000, 1000},
      {0, 1000, 0},
      {-5, 1000, 0},
      {33, 999, 329}
    };
    for (int[] c : cases) {
      HitPoints hp = hitPoints(c[1], c[1]);
      new ActionHolder(new Owner(hp)).start(new SetShield(ActionRow.named("shield"), c[0]));
      assertThat(hp.getShield()).as("%d%% of %d", c[0], c[1]).isEqualTo(c[2]);
    }

    new ActionHolder(new Owner(null)).start(new SetShield(ActionRow.named("shield"), 40));

    HitPoints none = new HitPoints(1000);
    none.setMaximum(0);
    new ActionHolder(new Owner(none)).start(new SetShield(ActionRow.named("shield"), 40));
    assertThat(none.getShield()).as("zero max hit points, no shield").isZero();
  }

  // ---------------------------------------------------------------------------------------------
  // RunActionAtHealth
  // ---------------------------------------------------------------------------------------------

  private static ActionInstance startAtHealth(
      ActionHolder h, List<Integer> percentages, List<BattleAction> actions) {
    h.start(new RunActionAtHealth(ActionRow.named("at_health"), percentages, actions));
    return h.running().get(0);
  }

  @Test
  @DisplayName("the run ends at once unless the lists match and the owner has hit points")
  void runActionAtHealthStart() {
    BattleAction t1 = leaf("T1");
    Object[][] cases = {
      {List.of(50), List.of(t1), true},
      {List.of(), List.of(), false},
      {List.of(50), List.of(), false},
      {List.of(), List.of(t1), false},
      {List.of(50, 20), List.of(t1), false}
    };
    for (Object[] c : cases) {
      @SuppressWarnings("unchecked")
      List<Integer> percentages = (List<Integer>) c[0];
      @SuppressWarnings("unchecked")
      List<BattleAction> actions = (List<BattleAction>) c[1];
      ActionHolder h = new ActionHolder(new Owner(hitPoints(1000, 1000)));
      ActionInstance run = startAtHealth(h, percentages, actions);
      assertThat(run.isFinished())
          .as("%s / %s", percentages, actions.size())
          .isEqualTo(!(boolean) c[2]);
    }
    ActionHolder h = new ActionHolder(new Owner(null));
    assertThat(startAtHealth(h, List.of(50), List.of(t1)).isFinished())
        .as("an owner without hit points ends the run at once")
        .isTrue();
  }

  @Test
  @DisplayName("each threshold fires once, in order, at or below its share of the maximum")
  void runActionAtHealthThresholds() {
    BattleAction t1 = leaf("T1");
    BattleAction t2 = leaf("T2");
    HitPoints hp = hitPoints(1000, 1000);
    ActionHolder h = new ActionHolder(new Owner(hp));
    ActionInstance run = startAtHealth(h, List.of(50, 20), List.of(t1, t2));

    h.runPass(1);
    assertThat(queue(h)).as("at full health nothing fires").isEmpty();
    hp.setHitPoints(501);
    h.runPass(2);
    assertThat(queue(h)).as("just above the first threshold").isEmpty();
    hp.setHitPoints(500);
    h.runPass(3);
    assertThat(queue(h)).as("at exactly the first threshold").containsExactly("T1");
    assertThat(run.isFinished()).isFalse();
    h.runPass(4);
    assertThat(queue(h)).as("the second is not reached yet").containsExactly("T1");
    hp.setHitPoints(150);
    h.runPass(5);
    assertThat(queue(h)).containsExactly("T1", "T2");
    assertThat(run.isFinished()).as("the last threshold ends the run").isTrue();

    HitPoints low = hitPoints(1000, 1000);
    ActionHolder h2 = new ActionHolder(new Owner(low));
    ActionInstance run2 = startAtHealth(h2, List.of(80, 50, 20), List.of(t1, t2, t1));
    low.setHitPoints(10);
    h2.runPass(1);
    assertThat(queue(h2)).as("one step across three thresholds").containsExactly("T1", "T2", "T1");
    assertThat(run2.isFinished()).isTrue();

    int[][] scaled = {{1200, 2000, 0}, {1000, 2000, 1}, {999, 2000, 1}};
    for (int[] c : scaled) {
      ActionHolder hs = new ActionHolder(new Owner(hitPoints(c[0], c[1])));
      startAtHealth(hs, List.of(50), List.of(t1));
      hs.runPass(1);
      assertThat(queue(hs)).as("%d of %d", c[0], c[1]).hasSize(c[2]);
    }

    HitPoints healed = hitPoints(400, 1000);
    ActionHolder h3 = new ActionHolder(new Owner(healed));
    ActionInstance run3 = startAtHealth(h3, List.of(50, 20), List.of(t1, t2));
    h3.runPass(1);
    assertThat(queue(h3)).containsExactly("T1");
    healed.setHitPoints(1000);
    h3.runPass(2);
    assertThat(queue(h3)).as("healing does not re-arm the entry").containsExactly("T1");
    assertThat(run3.isFinished()).isFalse();
  }
}
