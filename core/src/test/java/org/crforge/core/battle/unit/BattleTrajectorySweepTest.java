package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Replays forty-eight reference trajectories through {@link Battle} and compares every tick.
 *
 * <p>The five Knight trajectories all start on the bottom side with one set of unit columns. This
 * sweep widens that: sixteen ground units whose speed, attack range, sight range, collision radius
 * and deploy time all differ, deployed at random points on both sides, two thirds of them at a
 * point from which the unit first heads for the king tower and then switches to a princess tower. A
 * change to a cell cost, to the default target rule, to the endpoint scan or to lane assignment
 * moves a route somewhere in here even when it leaves the five Knight walks alone.
 *
 * <p>The trajectories come from the same model as the Knight ones and carry the same caveat: they
 * pin the simulator to the model, not the model to the game. See {@code
 * core/src/test/resources/pathfinding/README.md}.
 */
class BattleTrajectorySweepTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Index of each field within one record of the sweep file. */
  private static final int X = 0;

  private static final int Y = 1;
  private static final int STATE = 2;
  private static final int ROUTE = 3;
  private static final int REFERENCE = 4;

  @TestFactory
  Stream<DynamicTest> everyTrajectoryIsReproducedTickForTick() {
    JsonNode sweep = load("/pathfinding/sweep/trajectories.json");
    List<String> towers = new ArrayList<>();
    sweep.get("towers").forEach(name -> towers.add(name.asText()));

    List<JsonNode> cases = new ArrayList<>();
    sweep.get("cases").forEach(cases::add);
    assertThat(cases).as("the sweep file holds its cases").hasSize(48);
    return cases.stream()
        .map(
            trajectory ->
                DynamicTest.dynamicTest(describe(trajectory), () -> replay(trajectory, towers)));
  }

  private static String describe(JsonNode trajectory) {
    return String.format(
        "%s, side %d, deployed at (%d, %d)",
        trajectory.get("card").asText(),
        trajectory.get("side").asInt(),
        trajectory.get("deploy").get(0).asInt(),
        trajectory.get("deploy").get(1).asInt());
  }

  private static void replay(JsonNode trajectory, List<String> towers) {
    String unitName = trajectory.get("card").asText();

    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity unit =
        match.deploy(
            0,
            GameData.unit(unitName),
            Standard1v1Battle.DEFAULT_LEVEL,
            trajectory.get("side").asInt(),
            trajectory.get("deploy").get(0).asInt(),
            trajectory.get("deploy").get(1).asInt());
    assertThat(unit.getView().getLane()).as("lane").isEqualTo(trajectory.get("lane").asInt());

    // The placement runs at the head of the first step, so reference tick n is battle step n.
    JsonNode records = trajectory.get("records");
    for (int tick = 0; tick < records.size(); tick++) {
      battle.step();
      JsonNode record = records.get(tick);
      TargetView reference = unit.getUnit().targeting().getReference();
      int expectedReference = record.get(REFERENCE).asInt();

      assertThat(unit.getView().getX()).as("tick %d x", tick).isEqualTo(record.get(X).asInt());
      assertThat(unit.getView().getY()).as("tick %d y", tick).isEqualTo(record.get(Y).asInt());
      assertThat(unit.getUnit().movement().getRoute().size())
          .as("tick %d route length", tick)
          .isEqualTo(record.get(ROUTE).asInt());
      assertThat(reference == null ? null : reference.name())
          .as("tick %d reference", tick)
          .isEqualTo(expectedReference < 0 ? null : towers.get(expectedReference));
      if (!endsTheDeployment(records, tick)) {
        assertThat(unit.getView().getState())
            .as("tick %d state", tick)
            .isEqualTo(record.get(STATE).asInt());
      }
    }
    assertThat(unit.getView().getState())
        .as("the unit ends attacking")
        .isEqualTo(GridEntityState.ATTACKING);
  }

  /**
   * True on the last deploying tick. The reference records a deploying unit before its state visit,
   * so on that one tick it still says deploying while the unit, looked at after the whole step, has
   * already left the countdown; the state is not compared there.
   */
  private static boolean endsTheDeployment(JsonNode records, int tick) {
    return records.get(tick).get(STATE).asInt() == GridEntityState.DEPLOYING
        && tick + 1 < records.size()
        && records.get(tick + 1).get(STATE).asInt() != GridEntityState.DEPLOYING;
  }

  /** The card library's units by unit name. */
  /** Every unit of the card library by its unit name, with the first card that deploys it. */
  private static JsonNode load(String resource) {
    try (InputStream stream = BattleTrajectorySweepTest.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Missing test resource " + resource);
      }
      return MAPPER.readTree(stream);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + resource, e);
    }
  }
}
