package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.BattleAction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Plays the runs that schedule a row straight onto a placed unit, in the command pass of its tick
 * with the unit as its cause, and whose rows choose between branches, and holds the battle to them.
 *
 * <p>In {@code chef_filter} the Royal Chef's pancake filter is scheduled on a MiniPekka, a Knight
 * and a SuperMiniPekka on tick 5: its condition {@code has_data(MiniPekka)} holds on the MiniPekka
 * alone, whose branch runs at once inside the filter. In {@code bandit_greeting} the Boss Bandit's
 * greeting check is scheduled on a Knight on ticks 5 and 8: on 5 it counts the Knight's side's
 * troops and finds a RascalGirl, so it greets the rascals; on 8 a Firecracker placed since vetoes
 * that, and the check it runs instead counts two of the forest gang and greets them.
 */
class BattleScheduledRowRunTest {

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"chef_filter", "bandit_greeting"})
  void theRunMatchesTheReference(String name) {
    JsonNode reference = BattleMusketeerRunTest.load("/pathfinding/golden/" + name + ".json");
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), reference.get("tower_level").asInt());
    Battle battle = match.getBattle();
    List<CharacterEntity> units = BattleTowerRunTest.deployAll(match, reference);
    int[] currentTick = {-1};
    // Runs are listed as they start, in the reference's order; one that starts inside another's
    // start ran at once, which the reference writes without a phase.
    int[] depth = {0};
    List<String> runs = new ArrayList<>();
    Map<String, CharacterEntity> byName = new HashMap<>();
    for (CharacterEntity unit : units) {
      byName.put(unit.name(), unit);
      unit.actionHolder()
          .setListener(
              new ActionHolder.Listener() {
                @Override
                public void starting(BattleAction action, int phase) {
                  runs.add(
                      "%d %s %s %s"
                          .formatted(
                              currentTick[0],
                              unit.name(),
                              action.name(),
                              depth[0] > 0 ? "at once" : phase));
                  depth[0]++;
                }

                @Override
                public void started(BattleAction action, int phase) {
                  depth[0]--;
                }
              });
    }
    List<JsonNode> records = BattleMusketeerRunTest.records(reference);
    Map<Integer, List<JsonNode>> otherRecords = BattleTowerRunTest.otherRecordsByTick(reference);
    CharacterEntity unit = units.get(0);

    for (JsonNode record : records) {
      int tick = record.get("tick").asInt();
      currentTick[0] = tick;
      battle.step();
      assertOutside(unit, record, tick);
      for (JsonNode other : otherRecords.getOrDefault(tick, List.of())) {
        assertOutside(byName.get(other.get("name").asText()), other, tick);
      }
    }

    List<String> expected = new ArrayList<>();
    for (JsonNode a : reference.get("actions")) {
      if (a.get("event").asText().equals("run")) {
        expected.add(
            "%d %s %s %s"
                .formatted(
                    a.get("tick").asInt(),
                    a.get("owner").asText(),
                    a.get("action").asText(),
                    a.get("phase").isNull() ? "at once" : a.get("phase").asText()));
      }
    }
    assertThat(runs).as("every run of an action").containsExactlyElementsOf(expected);
  }

  /** Holds a unit to its record's outside: position, state and its own hit points. */
  private static void assertOutside(CharacterEntity unit, JsonNode record, int tick) {
    String where = "tick " + tick + ": " + unit.name();
    assertThat(unit.getView().getX()).as("%s x", where).isEqualTo(record.get("x").asInt());
    assertThat(unit.getView().getY()).as("%s y", where).isEqualTo(record.get("y").asInt());
    assertThat(unit.getView().getState())
        .as("%s state", where)
        .isEqualTo(record.get("state").asInt());
    assertThat(unit.getHitPoints().getHitPoints())
        .as("%s own hit points", where)
        .isEqualTo(record.get("own_hp").asInt());
  }
}
