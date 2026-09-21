package org.crforge.core.pathfinding;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.crforge.core.card.Card;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.PathfindingMode;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.LevelConfig;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drives a whole Knight deployment through {@link GameEngine} in grid mode and compares every tick
 * with a reference trajectory.
 *
 * <p>The reference trajectories are five deployments of a Knight on the standard arena with nothing
 * on it but the six crown towers, produced by a model of the game's movement and targeting rules.
 * Each record holds the unit's position, its state, the tower it was heading for and how many nodes
 * of its route were left, at the point in the tick where the reference recorded it.
 *
 * <p>Two of the five - {@code knight_right_rear} and {@code knight_behind_king} - deploy the unit
 * right beside one of its own towers, so that the passes which look at the unit's neighbours are
 * covered end to end and not only on trajectories that never come near a building.
 *
 * <p>Tick alignment: the reference's tick 0 is the first tick in which the unit exists and its
 * deploy countdown steps. In the engine that is the first {@code tick()} after the unit was
 * spawned, because a spawn is flushed at the head of the following tick. So engine tick number
 * {@code n + 1} is reference tick {@code n}, and the loop below asserts one record per engine tick
 * starting with the first.
 *
 * <p>One state offset is unavoidable and is corrected for here rather than hidden: the reference
 * records a deploying unit <b>before</b> its state visit and every other unit after it. On the tick
 * whose state visit ends the deployment the reference therefore still says "deploying" while the
 * engine, asked after the whole tick, already says "moving". {@link #expectedState} applies exactly
 * that one-tick correction and nothing else.
 */
class GridGoldenTrajectoryTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The level both the towers and the Knight are scaled to in the reference trajectories. */
  private static final int LEVEL = 11;

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
  @DisplayName("a Knight deployed behind the right tower walks past it and locks on at tick 323")
  void rightRearDeployment() {
    replay("knight_right_rear", "PrincessTower_1_2", 323);
  }

  @Test
  @DisplayName("a Knight deployed behind its king tower keeps the right lane and locks at tick 368")
  void behindKingDeployment() {
    replay("knight_behind_king", "PrincessTower_1_2", 368);
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

    AbstractEntity.resetIdCounter();
    List<Card> deckCards = new ArrayList<>();
    for (String id :
        List.of(
            "knight", "giant", "musketeer", "archer", "goblins", "valkyrie", "bomber", "minions")) {
      deckCards.add(Objects.requireNonNull(CardRegistry.get(id), id + " not found"));
    }
    Player blue = new Player(Team.BLUE, new Deck(deckCards), false, new LevelConfig(LEVEL));
    Player red =
        new Player(Team.RED, new Deck(new ArrayList<>(deckCards)), false, new LevelConfig(LEVEL));
    Standard1v1Match match = new Standard1v1Match(LEVEL, PathfindingMode.GRID);
    match.addPlayer(blue);
    match.addPlayer(red);
    GameEngine engine = new GameEngine();
    engine.setMatch(match);
    engine.initMatch();

    Card knight = Objects.requireNonNull(CardRegistry.get("knight"), "knight not found");
    int deployX = golden.get("deploy").get(0).asInt();
    int deployY = golden.get("deploy").get(1).asInt();
    // Spawned directly rather than played from hand: that skips the placement sync delay and the
    // elixir cost, so the unit's first countdown tick is the engine's next tick.
    engine
        .getSpawnerSystem()
        .spawnUnit(
            deployX,
            deployY,
            Team.BLUE,
            knight.getUnitStats(),
            LEVEL,
            knight.getUnitStats().getDeployTime());

    Troop troop = null;
    for (int i = 0; i < records.size(); i++) {
      engine.tick();
      if (troop == null) {
        troop = findKnight(engine);
        assertThat(troop).as("the Knight was spawned").isNotNull();
      }
      JsonNode record = records.get(i);
      int tick = record.get("tick").asInt();
      String where = caseName + " reference tick " + tick;
      GridUnitState unit = troop.getGridUnitState();
      assertThat(unit).as("%s has grid state", where).isNotNull();

      assertThat(troop.getPosition().getX()).as("%s x", where).isEqualTo(record.get("x").asInt());
      assertThat(troop.getPosition().getY()).as("%s y", where).isEqualTo(record.get("y").asInt());
      assertThat(unit.entity().getState())
          .as("%s state", where)
          .isEqualTo(expectedState(records, i));
      assertThat(unit.movement().getRoute().size())
          .as("%s route length", where)
          .isEqualTo(record.get("route").asInt());
      assertThat(name(troop.getCombat().getCurrentTarget(), engine))
          .as("%s reference", where)
          .isEqualTo(record.get("ref").isNull() ? null : record.get("ref").asText());
    }

    JsonNode last = records.get(records.size() - 1);
    assertThat(last.get("tick").asInt()).as("%s lock tick", caseName).isEqualTo(lockTick);
    assertThat(troop.getGridUnitState().entity().getState())
        .as("%s stands in the attacking state", caseName)
        .isEqualTo(GridEntityState.ATTACKING);
    assertThat(name(troop.getCombat().getCurrentTarget(), engine))
        .as("%s locked target", caseName)
        .isEqualTo(lockedOnto);
    assertThat(troop.getCombat().isTargetLocked()).as("%s locked", caseName).isTrue();
    assertThat(troop.getPosition().getX()).isEqualTo(last.get("x").asInt());
    assertThat(troop.getPosition().getY()).isEqualTo(last.get("y").asInt());
  }

  /**
   * The grid state the engine holds at the end of the tick that produced the given record.
   *
   * <p>Equal to the recorded state everywhere except on the last deploying tick, where the state
   * visit that ends the deployment has already run by the time the engine tick returns.
   */
  private static int expectedState(List<JsonNode> records, int index) {
    int recorded = records.get(index).get("state").asInt();
    if (recorded != GridEntityState.DEPLOYING) {
      return recorded;
    }
    boolean lastDeployingTick =
        index + 1 < records.size()
            && records.get(index + 1).get("state").asInt() != GridEntityState.DEPLOYING;
    return lastDeployingTick ? GridEntityState.MOVING : GridEntityState.DEPLOYING;
  }

  /** The first live Knight in the match. */
  private static Troop findKnight(GameEngine engine) {
    for (Entity entity : engine.getGameState().getAliveEntities()) {
      if (entity instanceof Troop troop && "Knight".equals(troop.getName())) {
        return troop;
      }
    }
    return null;
  }

  /**
   * The reference trajectory's name for a tower: the side, then the placement index, with the
   * princess towers numbered by position along the arena's width.
   */
  private static String name(Entity entity, GameEngine engine) {
    if (entity == null) {
      return null;
    }
    if (!(entity instanceof Tower tower)) {
      return entity.getName();
    }
    int side = tower.getTeam() == Team.BLUE ? 0 : 1;
    if (tower.isCrownTower()) {
      return "KingTower_" + side + "_0";
    }
    List<Tower> princesses =
        new ArrayList<>(engine.getGameState().getPrincessTowers(tower.getTeam()));
    princesses.sort(Comparator.comparingInt(t -> t.getPosition().getX()));
    return "PrincessTower_" + side + "_" + (princesses.indexOf(tower) + 1);
  }

  private static JsonNode load(String resource) {
    try (InputStream stream = GridGoldenTrajectoryTest.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Missing test resource " + resource);
      }
      return MAPPER.readTree(stream);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + resource, e);
    }
  }
}
