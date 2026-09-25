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
 * A crown tower takes part in contact like any unit: it is a neighbour of the push pass and an
 * obstacle of the avoidance handler. It has no movement component, so it pushes with the reach of a
 * static neighbour and is never pushed itself, and its mass is 0, so its share of a push is the
 * smallest one: 1 unit, which still counts in the push count the displacement divides by.
 */
class BattleTowerContactTest {

  @Test
  @DisplayName(
      "a Knight deployed against its king tower is pushed off it 1 unit a tick while it deploys")
  void theKingPushesADeployingKnight() {
    UnitData knight =
        UnitDataMapper.toUnitData(
            Objects.requireNonNull(CardRegistry.get("knight"), "knight not found"));
    Standard1v1Battle match = new Standard1v1Battle(Standard1v1Battle.DEFAULT_LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity unit = match.deploy(0, knight, Standard1v1Battle.DEFAULT_LEVEL, 0, 9000, 4600);

    // The king stands at (9000, 3000). A static neighbour reaches its own collision radius plus the
    // Knight's, clamped to 500: 1400 + 500 = 1900, and the Knight stands 1600 to 1620 away from it
    // over its 20 deploy ticks.
    for (int tick = 0; tick < 20; tick++) {
      battle.step();
      assertThat(unit.getView().getState())
          .as("tick %d state", tick)
          .isIn(GridEntityState.DEPLOYING, GridEntityState.MOVING);
      assertThat(unit.getView().getX()).as("tick %d x", tick).isEqualTo(9000);
      assertThat(unit.getView().getY()).as("tick %d y", tick).isEqualTo(4601 + tick);
    }
  }
}
