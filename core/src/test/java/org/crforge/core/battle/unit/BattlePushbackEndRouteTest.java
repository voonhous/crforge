package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.data.GameVersions;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a unit walks toward once a pushback's flight has ended. The game of data version 16.402.18
 * drops the route the unit held when the flight ends, so its next walk searches a fresh route from
 * where the pushback left it; the game of 14.593.1 keeps the route and walks back toward the
 * waypoint it held before the push.
 *
 * <p>The scene: the bottom side's Monk walks up the left lane, the top side's Giant comes down it,
 * and the Monk's third hit pushes the Giant sideways, the flight starting on tick 417. The flight's
 * budget runs out on tick 428 (the Giant stands) and its last visit on tick 429 steps it 25 units
 * back; tick 430 is its first walking step. The same battle runs on the configured tables and on
 * those tables relabelled as data version 16.402.18 or 16.426.22, which differ only in the
 * version's rule. Both are data versions of game client 16.402.17, whose rule it is.
 */
class BattlePushbackEndRouteTest {

  /** The battle stream's seed of the scene. */
  private static final int SEED = 1131;

  /** The levels the cards are played at: a champion's and a rare card's first level. */
  private static final int MONK_LEVEL = 11;

  private static final int GIANT_LEVEL = 3;

  /** The tick of the flight's last visit: the 25 step back. */
  private static final int FLIGHT_END = 429;

  /** The width of the routing grid, in cells, which turns a cell into a route node. */
  private static final int WIDTH = 36;

  /** The routing cell the Giant headed for before the push: column 8, row 36. */
  private static final int HELD_WAYPOINT = 36 * WIDTH + 8;

  @TempDir Path folder;

  /**
   * The configured tables copied into a folder with every file labelled as another data version.
   *
   * @param folder the folder to copy them into
   * @param version the data version the copy is labelled with
   */
  private static GameTables relabelled(Path folder, String version) throws IOException {
    Path source = GameTables.configuredDirectory().orElseThrow();
    ObjectMapper mapper = new ObjectMapper();
    try (Stream<Path> files = Files.list(source)) {
      for (Path file : files.toList()) {
        Path copy = folder.resolve(file.getFileName());
        if (file.getFileName().toString().endsWith(".json")) {
          ObjectNode document = (ObjectNode) mapper.readTree(file.toFile());
          document.put("version", version);
          mapper.writeValue(copy.toFile(), document);
        } else {
          Files.copy(file, copy);
        }
      }
    }
    return GameTables.load(folder);
  }

  /** The towers at the first level, fighting; the Monk and the Giant played. */
  private static Standard1v1Battle scene(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, 1, true);
    match.getWorld().seed(SEED);
    match.play(220, GameData.card("Monk"), MONK_LEVEL, 0, 3500, 14000, "M");
    match.play(300, GameData.card("Giant"), GIANT_LEVEL, 1, 3500, 21500, "G");
    return match;
  }

  /** Steps the battle until its tick is the given one. */
  private static void stepTo(Standard1v1Battle match, int tick) {
    while (match.getBattle().getTick() < tick) {
      match.getBattle().step();
    }
  }

  /** The Giant, the second play's unit. */
  private static CharacterEntity giant(Standard1v1Battle match) {
    return match.getPlays().get(1).units().get(0);
  }

  @Test
  @DisplayName(
      "on data version 16.402.18 a pushback's end drops the Giant's route, and its first step"
          + " follows a fresh route from where the push left it")
  void theEndOfThePushbackDropsTheRoute() throws IOException {
    assertTheRouteIsDropped(scene(relabelled(folder, GameVersions.DATA_16_402_18)));
  }

  @Test
  @DisplayName(
      "on data version 16.426.22, which the same game client runs, a pushback's end drops the"
          + " Giant's route too")
  void theEndOfThePushbackDropsTheRouteOnTheNewerDataOfTheSameClient() throws IOException {
    assertTheRouteIsDropped(scene(relabelled(folder, GameVersions.DATA_16_426_22)));
  }

  /** The Giant's route dropped as its flight ends, and its next step on a fresh route. */
  private static void assertTheRouteIsDropped(Standard1v1Battle match) {
    stepTo(match, FLIGHT_END - 1);
    MovementState movement = giant(match).getUnit().movement();
    assertThat(movement.getPushbackInFlight()).as("in flight, standing").isEqualTo(1);
    assertThat(movement.getRoute().last()).as("the route held").isEqualTo(HELD_WAYPOINT);

    stepTo(match, FLIGHT_END);
    assertThat(movement.getPushbackInFlight()).as("the flight ended").isZero();
    assertThat(giant(match).getView().getX()).as("stepped back").isEqualTo(5882);
    assertThat(giant(match).getView().getY()).isEqualTo(19491);
    assertThat(movement.getRoute().isEmpty()).as("the route dropped").isTrue();
    assertThat(movement.getRouteLeadsAway()).isZero();

    stepTo(match, FLIGHT_END + 1);
    assertThat(movement.getRoute().last())
        .as("a fresh route's next cell: column 9, row 36")
        .isEqualTo(36 * WIDTH + 9);
    assertThat(giant(match).getView().getX()).isEqualTo(5849);
    assertThat(giant(match).getView().getY()).isEqualTo(19452);
  }

  @Test
  @DisplayName(
      "on data version 14.593.1 the Giant keeps its route through a pushback's end and walks back"
          + " toward the waypoint it held")
  void theRouteIsKeptOnTheOlderVersion() {
    assertThat(GameData.tables().version()).isEqualTo(GameVersions.DATA_14_593_1);
    Standard1v1Battle match = scene(GameData.tables());
    stepTo(match, FLIGHT_END);
    MovementState movement = giant(match).getUnit().movement();
    assertThat(movement.getPushbackInFlight()).as("the flight ended").isZero();
    assertThat(giant(match).getView().getX()).isEqualTo(5882);
    assertThat(movement.getRoute().last()).as("the route kept").isEqualTo(HELD_WAYPOINT);

    stepTo(match, FLIGHT_END + 1);
    assertThat(movement.getRoute().last()).isEqualTo(HELD_WAYPOINT);
    assertThat(giant(match).getView().getX()).isEqualTo(5841);
    assertThat(giant(match).getView().getY()).isEqualTo(19460);
  }
}
