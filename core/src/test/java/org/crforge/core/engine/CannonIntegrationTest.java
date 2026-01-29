package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CannonIntegrationTest {

  private GameEngine engine;

  @BeforeEach
  void setUp() {
    engine = new GameEngine();
    engine.setMatch(new Standard1v1Match());
    engine.initMatch();
  }

  @Test
  void cannonShouldTargetAndAttackEnemy() {
    // 1. Create a Cannon (Building with Combat)
    // Range 5.5, Damage 100
    Building cannon = Building.builder()
        .name("Cannon")
        .team(Team.BLUE)
        .position(new Position(10f, 10f))
        .health(new Health(800))
        .movement(new Movement(0f, 0f, 1.0f, 1.0f, MovementType.BUILDING))
        .combat(Combat.builder()
            .range(5.5f)
            .sightRange(5.5f)
            .damage(100)
            .attackCooldown(0.8f)
            .build())
        .lifetime(30f)
        .build();

    cannon.onSpawn();
    engine.spawn(cannon);

    // 2. Create an Enemy Troop within range (2 tiles away)
    Troop enemy = Troop.builder()
        .name("TargetDummy")
        .team(Team.RED)
        .position(new Position(12f, 10f))
        .health(new Health(1000))
        .movement(new Movement(0f, 0f, 0.5f, 0.5f, MovementType.GROUND))
        .deployTime(0) // Make it targetable immediately
        .build();

    enemy.onSpawn();
    engine.spawn(enemy);

    // 3. Tick engine to process spawns and run TargetingSystem
    engine.tick(); // Process pending spawns
    engine.tick(); // Update systems

    // 4. Verify Targeting
    assertThat(cannon.getCombat().getCurrentTarget())
        .as("Cannon should acquire the enemy troop as a target")
        .isEqualTo(enemy);

    // 5. Run simulation for 1 second to allow CombatSystem to fire
    engine.runSeconds(1.0f);

    // 6. Verify Damage
    // Enemy starts with 1000 HP. Cannon does 100 dmg.
    assertThat(enemy.getHealth().getCurrent())
        .as("Enemy should take damage from Cannon attack")
        .isLessThan(1000);
  }
}
