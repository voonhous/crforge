package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A spawned child whose row has no hit points deploys for its row's deploy time even when the spawn
 * does not ask for a deploy: the Lumberjack's death drops RageBarbarianBottle, a building without
 * hit points and with a deploy time of 500, which stands deploying for ten steps, then leaves as
 * its deploy ends and makes its death area, BarbarianRage.
 */
class BattleRageBottleTest {

  @Test
  @DisplayName(
      "the Lumberjack's bottle deploys for its 500 ms, then leaves and makes BarbarianRage where it"
          + " stood")
  void theBottleDeploysThenBreaks() {
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity lumberjack =
        match.deploy(0, GameData.unit("RageBarbarian"), 11, 0, 9000, 12000);
    for (int i = 0; i < 40; i++) {
      battle.step();
    }
    int x = lumberjack.x();
    int y = lumberjack.y();
    match.getWorld().kill(lumberjack, null);

    List<Integer> deployingSteps = new ArrayList<>();
    int bottleLeft = -1;
    int rageMade = -1;
    boolean bottleSeen = false;
    for (int step = 1; step <= 40; step++) {
      battle.step();
      CharacterEntity bottle = character(battle, "RageBarbarianBottle");
      if (bottle != null) {
        bottleSeen = true;
        assertThat(new int[] {bottle.x(), bottle.y()}).containsExactly(x, y);
        if (bottle.getView().getState() == GridEntityState.DEPLOYING) {
          deployingSteps.add(step);
        }
      } else if (bottleSeen && bottleLeft < 0) {
        bottleLeft = step;
      }
      if (rageMade < 0 && area(battle, "BarbarianRage") != null) {
        rageMade = step;
        assertThat(area(battle, "BarbarianRage").getX()).isEqualTo(x);
        assertThat(area(battle, "BarbarianRage").getY()).isEqualTo(y);
      }
    }
    // Every observation of the bottle is a deploying one, eleven of them: the step it is made and
    // the ten of its 500 ms; it is gone on the next, and the rage was made as it left.
    assertThat(bottleSeen).isTrue();
    assertThat(deployingSteps).hasSize(11);
    assertThat(bottleLeft).isEqualTo(deployingSteps.get(deployingSteps.size() - 1) + 1);
    assertThat(rageMade).isEqualTo(deployingSteps.get(deployingSteps.size() - 1));
  }

  private static CharacterEntity character(Battle battle, String row) {
    for (BattleEntity entity : battle.getHolder().entities()) {
      if (entity instanceof CharacterEntity c && c.getData().name().equals(row)) {
        return c;
      }
    }
    return null;
  }

  private static AreaEffectEntity area(Battle battle, String row) {
    for (BattleEntity entity : battle.getHolder().entities()) {
      if (entity instanceof AreaEffectEntity a && a.getData().name().equals(row)) {
        return a;
      }
    }
    return null;
  }
}
