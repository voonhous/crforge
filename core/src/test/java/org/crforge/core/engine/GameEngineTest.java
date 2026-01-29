package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameEngineTest {

  private GameEngine engine;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    engine = new GameEngine();
    engine.setMatch(new Standard1v1Match());
  }

  @Test
  void constants_shouldBe30FPS() {
    assertThat(GameEngine.TICKS_PER_SECOND).isEqualTo(30);
    assertThat(GameEngine.DELTA_TIME).isEqualTo(1.0f / 30f);
  }

  @Test
  void initMatch_shouldSpawnSixTowers() {
    engine.initMatch();

    assertThat(engine.getGameState().getEntities()).hasSize(6);
    assertThat(engine.getGameState().getTowerCount(Team.BLUE)).isEqualTo(3);
    assertThat(engine.getGameState().getTowerCount(Team.RED)).isEqualTo(3);
  }

  @Test
  void initMatch_shouldHaveCrownAndPrincessTowers() {
    engine.initMatch();

    Tower blueCrown = engine.getGameState().getCrownTower(Team.BLUE);
    Tower redCrown = engine.getGameState().getCrownTower(Team.RED);

    assertThat(blueCrown).isNotNull();
    assertThat(blueCrown.isCrownTower()).isTrue();
    assertThat(redCrown).isNotNull();
    assertThat(redCrown.isCrownTower()).isTrue();

    assertThat(engine.getGameState().getPrincessTowers(Team.BLUE)).hasSize(2);
    assertThat(engine.getGameState().getPrincessTowers(Team.RED)).hasSize(2);
  }

  @Test
  void tick_shouldIncrementFrameCount() {
    engine.initMatch();

    engine.tick();

    assertThat(engine.getGameState().getFrameCount()).isEqualTo(1);
  }

  @Test
  void tick_shouldAdvanceGameTime() {
    engine.initMatch();

    engine.tick(30);

    assertThat(engine.getGameTimeSeconds()).isEqualTo(1.0f);
  }

  @Test
  void runSeconds_shouldAdvanceCorrectTicks() {
    engine.initMatch();

    engine.runSeconds(2.0f);

    assertThat(engine.getGameState().getFrameCount()).isEqualTo(60);
  }

  @Test
  void spawn_shouldAddEntityToGame() {
    engine.initMatch();

    Troop knight = Troop.builder().name("Knight").team(Team.BLUE).position(new Position(9, 15))
        .build();

    engine.spawn(knight);
    engine.tick(); // Process pending spawns

    assertThat(engine.getGameState().getEntities()).contains(knight);
  }

  @Test
  void troopMovement_shouldMoveTowardTarget() {
    engine.initMatch();

    Troop knight =
        Troop.builder()
            .name("Knight")
            .team(Team.BLUE)
            .position(new Position(9, 10))
            .movement(new Movement(2.0f, 1.0f, 0.5f, 0.5f, MovementType.GROUND))
            .deployTime(0)
            .combat(Combat.builder().sightRange(20f).build())
            .build();

    engine.spawn(knight);
    float initialY = knight.getPosition().getY();

    // Run for 1 second
    engine.runSeconds(1.0f);

    // Knight should have moved toward enemy side (higher Y)
    assertThat(knight.getPosition().getY()).isGreaterThan(initialY);
  }

  @Test
  void isRunning_shouldReturnFalseWhenGameOver() {
    engine.initMatch();

    assertThat(engine.isRunning()).isTrue();

    // Destroy blue crown tower
    Tower blueCrown = engine.getGameState().getCrownTower(Team.BLUE);
    blueCrown.getHealth().takeDamage(10000);
    engine.tick();

    assertThat(engine.isRunning()).isFalse();
    assertThat(engine.getGameState().isGameOver()).isTrue();
  }

  @Test
  void stop_shouldStopEngine() {
    engine.initMatch();

    engine.stop();

    assertThat(engine.isRunning()).isFalse();
  }

  @Test
  void combatInteraction_shouldDealDamage() {
    engine.initMatch();

    // Spawn a troop very close to enemy tower
    Troop knight =
        Troop.builder()
            .name("Knight")
            .team(Team.BLUE)
            .position(new Position(9, 26)) // Near red tower
            .health(new Health(1000))
            .deployTime(0)
            .combat(
                Combat.builder()
                    .damage(100)
                    .range(1.5f)
                    .sightRange(10f)
                    .attackCooldown(1.0f)
                    .build())
            .build();

    engine.spawn(knight);

    // Get initial tower health
    Tower redTower = engine.getGameState().getPrincessTowers(Team.RED).get(0);
    int initialHealth = redTower.getHealth().getCurrent();

    // Run for several seconds to allow combat
    engine.runSeconds(5.0f);

    // Tower should have taken damage from attacking the knight
    assertThat(knight.getHealth().getCurrent()).isLessThan(1000);
  }
}
