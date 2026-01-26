package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.component.Health;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameStateTest {

  private GameState gameState;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    gameState = new GameState();
  }

  @Test
  void newGameState_shouldBeEmpty() {
    assertThat(gameState.getEntities()).isEmpty();
    assertThat(gameState.getProjectiles()).isEmpty();
    assertThat(gameState.getFrameCount()).isEqualTo(0);
    assertThat(gameState.isGameOver()).isFalse();
  }

  @Test
  void spawnEntity_shouldAddToPendingThenEntities() {
    Troop troop = Troop.builder().name("Knight").team(Team.BLUE).build();

    gameState.spawnEntity(troop);
    assertThat(gameState.getEntities()).isEmpty();

    gameState.processPending();
    assertThat(gameState.getEntities()).hasSize(1);
    assertThat(gameState.getEntities().get(0)).isEqualTo(troop);
  }

  @Test
  void spawnEntity_shouldTriggerOnSpawn() {
    Troop troop = Troop.builder().name("Knight").team(Team.BLUE).deployTime(0).build();

    gameState.spawnEntity(troop);
    gameState.processPending();

    assertThat(troop.isSpawned()).isTrue();
  }

  @Test
  void getEntitiesByTeam_shouldFilterCorrectly() {
    Troop blueTroop = Troop.builder().name("Knight").team(Team.BLUE).build();
    Troop redTroop = Troop.builder().name("Knight").team(Team.RED).build();

    gameState.spawnEntity(blueTroop);
    gameState.spawnEntity(redTroop);
    gameState.processPending();

    assertThat(gameState.getEntitiesByTeam(Team.BLUE)).containsExactly(blueTroop);
    assertThat(gameState.getEntitiesByTeam(Team.RED)).containsExactly(redTroop);
  }

  @Test
  void getAliveEntities_shouldFilterDeadEntities() {
    Troop aliveTroop = Troop.builder().name("Knight").team(Team.BLUE).health(new Health(100))
        .build();
    Troop deadTroop = Troop.builder().name("Knight").team(Team.RED).health(new Health(100)).build();

    gameState.spawnEntity(aliveTroop);
    gameState.spawnEntity(deadTroop);
    gameState.processPending();

    deadTroop.getHealth().takeDamage(200);

    assertThat(gameState.getAliveEntities()).containsExactly(aliveTroop);
  }

  @Test
  void processDeaths_shouldTriggerOnDeath() {
    Troop troop = Troop.builder().name("Knight").team(Team.BLUE).health(new Health(100)).build();

    gameState.spawnEntity(troop);
    gameState.processPending();

    troop.getHealth().takeDamage(200);
    gameState.processDeaths();

    assertThat(troop.isDead()).isTrue();
  }

  @Test
  void crownTowerDeath_shouldEndGame() {
    Tower crownTower =
        Tower.builder()
            .name("Crown Tower")
            .team(Team.BLUE)
            .towerType(Tower.TowerType.CROWN)
            .health(new Health(100))
            .build();

    gameState.spawnEntity(crownTower);
    gameState.processPending();

    crownTower.getHealth().takeDamage(200);
    gameState.processDeaths();

    assertThat(gameState.isGameOver()).isTrue();
    assertThat(gameState.getWinner()).isEqualTo(Team.RED);
  }

  @Test
  void getTowerCount_shouldCountAliveTowers() {
    Tower tower1 = Tower.createPrincessTower(Team.BLUE, 5, 5);
    Tower tower2 = Tower.createPrincessTower(Team.BLUE, 10, 5);

    gameState.spawnEntity(tower1);
    gameState.spawnEntity(tower2);
    gameState.processPending();

    assertThat(gameState.getTowerCount(Team.BLUE)).isEqualTo(2);

    tower1.getHealth().takeDamage(10000);
    gameState.processDeaths();

    assertThat(gameState.getTowerCount(Team.BLUE)).isEqualTo(1);
  }

  @Test
  void princessTowerDeath_shouldActivateKingTower() {
    // Setup: King Tower (inactive) + Princess Tower (active)
    Tower kingTower = Tower.createCrownTower(Team.BLUE, 9, 3);
    Tower princessTower = Tower.createPrincessTower(Team.BLUE, 5, 6);

    gameState.spawnEntity(kingTower);
    gameState.spawnEntity(princessTower);
    gameState.processPending();

    // Verify initial state
    assertThat(kingTower.isActive()).isFalse();
    assertThat(princessTower.isActive()).isTrue();

    // Destroy Princess Tower
    princessTower.getHealth().takeDamage(10000);
    gameState.processDeaths();

    // Verify King Tower activated
    assertThat(kingTower.isActive()).as("King Tower should activate when Princess Tower falls")
        .isTrue();
  }

  @Test
  void kingTower_shouldActivateWhenDamaged() {
    // Setup: King Tower (inactive)
    Tower kingTower = Tower.createCrownTower(Team.BLUE, 9, 3);
    gameState.spawnEntity(kingTower);
    gameState.processPending();

    // Verify initial state
    assertThat(kingTower.isActive()).isFalse();

    // Damage the King Tower
    kingTower.getHealth().takeDamage(100);

    // Simulate game update (Tower.update() handles the activation check)
    kingTower.update(0.1f);

    // Verify King Tower activated
    assertThat(kingTower.isActive()).as("King Tower should activate when damaged").isTrue();
  }

  @Test
  void kingTower_shouldHaveActivationDelay() {
    // Setup: King Tower (inactive)
    Tower kingTower = Tower.createCrownTower(Team.BLUE, 9, 3);
    gameState.spawnEntity(kingTower);
    gameState.processPending();

    // Activate the tower manually or via damage
    kingTower.getHealth().takeDamage(100);
    kingTower.update(0.1f);

    // Should be active but waking up
    assertThat(kingTower.isActive()).isTrue();
    assertThat(kingTower.isWakingUp()).isTrue();

    // Advance time by 0.5s (still waking up, assumes 1s delay)
    kingTower.update(0.5f);
    assertThat(kingTower.isWakingUp()).isTrue();

    // Advance time by another 0.6s (total > 1.0s)
    kingTower.update(0.6f);
    assertThat(kingTower.isWakingUp()).isFalse();
  }

  @Test
  void incrementFrame_shouldTrackGameTime() {
    gameState.incrementFrame();
    gameState.incrementFrame();
    gameState.incrementFrame();

    assertThat(gameState.getFrameCount()).isEqualTo(3);
    assertThat(gameState.getGameTimeSeconds()).isEqualTo(0.1f);
  }

  @Test
  void reset_shouldClearAllState() {
    Troop troop = Troop.builder().name("Knight").team(Team.BLUE).build();
    gameState.spawnEntity(troop);
    gameState.processPending();
    gameState.incrementFrame();

    gameState.reset();

    assertThat(gameState.getEntities()).isEmpty();
    assertThat(gameState.getFrameCount()).isEqualTo(0);
    assertThat(gameState.isGameOver()).isFalse();
  }
}
