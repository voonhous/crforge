/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.DamageType;
import org.crforge.core.battle.action.Group;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Action rows that reach a row again through itself. The game's rows are one object each, which the
 * rows naming them point at, so a group whose part chains back to the group is a loop: each lap
 * schedules the group again, and the group's start gate, asked each time, ends it. A NextAction
 * chain that leads back to its own row is another matter: the loader clears it, which is not
 * modelled, so such a row is refused.
 */
class ActionCycleTest {

  private static final String GROUP = "Cycle_Group";
  private static final String MARK = "Cycle_Mark";
  private static final String COUNT = "Cycle_Count";

  /**
   * The test's rows added to the configured actions: a group gated on LAPS < 3 whose parts are a
   * bare effect, at once, and a count 50 ms later that adds one to LAPS and chains, waited, back to
   * the group. The count's own delay is 50 ms too, as the shipped loop's is.
   */
  private static GameTables loopTables(Path folder) throws IOException {
    return GameData.altered(
        folder,
        "actions",
        rows -> {
          ObjectNode group = row(rows, GROUP, "ActionGroup");
          group.put("ExecuteIfTrue", "LAPS < 3");
          group.putArray("SubActions").add(ref(MARK)).add(ref(COUNT));
          group.putArray("SubActionsDelay").add(0).add(50);
          ObjectNode mark = row(rows, MARK, "ActionPlayEffect");
          mark.put("Effect", "cycle_effect");
          ObjectNode count = row(rows, COUNT, "ActionSetVariable");
          count.put("ActionDelay", 50);
          count.put("ExecuteIfTrue", "LAPS < 3");
          count.put("Variable", "LAPS");
          count.put("Value", "LAPS + 1");
          count.set("NextAction", ref(GROUP));
          count.put("NextActionWait", true);
        });
  }

  /** Adds an action row of a class to the actions table; answers its fields. */
  private static ObjectNode row(ObjectNode rows, String name, String classType) {
    ObjectNode row = rows.putObject(name);
    row.put("class", "Logic" + classType + "Data");
    row.put("ClassType", classType);
    ObjectNode fields = row.putObject("fields");
    fields.put("ClassType", classType);
    return fields;
  }

  /** A reference to a row by name, as the data writes one. */
  private static ObjectNode ref(String name) {
    ObjectNode ref = JsonNodeFactory.instance.objectNode();
    ref.put("action", name);
    return ref;
  }

  /** An owner that keeps its variables, which the binding's expressions read. */
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

  /** A binding for the owner that compiles the loop's two expressions over its LAPS variable. */
  private static ActionBinding binding(VariableOwner owner) {
    int laps = "LAPS".hashCode();
    return new ActionBinding() {
      @Override
      public IntSupplier expression(String text) {
        return switch (text) {
          case "LAPS < 3" -> () -> owner.variable(laps) < 3 ? 1 : 0;
          case "LAPS + 1" -> () -> owner.variable(laps) + 1;
          default -> throw new IllegalArgumentException(text);
        };
      }

      @Override
      public int variableKey(String name) {
        return name.hashCode();
      }

      @Override
      public LongSupplier tags() {
        return () -> 0;
      }
    };
  }

  @Test
  @DisplayName(
      "a group whose part chains back to the group is built as a loop: one lap a tick, the group's"
          + " gate asked at each lap, which ends it once the count reaches it")
  void aGroupReachedAgainThroughItsPartLoops(@TempDir Path folder) throws IOException {
    GameTables tables = loopTables(folder);
    VariableOwner owner = new VariableOwner();
    BattleAction group =
        new ActionRows(tables, new BattleRecords(tables)).build(GROUP, binding(owner));
    assertThat(group).isInstanceOf(Group.class);

    ActionHolder holder = new ActionHolder(owner);
    List<String> started = new ArrayList<>();
    int[] tick = {0};
    holder.setListener(
        new ActionHolder.Listener() {
          @Override
          public void started(BattleAction action, int phase) {
            started.add(tick[0] + " " + action.name());
          }
        });
    // Tick 0 schedules the group at once, as a tap does, and ends with the end pass.
    holder.schedule(group, ActionHolder.OWN_DELAY, true);
    holder.endOfTick();
    for (tick[0] = 1; tick[0] <= 6; tick[0]++) {
      holder.pendingPass(1);
      holder.runPass(tick[0]);
      holder.endOfTick();
    }

    // The first lap starts as the group is scheduled; each count's waited chain schedules the
    // group again with its own delay of none, inside the pending pass, so it starts at once. The
    // count's part delay of 50 ms stands in for its own: one tick a lap.
    assertThat(started)
        .containsExactly(
            "0 " + GROUP,
            "0 " + MARK,
            "1 " + COUNT,
            "1 " + GROUP,
            "1 " + MARK,
            "2 " + COUNT,
            "2 " + GROUP,
            "2 " + MARK,
            "3 " + COUNT);
    assertThat(owner.variable("LAPS".hashCode())).isEqualTo(3);
    assertThat(holder.queued()).isEmpty();
  }

  @Test
  @DisplayName("a row whose NextAction chain leads back to the row itself is refused")
  void aNextActionChainBackToItsRowIsRefused(@TempDir Path folder) throws IOException {
    GameTables tables =
        GameData.altered(
            folder,
            "actions",
            rows -> {
              row(rows, "Chain_A", "ActionPlayEffect").set("NextAction", ref("Chain_B"));
              row(rows, "Chain_B", "ActionPlayEffect").set("NextAction", ref("Chain_A"));
            });
    ActionRows rows = new ActionRows(tables, new BattleRecords(tables));
    assertThatThrownBy(() -> rows.build("Chain_A", binding(new VariableOwner())))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "Chain_A chains NextAction back to itself, which the loader clears, not modelled");
  }
}
