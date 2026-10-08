package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a unit walks toward once a pushback's flight has ended. The game drops the route the unit
 * held when the flight ends, so its next walk searches a fresh route from where the pushback left
 * it, rather than walking back toward the waypoint it held before the push.
 *
 * <p>The scene: the bottom side's Monk walks up the left lane, the top side's Giant comes down it,
 * and the Monk's third hit pushes the Giant sideways, the flight starting on tick 417. The flight's
 * budget runs out on tick 428 (the Giant stands) and its last visit on tick 429 steps it 25 units
 * back; tick 430 is its first walking step.
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
      "a pushback's end drops the Giant's route, and its first step"
          + " follows a fresh route from where the push left it")
  void theEndOfThePushbackDropsTheRoute() {
    assertTheRouteIsDropped(scene(GameData.tables()));
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
}
