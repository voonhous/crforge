package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the battle makes of a row's charge, and what it refuses of it. */
class BattleChargeTest {

  private static Standard1v1Battle passiveTowers() {
    return new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
  }

  @Test
  @DisplayName("a row with a charge range starts tracking its charge at 0, one without tracks none")
  void aChargeStartsAtZero() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity prince = match.deploy(0, GameData.unit("Prince"), 11, 0, 3500, 10000);
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), 11, 0, 14500, 10000);

    assertThat(prince.getUnit().movement().getChargeProgress()).isZero();
    assertThat(knight.getUnit().movement().getChargeProgress())
        .isEqualTo(MovementState.CHARGE_INACTIVE);
  }

  @Test
  @DisplayName("a Kamikaze row's hit destroys it in the tick it lands")
  void aKamikazeHitDestroysTheUnit() {
    Standard1v1Battle match = passiveTowers();
    // A Battle Ram just short of the red princess tower, which it walks to and hits.
    CharacterEntity ram = match.deploy(0, GameData.unit("BattleRam"), 11, 0, 3500, 22000);

    int tick = 0;
    while (ram.getHitPoints().getHitPoints() > 0 && tick < 200) {
      match.getBattle().step();
      tick++;
    }
    // The tick its hit landed on the tower is the tick it killed itself.
    WorldEntity tower =
        match.getWorld().present().stream()
            .filter(e -> e.name().equals("PrincessTower_1_1"))
            .findFirst()
            .orElseThrow();
    assertThat(ram.getHitPoints().getHitPoints()).isZero();
    assertThat(tower.getHitPoints().getHitPoints()).isLessThan(tower.getHitPoints().getMaximum());
  }

  @Test
  @DisplayName("a Kamikaze row that drains over a time is refused as it is created")
  void aKamikazeTimeIsRefused() {
    BattleWorld world = passiveTowers().getWorld();
    assertThatThrownBy(
            () ->
                new CharacterEntity(
                    world, GameData.unit("SkeletonBalloon"), "SkeletonBalloon", 0, 3500, 10000, 11))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("KamikazeTime");
  }

  @Test
  @DisplayName("a row whose completed charge runs an action is refused as it is created")
  void aChargeActionIsRefused() {
    BattleWorld world = passiveTowers().getWorld();
    assertThatThrownBy(
            () ->
                new CharacterEntity(world, GameData.unit("Ram_crazy_1"), "Ram", 0, 3500, 10000, 11))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("OnStartChargingAction");
  }
}
