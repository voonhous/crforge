package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.BattleAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plays {@code golemite_convert}: a crazy-arena baby golemite placed directly, whose row's starting
 * group stores its hit points' share of its maximum 70 ticks later, then turns it into an Elixir
 * Golem and heals it back to that share of the new maximum, all in one phase-1 pass.
 *
 * <p>The golemite walks at its own speed until the swap and at the Elixir Golem's from the movement
 * visit of the same tick; it keeps its hit points through the swap, its maximum is the new row's at
 * its level, and the tower it was walking at is kept as its target. It dies on tick 110, where the
 * Elixir Golem's row spawns two golemites, which the reference does not make and the battle
 * refuses: the run is held through tick 109, and the death is refused.
 */
class BattleChangeDataRunTest {

  private static final String REFERENCE = "/pathfinding/golden/golemite_convert.json";

  /** The tick the swapped unit dies on, whose death spawn the battle does not model. */
  private static final int DEATH_TICK = 110;

  @Test
  @DisplayName(
      "the golemite turns into an Elixir Golem mid-walk, keeps its hit points and target, is healed"
          + " to its share, and walks on at the new speed")
  void theRunMatchesTheReferenceUpToTheDeath() {
    JsonNode reference = BattleMusketeerRunTest.load(REFERENCE);
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), reference.get("tower_level").asInt());
    Battle battle = match.getBattle();
    CharacterEntity unit = BattleTowerRunTest.deployAll(match, reference).get(0);
    int[] currentTick = {-1};
    List<String> runs = new ArrayList<>();
    unit.actionHolder()
        .setListener(
            new ActionHolder.Listener() {
              @Override
              public void starting(BattleAction action, int phase, boolean queued) {
                runs.add(
                    "%d %s %s"
                        .formatted(currentTick[0], action.name(), queued ? phase : "at once"));
              }
            });
    List<String> events = new ArrayList<>();
    match.getWorld().addObserver(BattleTowerRunTest.eventCollector(currentTick, events));
    List<JsonNode> records = BattleMusketeerRunTest.records(reference);

    for (int i = 0; i < records.size(); i++) {
      JsonNode record = records.get(i);
      int tick = record.get("tick").asInt();
      if (tick == DEATH_TICK) {
        break;
      }
      currentTick[0] = tick;
      battle.step();
      String where = "reference tick " + tick;
      assertThat(unit.getView().getX()).as("%s x", where).isEqualTo(record.get("x").asInt());
      assertThat(unit.getView().getY()).as("%s y", where).isEqualTo(record.get("y").asInt());
      assertThat(unit.getView().getState())
          .as("%s state", where)
          .isEqualTo(BattleGoldenTrajectoryTest.expectedState(records, i));
      assertThat(BattleMusketeerRunTest.referenceName(unit))
          .as("%s reference", where)
          .isEqualTo(BattleMusketeerRunTest.expectedReference(record));
      assertThat(unit.getUnit().movement().getRoute().size())
          .as("%s route length", where)
          .isEqualTo(record.get("route").asInt());
      if (!record.get("speed").isNull()) {
        assertThat(unit.getSpeedBudget())
            .as("%s movement budget", where)
            .isEqualTo(record.get("speed").asInt());
      }
      assertThat(unit.getHitPoints().getHitPoints())
          .as("%s the unit's own hit points", where)
          .isEqualTo(record.get("own_hp").asInt());
      if (tick == 70) {
        assertThat(unit.getData().name()).isEqualTo("ElixirGolem2");
        assertThat(unit.getHitPoints().getMaximum()).isEqualTo(762);
        assertThat(unit.getView().getCollisionRadius()).isEqualTo(500);
        assertThat(unit.variable(match.getWorld().declaredVariable("ElixirGolem2_crazy_maxHP")))
            .isEqualTo(39);
      }
    }

    currentTick[0] = DEATH_TICK;
    assertThatThrownBy(battle::step)
        .as("the Elixir Golem's death spawn is not modelled")
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("DeathSpawnCharacter");

    List<String> expectedRuns = new ArrayList<>();
    for (JsonNode a : reference.get("actions")) {
      if (a.get("event").asText().equals("run")) {
        expectedRuns.add(
            "%d %s %s"
                .formatted(
                    a.get("tick").asInt(),
                    a.get("action").asText(),
                    a.get("phase").isNull() ? "at once" : a.get("phase").asText()));
      }
    }
    assertThat(runs).as("every run of an action").containsExactlyElementsOf(expectedRuns);
    List<String> expectedEvents = new ArrayList<>();
    for (JsonNode event : reference.get("events")) {
      if (event.get("tick").asInt() < DEATH_TICK) {
        expectedEvents.add(BattleTowerRunTest.eventLine(event));
      }
    }
    assertThat(events)
        .as("every launch and impact before the death")
        .containsExactlyElementsOf(expectedEvents);
  }
}
