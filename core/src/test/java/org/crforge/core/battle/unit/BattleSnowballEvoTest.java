package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Snowball's rolling snowball where the reference runs do not take it: a cast for side
 * 1, which aims and rolls down the length, a destination off the map, walked back, and the hidden
 * answer of a captured unit, which nothing in the references attacks.
 */
class BattleSnowballEvoTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The rolling snowball of one cast: its aim as it starts and the destination its roll takes. */
  private record Roll(int aimX, int aimY, int destinationX, int destinationY) {}

  /** Casts the evolved Snowball for a side at a point and runs until its roll has started. */
  private static Roll cast(int side, int x, int y) {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    List<Roll> rolls = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void rollStarted(
                  int tick,
                  ProjectileEntity projectile,
                  String action,
                  int phase,
                  int destinationX,
                  int destinationY) {
                rolls.add(
                    new Roll(
                        projectile.getAimX(), projectile.getAimY(), destinationX, destinationY));
              }
            });
    match.play(0, GameData.card("Snowball_EV1"), LEVEL, side, x, y, "S");
    for (int tick = 0; rolls.isEmpty(); tick++) {
      assertThat(tick).as("the snowball lands and rolls").isLessThan(200);
      match.getBattle().step();
    }
    return rolls.get(0);
  }

  @Test
  @DisplayName(
      "cast for side 1, the rolling snowball is aimed and rolls 4500 down the length from the"
          + " impact")
  void sideOneRollsDownTheLength() {
    assertThat(cast(1, 14500, 12500)).isEqualTo(new Roll(14500, 8000, 14500, 8000));
  }

  @Test
  @DisplayName(
      "a captured Goblin is hidden from the tick after its hiding run first sets the tag, 45, to"
          + " the tick after the snowball leaves, 51, as snowball_ev1_goblins has it")
  void aCapturedUnitIsHiddenWhileItsTagIsSet() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, true);
    int[][] points = {{3000, 22500}, {4000, 22500}, {3000, 23300}, {4000, 23300}};
    List<CharacterEntity> goblins = new ArrayList<>();
    for (int k = 0; k < points.length; k++) {
      goblins.add(
          match.deploy(0, GameData.unit("Goblin"), LEVEL, 1, points[k][0], points[k][1], "G" + k));
    }
    match.play(14, GameData.card("Snowball_EV1"), LEVEL, 0, 3500, 19500, "S");
    List<Integer> hidden = new ArrayList<>();
    for (int tick = 0; tick <= 55; tick++) {
      match.getBattle().step();
      if (goblins.get(0).hidden()) {
        hidden.add(tick);
      }
    }
    assertThat(hidden).containsExactly(45, 46, 47, 48, 49, 50, 51);
  }

  @Test
  @DisplayName(
      "cast near the far end, the destination off the map is walked back 100 at a time to the"
          + " first point not blocked")
  void aDestinationOffTheMapIsWalkedBack() {
    Roll roll = cast(0, 3500, 29000);
    assertThat(roll.destinationX()).isEqualTo(3500);
    assertThat(roll.destinationY()).isEqualTo(30900);
  }
}
