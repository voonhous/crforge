package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.Rarity;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for GoblinHut_Rework: a 4-elixir Rare spawner building with aggro-gated spawning. The hut
 * only spawns SpearGoblin_Dummy units when enemies are within its 6.0-tile detection radius. The
 * spawn timer pauses when no enemies are nearby and resumes when they return.
 */
class GoblinHutTest {

  private GameEngine engine;
  private Player bluePlayer;

  private static final Card GOBLIN_HUT = CardRegistry.get("goblinhut_rework");

  // Deploy at (9, 10) on blue side -- >6 tiles from any red tower
  private static final float DEPLOY_X = 9f;
  private static final float DEPLOY_Y = 10f;

  // 1.0s placement sync delay
  private static final int SYNC_DELAY_TICKS = GameEngine.TICKS_PER_SECOND;
  // 1.0s building deploy time
  private static final int DEPLOY_TICKS = GameEngine.TICKS_PER_SECOND;
  // 1.0s spawn start time
  private static final int SPAWN_START_TICKS = GameEngine.TICKS_PER_SECOND;
  // 2.1s spawn pause time
  private static final int SPAWN_PAUSE_TICKS = (int) (2.1f * GameEngine.TICKS_PER_SECOND);

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    engine = new GameEngine();

    Deck blueDeck = buildDeckWith(GOBLIN_HUT);
    Deck redDeck = buildDeckWith(GOBLIN_HUT);

    bluePlayer = new Player(Team.BLUE, blueDeck, false);
    Player redPlayer = new Player(Team.RED, redDeck, false);

    Standard1v1Match match = new Standard1v1Match(1);
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);
    engine.setMatch(match);
    engine.initMatch();

    bluePlayer.getElixir().add(10);
  }

  @Test
  void cardDataLoadsCorrectly() {
    assertThat(GOBLIN_HUT).as("Card should be loaded").isNotNull();
    assertThat(GOBLIN_HUT.getType()).as("Card type").isEqualTo(CardType.BUILDING);
    assertThat(GOBLIN_HUT.getCost()).as("Elixir cost").isEqualTo(4);
    assertThat(GOBLIN_HUT.getRarity()).as("Rarity").isEqualTo(Rarity.RARE);

    assertThat(GOBLIN_HUT.getUnitStats()).as("Unit stats").isNotNull();
    assertThat(GOBLIN_HUT.getUnitStats().getHealth()).as("Health").isEqualTo(461);
    assertThat(GOBLIN_HUT.getUnitStats().getLifeTime()).as("Lifetime").isEqualTo(30.0f);

    assertThat(GOBLIN_HUT.getUnitStats().getLiveSpawn()).as("Live spawn config").isNotNull();
    assertThat(GOBLIN_HUT.getUnitStats().getLiveSpawn().spawnOnAggro())
        .as("Spawn on aggro")
        .isTrue();
    assertThat(GOBLIN_HUT.getUnitStats().getLiveSpawn().spawnCharacter())
        .as("Spawn character")
        .isEqualTo("SpearGoblin_Dummy");

    // Death spawn: 1 SpearGoblin (not Dummy)
    assertThat(GOBLIN_HUT.getUnitStats().getDeathSpawns()).as("Death spawns").hasSize(1);
    assertThat(GOBLIN_HUT.getUnitStats().getDeathSpawns().get(0).stats().getName())
        .as("Death spawn character")
        .isEqualTo("SpearGoblin");
  }

  @Test
  void buildingCreatedOnDeploy() {
    deployGoblinHut(DEPLOY_X, DEPLOY_Y);

    // Tick past sync delay so the building is spawned
    engine.tick(SYNC_DELAY_TICKS + 2);

    Building building = findBuilding();
    assertThat(building).as("GoblinHut building should exist").isNotNull();
    assertThat(building.getTeam()).as("Team").isEqualTo(Team.BLUE);
    assertThat(building.getHealth().getMax()).as("Max HP").isEqualTo(461);
    assertThat(building.getSpawner()).as("SpawnerComponent").isNotNull();
    assertThat(building.getSpawner().isSpawnOnAggro()).as("Aggro-gated").isTrue();
  }

  @Test
  void buildingHasNoAttack() {
    deployGoblinHut(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + 2);

    Building building = findBuilding();
    assertThat(building.getCombat())
        .as("Building should have no combat component (damage=0)")
        .isNull();
  }

  @Test
  void noSpawnsWithoutEnemiesInRange() {
    deployGoblinHut(DEPLOY_X, DEPLOY_Y);

    // Tick 5 seconds past deploy (well past spawnStartTime of 1.0s) with no enemies nearby
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 150);

    assertThat(countSpearGoblinDummies())
        .as("No SpearGoblin_Dummy should spawn without enemies in range")
        .isEqualTo(0);
  }

  @Test
  void spawnsWhenEnemyEntersRange() {
    deployGoblinHut(DEPLOY_X, DEPLOY_Y);

    // Tick past sync + deploy so building is active
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    // Place enemy within 6 tiles of the hut
    spawnEnemyTroop(DEPLOY_X + 3f, DEPLOY_Y);

    // Tick past spawnStartTime (1.0s = 30 ticks) + a couple extra
    engine.tick(SPAWN_START_TICKS + 3);

    assertThat(countSpearGoblinDummies())
        .as("1 SpearGoblin_Dummy should spawn after enemy enters range")
        .isEqualTo(1);
  }

  @Test
  void spawnTimerPausesWhenEnemyLeavesRange() {
    deployGoblinHut(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    // Place enemy within range
    Troop enemy = spawnEnemyTroop(DEPLOY_X + 3f, DEPLOY_Y);

    // Tick past spawnStartTime to get first spawn
    engine.tick(SPAWN_START_TICKS + 3);
    assertThat(countSpearGoblinDummies()).as("First spawn").isEqualTo(1);

    // Tick part of the pause time (about half of 2.1s)
    engine.tick(SPAWN_PAUSE_TICKS / 2);

    // Kill the enemy to remove aggro trigger
    enemy.getHealth().takeDamage(enemy.getHealth().getCurrent());
    engine.tick(2); // Process death

    // Tick well past what would be the full pause time -- should NOT spawn
    engine.tick(SPAWN_PAUSE_TICKS + 30);

    assertThat(countSpearGoblinDummies())
        .as("Timer should pause when enemy leaves range -- still 1 spawn")
        .isEqualTo(1);
  }

  @Test
  void spawnTimerResumesWhenEnemyReturns() {
    deployGoblinHut(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    // Place enemy within range
    Troop enemy = spawnEnemyTroop(DEPLOY_X + 3f, DEPLOY_Y);

    // Tick past spawnStartTime to get first spawn
    engine.tick(SPAWN_START_TICKS + 3);
    assertThat(countSpearGoblinDummies()).as("First spawn").isEqualTo(1);

    // Tick about half the pause time
    engine.tick(SPAWN_PAUSE_TICKS / 2);

    // Kill enemy to pause
    enemy.getHealth().takeDamage(enemy.getHealth().getCurrent());
    engine.tick(2);

    // Spawn new enemy to resume timer
    spawnEnemyTroop(DEPLOY_X + 2f, DEPLOY_Y);

    // Tick the remaining half of pause time + small buffer
    engine.tick(SPAWN_PAUSE_TICKS / 2 + 5);

    assertThat(countSpearGoblinDummies())
        .as("Timer should resume and produce 2nd spawn after enemy returns")
        .isEqualTo(2);
  }

  @Test
  void multipleSpawnsWithContinuousPresence() {
    deployGoblinHut(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    // Place persistent enemy within range
    spawnEnemyTroop(DEPLOY_X + 3f, DEPLOY_Y);

    // Tick past spawnStartTime (1.0s) -> 1st spawn
    engine.tick(SPAWN_START_TICKS + 3);
    assertThat(countSpearGoblinDummies()).as("After spawnStartTime").isEqualTo(1);

    // Tick past spawnPauseTime (2.1s) -> 2nd spawn
    engine.tick(SPAWN_PAUSE_TICKS + 3);
    assertThat(countSpearGoblinDummies()).as("After first pause").isEqualTo(2);

    // Tick past another spawnPauseTime (2.1s) -> 3rd spawn
    engine.tick(SPAWN_PAUSE_TICKS + 3);
    assertThat(countSpearGoblinDummies()).as("After second pause").isEqualTo(3);
  }

  @Test
  void deathSpawnOnBuildingDestruction() {
    deployGoblinHut(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    // Kill the building
    Building building = findBuilding();
    building.getHealth().takeDamage(building.getHealth().getCurrent());

    // Tick to process death + death spawn
    engine.tick(2);

    // Death spawn should be 1 SpearGoblin (not Dummy)
    assertThat(countSpearGoblins()).as("Death spawn should produce 1 SpearGoblin").isEqualTo(1);
  }

  @Test
  void deathSpawnOnLifetimeExpiry() {
    deployGoblinHut(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    // Tick through 30s lifetime (900 ticks) + buffer
    engine.tick(900 + 5);

    Building building = findBuilding();
    assertThat(building.getHealth().isDead())
        .as("Building should be dead after 30s lifetime")
        .isTrue();

    // Death spawn SpearGoblin should have appeared
    assertThat(countSpearGoblins())
        .as("Death spawn should produce SpearGoblin on lifetime expiry")
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void longRangeAttackerDoesNotTriggerSpawning() {
    deployGoblinHut(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    // Place enemy far outside 6-tile range
    spawnEnemyTroop(DEPLOY_X, DEPLOY_Y + 8f);

    // Tick well past spawnStartTime
    engine.tick(SPAWN_START_TICKS + SPAWN_PAUSE_TICKS + 30);

    assertThat(countSpearGoblinDummies())
        .as("Enemy at >6 tiles should not trigger spawning")
        .isEqualTo(0);
  }

  @Test
  void spawnedGoblinAppearsTowardEnemy() {
    deployGoblinHut(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    // Place enemy to the right of the hut
    spawnEnemyTroop(DEPLOY_X + 3f, DEPLOY_Y);
    engine.tick(SPAWN_START_TICKS + 3);

    Troop goblin = findFirstSpearGoblinDummy();
    assertThat(goblin).as("Goblin should have spawned").isNotNull();
    // Goblin should be offset to the right of the building center (toward the enemy)
    assertThat(goblin.getPosition().getX())
        .as("Goblin X should be right of building center")
        .isGreaterThan(DEPLOY_X);
    assertThat(goblin.getPosition().getY())
        .as("Goblin Y should be roughly at building Y")
        .isCloseTo(DEPLOY_Y, within(0.5f));
  }

  // -- Helpers --

  private void deployGoblinHut(float x, float y) {
    PlayerActionDTO action = PlayerActionDTO.builder().handIndex(0).x(x).y(y).build();
    engine.queueAction(bluePlayer, action);
  }

  private Troop spawnEnemyTroop(float x, float y) {
    Troop enemy =
        Troop.builder()
            .name("TestEnemy")
            .team(Team.RED)
            .position(new Position(x, y))
            .health(new Health(1000))
            .movement(new Movement(0f, 1.0f, 0.5f, 0.5f, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .damage(50)
                    .range(1.0f)
                    .sightRange(5.0f)
                    .attackCooldown(1.0f)
                    .targetType(TargetType.GROUND)
                    .build())
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    engine.spawn(enemy);
    return enemy;
  }

  private Troop findFirstSpearGoblinDummy() {
    return engine.getGameState().getEntitiesOfType(Troop.class).stream()
        .filter(t -> "SpearGoblin_Dummy".equals(t.getName()) && t.getTeam() == Team.BLUE)
        .findFirst()
        .orElse(null);
  }

  private long countSpearGoblinDummies() {
    return engine.getGameState().getEntitiesOfType(Troop.class).stream()
        .filter(t -> "SpearGoblin_Dummy".equals(t.getName()) && t.getTeam() == Team.BLUE)
        .count();
  }

  private long countSpearGoblins() {
    return engine.getGameState().getEntitiesOfType(Troop.class).stream()
        .filter(t -> "SpearGoblin".equals(t.getName()) && t.getTeam() == Team.BLUE)
        .count();
  }

  private Building findBuilding() {
    return engine.getGameState().getEntitiesOfType(Building.class).stream()
        .filter(b -> "GoblinHut_Rework".equals(b.getName()) && b.getTeam() == Team.BLUE)
        .findFirst()
        .orElse(null);
  }

  private Deck buildDeckWith(Card card) {
    List<Card> cards = new java.util.ArrayList<>();
    for (int i = 0; i < 8; i++) {
      cards.add(card);
    }
    return new Deck(cards);
  }
}
