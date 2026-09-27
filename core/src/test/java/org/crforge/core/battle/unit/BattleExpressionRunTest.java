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
import org.crforge.core.battle.data.GameAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rows whose expressions call the battle's functions, evaluated where the battle evaluates them.
 *
 * <p>In {@code chef_filter} the Royal Chef's pancake filter is scheduled on a MiniPekka, a Knight
 * and a SuperMiniPekka on tick 5: its condition {@code has_data(MiniPekka)} holds on the MiniPekka
 * alone, whose branch runs at once inside the filter. {@code goblin_wave} places the Goblin Hero's
 * second wave around a Knight of each side on tick 30, by {@code team_y_direction(team_index)} and
 * {@code map_width}; its spawns set a creation flag the battle does not model yet, so only the
 * points the rows work out are held here, on the battle's own Knights as they stand when the rows
 * run.
 */
class BattleExpressionRunTest {

  private static final String CHEF_FILTER = "/pathfinding/golden/chef_filter.json";

  @Test
  @DisplayName(
      "the pancake filter takes its branch on the MiniPekka alone, at once, and every unit holds"
          + " to its record")
  void chefFilter() {
    JsonNode reference = BattleMusketeerRunTest.load(CHEF_FILTER);
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), reference.get("tower_level").asInt());
    Battle battle = match.getBattle();
    List<CharacterEntity> units = BattleTowerRunTest.deployAll(match, reference);
    int[] currentTick = {-1};
    List<String> runs = new ArrayList<>();
    Map<String, CharacterEntity> byName = new HashMap<>();
    for (CharacterEntity unit : units) {
      byName.put(unit.name(), unit);
      unit.actionHolder()
          .setListener(
              new ActionHolder.Listener() {
                @Override
                public void started(BattleAction action, int phase) {
                  runs.add(
                      "%d %s %s %d".formatted(currentTick[0], unit.name(), action.name(), phase));
                }
              });
    }
    List<JsonNode> records = BattleMusketeerRunTest.records(reference);
    Map<Integer, List<JsonNode>> otherRecords = BattleTowerRunTest.otherRecordsByTick(reference);
    CharacterEntity miniPekka = units.get(0);

    for (JsonNode record : records) {
      int tick = record.get("tick").asInt();
      currentTick[0] = tick;
      battle.step();
      assertOutside(miniPekka, record, tick);
      for (JsonNode other : otherRecords.getOrDefault(tick, List.of())) {
        assertOutside(byName.get(other.get("name").asText()), other, tick);
      }
    }

    assertThat(runs)
        .as("every run of an action")
        .containsExactlyElementsOf(expectedRuns(reference));
  }

  @Test
  @DisplayName(
      "the Goblin Hero's second wave stands ahead of and behind a Knight of either side, mirrored"
          + " across the middle of the arena")
  void goblinWavePoints() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), 11);
    Battle battle = match.getBattle();
    CharacterEntity blue = match.deploy(0, GameData.unit("Knight"), 11, 0, 4000, 10000);
    CharacterEntity red =
        match.deploy(0, GameData.unit("Knight"), 11, 1, 14000, 22000, "KnightRed");
    // The rows run in the Knights' phase-1 passes of tick 30, before either moves: where each stood
    // at the end of tick 29.
    for (int tick = 0; tick < 30; tick++) {
      battle.step();
    }
    assertThat(List.of(blue.getView().getX(), blue.getView().getY())).containsExactly(4104, 10581);
    assertThat(List.of(red.getView().getX(), red.getView().getY())).containsExactly(14115, 21429);

    // The points goblin_wave's spawns were created at, by row.
    assertThat(wavePoints(match, blue))
        .containsExactly("5104 11581", "3104 11581", "4104 9581", "3104 9581");
    assertThat(wavePoints(match, red))
        .containsExactly("13115 20429", "15115 20429", "14115 22429", "15115 22429");
  }

  /** The point each of the four wave rows works out on a unit, in row order. */
  private static List<String> wavePoints(Standard1v1Battle match, CharacterEntity unit) {
    List<String> points = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      GameAction row = GameData.tables().action("GoblinHero_Spawn_Second_Wave_" + i);
      int x =
          match
              .getWorld()
              .binding(unit)
              .expression(row.fields().get("XPositionExpression").asText())
              .getAsInt();
      int y =
          match
              .getWorld()
              .binding(unit)
              .expression(row.fields().get("YPositionExpression").asText())
              .getAsInt();
      points.add(x + " " + y);
    }
    return points;
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

  /**
   * The reference's runs as the battle's holders report them. A run the reference lists without a
   * phase started at once inside the run before it, on the same owner and in the same tick; a
   * holder reports a run once its start returns, so that one is reported first, in the phase of the
   * pass it ran in.
   */
  private static List<String> expectedRuns(JsonNode reference) {
    List<String> expected = new ArrayList<>();
    int parent = -1;
    int parentPhase = 0;
    for (JsonNode a : reference.get("actions")) {
      if (!a.get("event").asText().equals("run")) {
        continue;
      }
      String prefix = a.get("tick").asInt() + " " + a.get("owner").asText() + " ";
      if (a.get("phase").isNull()) {
        expected.add(parent, prefix + a.get("action").asText() + " " + parentPhase);
        parent++;
      } else {
        parentPhase = a.get("phase").asInt();
        expected.add(prefix + a.get("action").asText() + " " + parentPhase);
        parent = expected.size() - 1;
      }
    }
    return expected;
  }
}
