package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.deploy.DeployCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An entity killed during a tick is still visited by the rest of that tick, except that its death
 * switches its movement component off. Two Knights that meet on the left lane hit each other on the
 * same ticks; on tick 271 the first one visited kills the other, whose own hit, due on the same
 * tick, still lands, and both leave the battle in that tick's closing cleanup.
 */
class BattleDeathTickTest {

  @Test
  @DisplayName("two Knights that land their last hits on one tick both die, their movement off")
  void twoKnightsKillEachOtherOnOneTick() {
    DeployCard knight = GameData.card("Knight");
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL);
    Battle battle = match.getBattle();
    match.play(0, knight, Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 12000, "Blue");
    match.play(0, knight, Standard1v1Battle.DEFAULT_LEVEL, 1, 3500, 20000, "Red");

    for (int tick = 0; tick < 271; tick++) {
      battle.step();
    }
    CharacterEntity blue = match.getPlays().get(0).units().get(0);
    CharacterEntity red = match.getPlays().get(1).units().get(0);
    assertThat(blue.getHitPoints().getHitPoints()).isEqualTo(150);
    assertThat(red.getHitPoints().getHitPoints()).isEqualTo(150);
    assertThat(blue.getView().isMovementActive()).isTrue();

    battle.step();
    assertThat(red.getHitPoints().getHitPoints()).as("Blue, visited first, kills Red").isZero();
    assertThat(blue.getHitPoints().getHitPoints()).as("and Red's due hit still lands").isZero();
    assertThat(red.getView().isMovementActive()).as("death switches movement off").isFalse();
    assertThat(blue.getView().isMovementActive()).isFalse();
    assertThat(red.getView().isMovementComponent()).as("the component stays").isTrue();
    assertThat(battle.getHolder().entities()).doesNotContain(blue, red);
  }
}
