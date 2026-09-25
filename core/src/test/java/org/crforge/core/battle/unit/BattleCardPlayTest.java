package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.card.UnitDataMapper;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A player's card play is stamped with the battle's tick counter when it is submitted, 1 while the
 * counter is still 0, and runs {@link Standard1v1Battle#PLAY_DELAY_TICKS} ticks after that stamp.
 */
class BattleCardPlayTest {

  private static DeployCard knight() {
    return UnitDataMapper.toDeployCard(
        Objects.requireNonNull(CardRegistry.get("knight"), "knight not found"));
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
}
