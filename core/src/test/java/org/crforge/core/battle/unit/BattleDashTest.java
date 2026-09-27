package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a dash does to its dasher beyond its path, and what the battle refuses of a dash. */
class BattleDashTest {

  /** A dasher placed for the bottom side with a red Knight ahead of it, the towers passive. */
  private static final class Scene {
    final Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    final CharacterEntity dasher;

    Scene(String row) {
      dasher = match.deploy(0, GameData.unit(row), 11, 0, 3500, 10000, "Dasher");
      match.deploy(0, GameData.unit("Knight"), 11, 1, 3500, 18000, "Knight");
    }

    /** Steps until the dasher is in the given state, and answers the tick it got there. */
    int stepUntil(int state) {
      for (int tick = 0; tick < 200; tick++) {
        match.getBattle().step();
        if (dasher.getView().getState() == state) {
          return tick;
        }
      }
      throw new AssertionError("the dasher never reached state " + state);
    }
  }

  @Test
  @DisplayName(
      "a Bandit is untouchable while it dashes and for its immunity after, 100 ms, then not")
  void theBanditIsImmuneWhileItDashesAndJustAfter() {
    Scene scene = new Scene("Assassin");
    scene.stepUntil(GridEntityState.DASHING);
    assertThat(scene.dasher.untouchable()).isTrue();

    scene.stepUntil(GridEntityState.MOVING);
    // The immunity was topped up to 100 on its last dashing visit and counts down 50 a visit.
    assertThat(scene.dasher.untouchable()).isTrue();
    scene.match.getBattle().step();
    assertThat(scene.dasher.untouchable()).isFalse();
  }

  @Test
  @DisplayName("a Mega Knight, with no dash immunity, can be hurt while it dashes")
  void theMegaKnightCanBeHurtWhileItDashes() {
    Scene scene = new Scene("MegaKnight");
    scene.stepUntil(GridEntityState.DASHING);

    assertThat(scene.dasher.untouchable()).isFalse();
  }

  @Test
  @DisplayName("a Mega Knight's landing holds it in the dashing state until its landing time")
  void theMegaKnightIsHeldAfterItLands() {
    Scene scene = new Scene("MegaKnight");
    scene.stepUntil(GridEntityState.DASHING);
    while (scene.dasher.getView().getBlockCountdownMs() == 0) {
      scene.match.getBattle().step();
    }
    // The landing's own state visit takes the hold from 50 to 100; three more visits stand still
    // at 150, 200 and 250, and the fourth reaches the landing time of 300 and walks on.
    int held = 0;
    while (true) {
      scene.match.getBattle().step();
      held++;
      if (scene.dasher.getView().getState() != GridEntityState.DASHING) {
        break;
      }
      assertThat(scene.dasher.getSpeedBudget()).isZero();
    }
    assertThat(held).isEqualTo(4);
    assertThat(scene.dasher.getView().getState()).isEqualTo(GridEntityState.MOVING);
    assertThat(scene.dasher.getView().getBlockCountdownMs()).isZero();
  }

  @Test
  @DisplayName("a chained dash and a dash's contact damage are refused as the row is created")
  void unestablishedDashesAreRefused() {
    BattleWorld world =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false).getWorld();
    assertThatThrownBy(
            () ->
                new CharacterEntity(
                    world, GameData.unit("GoldenKnight"), "Knight", 0, 3500, 10000, 11))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("DashCount");
  }
}
