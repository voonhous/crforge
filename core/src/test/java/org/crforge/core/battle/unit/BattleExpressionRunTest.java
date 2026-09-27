package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rows whose expressions call the battle's functions, evaluated where the battle evaluates them.
 *
 * <p>{@code goblin_wave} places the Goblin Hero's second wave around a Knight of each side on tick
 * 30, by {@code team_y_direction(team_index)} and {@code map_width}; its spawns set a creation flag
 * the battle does not model yet, so only the points the rows work out are held here, on the
 * battle's own Knights as they stand when the rows run.
 */
class BattleExpressionRunTest {

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
}
