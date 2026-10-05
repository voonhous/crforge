package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which characters have a movement component, and what a data swap does to it. The level setter
 * builds one only for a row with a speed of at least 1, building or not, so a character row without
 * a speed stands once it has deployed; a data swap onto a row with a speed builds one, and a swap
 * onto a row without a lifetime ends the drain of one that had it. A hit dealt while its dealer
 * carries a buff with a damage multiplier, whose share of the damage is not traced, is refused.
 */
class BattleMovementComponentBySpeedTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** A Knight's row without a speed, and with a lifetime when one is given. */
  private static UnitData standingKnight(int lifeTimeMs) {
    return GameData.records().unit("Knight").toBuilder().speed(0).lifeTimeMs(lifeTimeMs).build();
  }

  /** A passive battle with the unit placed for side 0 at the left lane's front. */
  private static Standard1v1Battle placed(UnitData data) {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    battle.deploy(0, data, LEVEL, 0, 3500, 10000, "knight");
    return battle;
  }

  private static void steps(Standard1v1Battle battle, int count) {
    for (int i = 0; i < count; i++) {
      battle.getBattle().step();
    }
  }

  private static CharacterEntity unit(Standard1v1Battle battle) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(CharacterEntity.class::isInstance)
        .map(CharacterEntity.class::cast)
        .filter(c -> c.name().equals("knight"))
        .findFirst()
        .orElseThrow();
  }

  @Test
  @DisplayName(
      "a character row without a speed has no movement component, and stands where it is placed"
          + " once it has deployed")
  void aRowWithoutASpeedStands() {
    Standard1v1Battle battle = placed(standingKnight(0));
    steps(battle, 40);
    CharacterEntity knight = unit(battle);
    assertThat(knight.getView().isMovementComponent()).isFalse();
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.STANDING);
    assertThat(knight.getView().getX()).isEqualTo(3500);
    assertThat(knight.getView().getY()).isEqualTo(10000);
  }

  @Test
  @DisplayName(
      "a swap from a row without a speed and with a lifetime onto the Knight's builds a movement"
          + " component, so the unit walks from the next visit, and ends the drain")
  void aSwapOntoARowWithASpeedBuildsOne() {
    Standard1v1Battle battle = placed(standingKnight(20000));
    steps(battle, 40);
    CharacterEntity knight = unit(battle);
    int drained = knight.getHitPoints().getMaximum() - knight.getHitPoints().getHitPoints();
    assertThat(drained).as("the drain ran").isPositive();

    knight.changeData("Knight", false);
    assertThat(knight.getView().isMovementComponent()).isTrue();
    assertThat(knight.getHitPoints().getDecayStep()).isZero();
    int hitPoints = knight.getHitPoints().getHitPoints();
    steps(battle, 20);
    assertThat(knight.getHitPoints().getHitPoints()).as("no more drain").isEqualTo(hitPoints);
    assertThat(knight.getView().getY()).as("it walks").isGreaterThan(10000);
  }

  @Test
  @DisplayName(
      "a hit dealt while the dealer carries a buff with a damage multiplier is refused, the"
          + " multiplier's share of the damage not being traced")
  void aHitUnderADamageMultiplierIsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    battle.deploy(0, GameData.records().unit("Knight"), LEVEL, 0, 3500, 15000, "knight");
    battle.deploy(0, GameData.records().unit("Knight"), LEVEL, 1, 3500, 16500, "enemy");
    steps(battle, 1);
    CharacterEntity knight = unit(battle);
    knight
        .getBuffs()
        .apply(GameData.records().buff("crazy_FullFire"), 100000, knight.getPackedLevel(), null, 0);
    assertThatThrownBy(() -> steps(battle, 200))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(
            "knight hits while it carries crazy_FullFire, whose DamageMultiplier is not modelled");
  }
}
