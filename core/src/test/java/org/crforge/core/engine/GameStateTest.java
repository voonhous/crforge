package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.arena.Arena;
import org.crforge.core.arena.TileType;
import org.crforge.core.card.LevelScaling;
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
    Troop aliveTroop =
        Troop.builder().name("Knight").team(Team.BLUE).health(new Health(100)).build();
    Troop deadTroop = Troop.builder().name("Knight").team(Team.RED).health(new Health(100)).build();

    gameState.spawnEntity(aliveTroop);
    gameState.spawnEntity(deadTroop);
    gameState.processPending();

    deadTroop.getHealth().takeDamage(200);
    gameState.refreshCaches();

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
    Tower tower1 = Tower.createPrincessTower(Team.BLUE, 5, 5, LevelScaling.DEFAULT_TOWER_LEVEL);
    Tower tower2 = Tower.createPrincessTower(Team.BLUE, 10, 5, LevelScaling.DEFAULT_TOWER_LEVEL);

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
    Tower kingTower = Tower.createCrownTower(Team.BLUE, 9, 3, LevelScaling.DEFAULT_TOWER_LEVEL);
    Tower princessTower =
        Tower.createPrincessTower(Team.BLUE, 5, 6, LevelScaling.DEFAULT_TOWER_LEVEL);

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
    assertThat(kingTower.isActive())
        .as("King Tower should activate when Princess Tower falls")
        .isTrue();
  }

  @Test
  void kingTower_shouldActivateWhenDamaged() {
    // Setup: King Tower (inactive)
    Tower kingTower = Tower.createCrownTower(Team.BLUE, 9, 3, LevelScaling.DEFAULT_TOWER_LEVEL);
    gameState.spawnEntity(kingTower);
    gameState.processPending();

    // Verify initial state
    assertThat(kingTower.isActive()).isFalse();

    // Damage the King Tower
    kingTower.getHealth().takeDamage(100);

    // EntityTimerSystem handles tower activation check
    new org.crforge.core.engine.EntityTimerSystem().update(java.util.List.of(kingTower), 0.1f);

    // Verify King Tower activated
    assertThat(kingTower.isActive()).as("King Tower should activate when damaged").isTrue();
  }

  @Test
  void kingTower_shouldHaveActivationDelay() {
    // Setup: King Tower (inactive)
    Tower kingTower = Tower.createCrownTower(Team.BLUE, 9, 3, LevelScaling.DEFAULT_TOWER_LEVEL);
    gameState.spawnEntity(kingTower);
    gameState.processPending();

    // Activate the tower manually or via damage
    kingTower.getHealth().takeDamage(100);
    org.crforge.core.engine.EntityTimerSystem ets = new org.crforge.core.engine.EntityTimerSystem();
    ets.update(java.util.List.of(kingTower), 0.1f);

    // Should be active but waking up
    assertThat(kingTower.isActive()).isTrue();
    assertThat(kingTower.isWakingUp()).isTrue();

    // Advance time by 0.5s (still waking up, assumes 1s delay)
    ets.update(java.util.List.of(kingTower), 0.5f);
    assertThat(kingTower.isWakingUp()).isTrue();

    // Advance time by another 0.6s (total > 1.0s)
    ets.update(java.util.List.of(kingTower), 0.6f);
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
  void princessTowerDeath_shouldFreeTilesForPlacement() {
    Arena arena = Arena.standard();
    gameState.setArena(arena);

    // Blue left princess tower at standard position (3.5, 6.5)
    Tower princessTower =
        Tower.createPrincessTower(Team.BLUE, 3.5f, 6.5f, LevelScaling.DEFAULT_TOWER_LEVEL);

    gameState.spawnEntity(princessTower);
    gameState.processPending();

    // Tiles should be TOWER before destruction
    assertThat(arena.getTile(3, 6).type()).isEqualTo(TileType.TOWER);
    assertThat(arena.isValidPlacement(3.5f, 6.5f, Team.BLUE)).isFalse();

    // Destroy the princess tower
    princessTower.getHealth().takeDamage(100000);
    gameState.processDeaths();

    // Tiles should now be BLUE_ZONE, enabling placement
    assertThat(arena.getTile(3, 6).type()).isEqualTo(TileType.BLUE_ZONE);
    assertThat(arena.isValidPlacement(3.5f, 6.5f, Team.BLUE))
        .as("Should be able to place on destroyed princess tower footprint")
        .isTrue();
  }

  @Test
  void princessTowerDeath_shouldOpenPocketZoneForOpposingTeam() {
    Arena arena = Arena.standard();
    gameState.setArena(arena);

    // Red left princess tower at standard position (3.5, 25.5)
    Tower princessTower =
        Tower.createPrincessTower(Team.RED, 3.5f, 25.5f, LevelScaling.DEFAULT_TOWER_LEVEL);

    gameState.spawnEntity(princessTower);
    gameState.processPending();

    // Blue cannot deploy in Red territory before tower destruction
    assertThat(arena.isValidPlacement(4.5f, 18.5f, Team.BLUE))
        .as("Blue should not deploy in Red territory before pocket opens")
        .isFalse();

    // Destroy the Red left princess tower
    princessTower.getHealth().takeDamage(100000);
    gameState.processDeaths();

    // Blue should now be able to deploy in the left pocket (x[0-8], y[17-20])
    assertThat(arena.isValidPlacement(4.5f, 18.5f, Team.BLUE))
        .as("Blue should deploy in pocket after destroying Red's left princess tower")
        .isTrue();

    // Right lane should still be blocked for Blue
    assertThat(arena.isValidPlacement(14.5f, 18.5f, Team.BLUE))
        .as("Right lane pocket should not open from left tower destruction")
        .isFalse();
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
