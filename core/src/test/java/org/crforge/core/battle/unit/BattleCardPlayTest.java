package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A player's card play is stamped with the battle's tick counter when it is submitted, 1 while the
 * counter is still 0, and runs {@link Standard1v1Battle#PLAY_DELAY_TICKS} ticks after that stamp.
 */
class BattleCardPlayTest {

  private static DeployCard knight() {
    return GameData.card("Knight");
  }

  @Test
  @DisplayName("a play submitted before the first step is stamped 1 and runs on tick 21")
  void aPlayBeforeTheFirstStepRunsOnTick21() {
    Standard1v1Battle match = new Standard1v1Battle(Standard1v1Battle.DEFAULT_LEVEL, false);
    Battle battle = match.getBattle();
    match.submit(knight(), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 10000, "Knight");

    for (int tick = 0; tick < 21; tick++) {
      battle.step();
    }
    assertThat(match.getPlays()).as("nothing has run by the end of tick 20").isEmpty();

    battle.step();
    assertThat(match.getPlays()).hasSize(1);
    assertThat(match.getPlays().get(0).tick()).isEqualTo(21);
    assertThat(match.getPlays().get(0).units()).hasSize(1);
    assertThat(battle.getHolder().entities())
        .as("the Knight is admitted and visited on the tick its play runs")
        .contains(match.getPlays().get(0).units().get(0));
  }

  @Test
  @DisplayName("a play submitted after 30 steps is stamped 30 and runs on tick 50")
  void aPlayAfterThirtyStepsRunsOnTick50() {
    Standard1v1Battle match = new Standard1v1Battle(Standard1v1Battle.DEFAULT_LEVEL, false);
    Battle battle = match.getBattle();
    for (int tick = 0; tick < 30; tick++) {
      battle.step();
    }
    match.submit(knight(), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 10000, "Knight");

    for (int tick = 30; tick < 50; tick++) {
      battle.step();
    }
    assertThat(match.getPlays()).as("nothing has run by the end of tick 49").isEmpty();

    battle.step();
    assertThat(match.getPlays()).hasSize(1);
    assertThat(match.getPlays().get(0).tick()).isEqualTo(50);
  }

  @Test
  @DisplayName("a unit waiting its turn has its movement switched off until it starts deploying")
  void aWaitingUnitsMovementIsOffUntilItDeploys() {
    Standard1v1Battle match = new Standard1v1Battle(Standard1v1Battle.DEFAULT_LEVEL, false);
    Battle battle = match.getBattle();
    DeployCard barbarians = GameData.card("Barbarians");
    match.play(0, barbarians, Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 10000, "Barbarians");

    battle.step();
    CharacterEntity first = match.getPlays().get(0).units().get(0);
    CharacterEntity second = match.getPlays().get(0).units().get(1);
    assertThat(first.getView().getState()).isEqualTo(GridEntityState.DEPLOYING);
    assertThat(first.getView().isMovementActive()).isTrue();
    assertThat(second.getView().getState()).isEqualTo(GridEntityState.WAITING_TO_DEPLOY);
    assertThat(second.getView().isMovementActive()).as("waiting: switched off").isFalse();
    assertThat(second.getView().isMovementComponent()).as("but still there").isTrue();

    // The second unit waits 100 ms: two state visits count it down into the deploying state.
    battle.step();
    battle.step();
    assertThat(second.getView().getState()).isEqualTo(GridEntityState.DEPLOYING);
    assertThat(second.getView().isMovementActive()).isTrue();
  }
}
