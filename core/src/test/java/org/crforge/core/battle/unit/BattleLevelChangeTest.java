package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.SetCharacterLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A level-changing action on units in the battle: it changes its cause's level, and the unit's
 * damage and hit points follow the new level as the level change has them.
 */
class BattleLevelChangeTest {

  /** A Knight of the given level, placed on tick 0 for the bottom side. */
  private static CharacterEntity knight(Standard1v1Battle match, int level, String name, int x) {
    return match.deploy(0, GameData.unit("Knight"), level, 0, x, 10000, name);
  }

  private static SetCharacterLevel relative(int steps) {
    return new SetCharacterLevel(ActionRow.named("level"), steps, 1);
  }

  @Test
  @DisplayName("a rise changes the cause, whose hit points keep their share and whose hits follow")
  void aRiseChangesTheCause() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), 11, false);
    CharacterEntity owner = knight(match, 11, "Owner", 3500);
    CharacterEntity cause = knight(match, 11, "Cause", 14500);
    CharacterEntity twelve = knight(match, 12, "Twelve", 9000);
    match.getBattle().step();
    cause.getHitPoints().setHitPoints(1003);

    new ActionHolder(owner).start(relative(1), cause.actionHolder());

    assertThat(cause.level()).isEqualTo(12);
    assertThat(cause.getDamage()).isEqualTo(twelve.getDamage()).isEqualTo(221);
    assertThat(cause.getHitPoints().getMaximum()).isEqualTo(1938);
    assertThat(cause.getHitPoints().teamPool(0)).isEqualTo(1938);
    assertThat(cause.getHitPoints().teamPool(1)).isEqualTo(1938);
    assertThat(cause.getHitPoints().getHitPoints())
        .as("1003 of 1766 is 56795 hundred-thousandths, 1100 of 1938")
        .isEqualTo(1100);
    assertThat(owner.level()).as("the owner is left as it was").isEqualTo(11);
    assertThat(owner.getHitPoints().getMaximum()).isEqualTo(1766);
  }

  @Test
  @DisplayName("a fall keeps the hit points as they stand, above the new maximum")
  void aFallKeepsTheHitPoints() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), 11, false);
    CharacterEntity knight = knight(match, 11, "Knight", 3500);
    CharacterEntity ten = knight(match, 10, "Ten", 14500);
    match.getBattle().step();

    knight.actionHolder().start(relative(-1), knight.actionHolder());

    assertThat(knight.level()).isEqualTo(10);
    assertThat(knight.getDamage()).isEqualTo(ten.getDamage());
    assertThat(knight.getHitPoints().getMaximum()).isEqualTo(ten.getHitPoints().getMaximum());
    assertThat(knight.getHitPoints().getHitPoints()).isEqualTo(1766);
  }

  @Test
  @DisplayName(
      "without a relative adjustment the absolute level is set, and the same level is kept")
  void theAbsoluteLevel() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), 11, false);
    CharacterEntity knight = knight(match, 11, "Knight", 3500);
    match.getBattle().step();

    knight
        .actionHolder()
        .start(new SetCharacterLevel(ActionRow.named("level"), 0, 5), knight.actionHolder());
    assertThat(knight.level()).isEqualTo(5);

    int hitPoints = knight.getHitPoints().getHitPoints();
    knight
        .actionHolder()
        .start(new SetCharacterLevel(ActionRow.named("level"), 0, 5), knight.actionHolder());
    assertThat(knight.level()).isEqualTo(5);
    assertThat(knight.getHitPoints().getHitPoints()).isEqualTo(hitPoints);
  }

  @Test
  @DisplayName("a dead cause, or none, is left alone, and a tower's level change is refused")
  void whatIsLeftAloneOrRefused() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), 11, false);
    CharacterEntity knight = knight(match, 11, "Knight", 3500);
    CharacterEntity dead = knight(match, 11, "Dead", 14500);
    match.getBattle().step();
    dead.getHitPoints().setHitPoints(0);

    knight.actionHolder().start(relative(1), dead.actionHolder());
    knight.actionHolder().start(relative(1));
    assertThat(dead.level()).isEqualTo(11);
    assertThat(knight.level()).isEqualTo(11);

    TowerEntity tower = BattleMusketeerRunTest.towerNamed(match.getBattle(), "PrincessTower_0_1");
    assertThatThrownBy(() -> tower.actionHolder().start(relative(1), tower.actionHolder()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("a tower");
  }
}
