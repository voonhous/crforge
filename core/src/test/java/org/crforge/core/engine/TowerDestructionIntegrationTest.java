package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TowerDestructionIntegrationTest {

  private GameEngine engine;

  @BeforeEach
  void setUp() {
    engine = new GameEngine();
    engine.setMatch(new Standard1v1Match());
    engine.initMatch();
  }

  @Test
  void troopShouldMoveToKingTower_afterDestroyingPrincessTower() {
    // 1. Setup: Red Princess Tower is the target
    Tower redPrincess = engine.getGameState().getPrincessTowers(Team.RED).get(0);

    // Set HP to 1 so it dies essentially instantly when attacked
    redPrincess.getHealth().takeDamage(redPrincess.getHealth().getCurrent() - 1);

    // 2. Spawn Blue Knight right next to it
    float targetX = redPrincess.getPosition().getX();
    float targetY = redPrincess.getPosition().getY();

    Troop knight =
        Troop.builder()
            .name("Knight")
            .team(Team.BLUE)
            .position(new Position(targetX, targetY - 2.0f))
            .health(new Health(1000))
            .movement(new Movement(5.0f, 1.0f, 0.5f, 0.5f, MovementType.GROUND))
            .combat(
                Combat.builder().damage(10).range(1.0f).attackCooldown(0.1f).loadTime(0f).build())
            .build();
    knight.onSpawn();

    engine.spawn(knight);
    engine.tick();

    // 3. Knight should attack and destroy tower
    // Increased duration to 1.5s to account for potential windup or movement adjustments
    engine.runSeconds(1.5f);

    assertThat(redPrincess.isAlive()).as("Princess tower should be destroyed").isFalse();
    // Target might be null or King tower depending on timing, but shouldn't be Princess
    if (knight.getCombat().getCurrentTarget() != null) {
      assertThat(knight.getCombat().getCurrentTarget()).isNotEqualTo(redPrincess);
    }

    // 4. Knight should now move towards King Tower
    engine.runSeconds(2.0f);

    assertThat(knight.getPosition().getY())
        .as("Knight should move North past the dead tower")
        .isGreaterThan(targetY + 1.0f);
  }
}
