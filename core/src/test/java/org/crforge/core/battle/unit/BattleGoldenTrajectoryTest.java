package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.crforge.core.battle.Battle;
import org.crforge.core.card.UnitDataMapper;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drives a whole Knight deployment through {@link Battle} - the battle step, the holder tick and
 * the character's components, with nothing else in the loop - and compares every tick with a
 * reference trajectory.
 *
 * <p>The reference trajectories are the same five the grid pathfinding mode is held to; see {@code
 * core/src/test/resources/pathfinding/README.md} for what they are and are not.
 *
 * <p>Tick alignment: the placement is a command due on tick 0. Commands run at the head of a step,
 * before the entity tick, whose opening cleanup admits the Knight, so it is first visited on tick
 * 0. Reference tick {@code n} is therefore battle tick {@code n}, with nothing shifted.
 *
 * <p>One state offset is corrected for here rather than hidden: the reference records a deploying
 * unit <b>before</b> its state visit and every other unit after it, while this test looks at the
 * unit after the whole step. {@link #expectedState} applies exactly that one-tick correction.
 */
class BattleGoldenTrajectoryTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("a Knight deployed on the left walks the left lane and locks on at tick 235")
  void leftDeployment() {
    replay("knight_left", "PrincessTower_1_1", 235);
  }

  @Test
  @DisplayName("a Knight deployed on the right walks the right lane and locks on at tick 235")
  void rightDeployment() {
    replay("knight_right", "PrincessTower_1_2", 235);
  }

  @Test
  @DisplayName("a Knight deployed in the centre switches tower and locks on at tick 245")
  void centreDeployment() {
    replay("knight_centre", "PrincessTower_1_2", 245);
  }

  @Test
  @DisplayName(
      "a Knight deployed behind the right tower is steered around it and locks on at tick 326")
  void rightRearDeployment() {
    replay("knight_right_rear", "PrincessTower_1_2", 326);
  }

  @Test
  @DisplayName(
      "a Knight deployed behind its king tower is pushed while deploying and locks at tick 361")
  void behindKingDeployment() {
    replay("knight_behind_king", "PrincessTower_1_2", 361);
  }

  @Test
  @DisplayName(
      "a Knight deployed inside the left lane near the middle is kept to its lane's tower for its"
          + " first ten walking ticks, then walks at the king until the princess tower is closer")
  void innerLeftDeployment() {
    // The king tower is the closest in x from (8000, 10000), but a unit in its first ten walking
    // ticks only considers the towers of its own lane, so the left princess tower is taken on
    // tick 20, the king on tick 30, and the princess tower again from tick 68.
    replay("knight_left_inner", "PrincessTower_1_1", 259);
  }

  /**
   * Runs one reference case end to end.
   *
   * @param caseName the trajectory resource to replay
   * @param lockedOnto the tower the unit is expected to be attacking at the end
   * @param lockTick the reference tick on which the unit enters the attacking state
   */
  private void replay(String caseName, String lockedOnto, int lockTick) {
    JsonNode golden = load("/pathfinding/golden/" + caseName + ".json");
    List<JsonNode> records = new ArrayList<>();
    golden.get("records").forEach(records::add);

    UnitData knight =
        UnitDataMapper.toUnitData(
            Objects.requireNonNull(CardRegistry.get("knight"), "knight not found"));
    Standard1v1Battle match = new Standard1v1Battle(Standard1v1Battle.DEFAULT_LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity unit =
        match.deploy(
            0,
            knight,
            Standard1v1Battle.DEFAULT_LEVEL,
            golden.get("side").asInt(),
            golden.get("deploy").get(0).asInt(),
            golden.get("deploy").get(1).asInt());

    // The placement command runs at the head of the first step and hands the Knight to the holder,
    // whose opening cleanup admits it: reference tick n is battle step n.
    for (int i = 0; i < records.size(); i++) {
      battle.step();
      if (i == 0) {
        assertThat(unit.getId()).as("the seventh character").isEqualTo(5000006);
        assertThat(battle.getHolder().entities()).as("admitted and visited").contains(unit);
      }
      JsonNode record = records.get(i);
      String where = caseName + " reference tick " + record.get("tick").asInt();

      assertThat(unit.getId())
          .as("%s: the Knight follows the six towers in the character band", where)
          .isEqualTo(5000006);
      assertThat(unit.getView().getX()).as("%s x", where).isEqualTo(record.get("x").asInt());
      assertThat(unit.getView().getY()).as("%s y", where).isEqualTo(record.get("y").asInt());
      assertThat(unit.getView().getState())
          .as("%s state", where)
          .isEqualTo(expectedState(records, i));
      assertThat(unit.getUnit().movement().getRoute().size())
          .as("%s route length", where)
          .isEqualTo(record.get("route").asInt());
      assertThat(referenceName(unit))
          .as("%s reference", where)
          .isEqualTo(record.get("ref").isNull() ? null : record.get("ref").asText());
    }

    JsonNode last = records.get(records.size() - 1);
    assertThat(last.get("tick").asInt()).as("%s lock tick", caseName).isEqualTo(lockTick);
    assertThat(battle.getTick()).as("%s battle tick", caseName).isEqualTo(lockTick + 1);
    assertThat(battle.getClockMs()).isEqualTo((lockTick + 1) * Battle.STEP_MS);
    assertThat(unit.getView().getState())
        .as("%s stands in the attacking state", caseName)
        .isEqualTo(GridEntityState.ATTACKING);
    assertThat(referenceName(unit)).as("%s locked target", caseName).isEqualTo(lockedOnto);
  }

  /** The name of the tower the unit currently holds a reference to, or null. */
  private static String referenceName(CharacterEntity unit) {
    TargetView reference = unit.getUnit().targeting().getReference();
    return reference == null ? null : reference.name();
  }

  /**
   * The state the unit holds at the end of the step that produced the given record.
   *
   * <p>Equal to the recorded state everywhere except on the last deploying tick, where the state
   * visit that ends the deployment has already run by the time the step returns.
   */
  static int expectedState(List<JsonNode> records, int index) {
    int recorded = records.get(index).get("state").asInt();
    if (recorded != GridEntityState.DEPLOYING) {
      return recorded;
    }
    boolean lastDeployingTick =
        index + 1 < records.size()
            && records.get(index + 1).get("state").asInt() != GridEntityState.DEPLOYING;
    return lastDeployingTick ? GridEntityState.MOVING : GridEntityState.DEPLOYING;
  }

  private static JsonNode load(String resource) {
    try (InputStream stream = BattleGoldenTrajectoryTest.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Missing test resource " + resource);
      }
      return MAPPER.readTree(stream);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + resource, e);
    }
  }
}
