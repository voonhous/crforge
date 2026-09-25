package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import org.crforge.core.battle.Battle;
import org.crforge.core.card.UnitDataMapper;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A deploying unit's movement visit runs: it asks for no route and gets no speed, so it stays put
 * unless another unit pushes it, and two units placed on one point are split while still deploying.
 */
class BattleDeployingPushTest {

  private static UnitData knight() {
    return UnitDataMapper.toUnitData(
        Objects.requireNonNull(CardRegistry.get("knight"), "knight not found"));
  }

  @Test
  @DisplayName("a unit deploying alone stays where it was placed")
  void aLoneDeployingUnitStaysPut() {
    Standard1v1Battle match = new Standard1v1Battle();
    Battle battle = match.getBattle();
    CharacterEntity knight = match.deploy(0, knight(), 11, 0, 3500, 10000);

    for (int tick = 0; tick < 19; tick++) {
      battle.step();
      assertThat(knight.getView().getState()).isEqualTo(GridEntityState.DEPLOYING);
      assertThat(knight.getView().getX()).isEqualTo(3500);
      assertThat(knight.getView().getY()).isEqualTo(10000);
    }
  }

  @Test
  @DisplayName("two units placed on one point are pushed apart while both are still deploying")
  void twoUnitsOnOnePointSplitWhileDeploying() {
    Standard1v1Battle match = new Standard1v1Battle();
    Battle battle = match.getBattle();
    CharacterEntity first = match.deploy(0, knight(), 11, 0, 3500, 10000, "Knight_a");
    CharacterEntity second = match.deploy(0, knight(), 11, 0, 3500, 10000, "Knight_b");

    battle.step();

    assertThat(first.getView().getState()).isEqualTo(GridEntityState.DEPLOYING);
    assertThat(second.getView().getState()).isEqualTo(GridEntityState.DEPLOYING);
    int dx = first.getView().getX() - second.getView().getX();
    int dy = first.getView().getY() - second.getView().getY();
    assertThat(dx != 0 || dy != 0).as("the stack is split on its first tick").isTrue();
  }
}
