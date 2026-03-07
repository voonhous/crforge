package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.component.Health;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Deck;
import org.crforge.core.player.LevelConfig;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeploymentSystemTest {

  private GameState gameState;
  private DeploymentSystem deploymentSystem;
  private Player player;

  @BeforeEach
  void setUp() {
    gameState = new GameState();
    CombatSystem combatSystem = new CombatSystem(gameState);
    deploymentSystem = new DeploymentSystem(gameState, combatSystem);

    // Create a deck of 8 dummy cards
    List<Card> cards = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      cards.add(Card.builder()
          .name("Troop " + i)
          .cost(3)
          .type(CardType.TROOP)
          .build());
    }
    Deck deck = new Deck(cards);

    // Player starts with 5.0 Elixir by default
    player = new Player(Team.BLUE, deck, false);
  }

  @Test
  void testSuccessfulDeployment() {
    // Capture state before action
    Card cardSlot0 = player.getHand().getCard(0);
    float initialElixir = player.getElixir().getCurrent(); // 5.0

    // Create action to play card at slot 0 (Cost 3)
    PlayerActionDTO action = PlayerActionDTO.builder()
        .handIndex(0)
        .x(10f)
        .y(20f)
        .build();

    // Queue and Update
    deploymentSystem.queueAction(player, action);
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);

    // 1. Verify Elixir Spent (5.0 - 3.0 = 2.0)
    assertThat(player.getElixir().getCurrent()).isEqualTo(initialElixir - 3);

    // 2. Verify Hand Cycled (Slot 0 should have a new card)
    assertThat(player.getHand().getCard(0)).isNotEqualTo(cardSlot0);
  }

  @Test
  void testInsufficientElixir() {
    // Drain player elixir to 1.0 (Card costs 3)
    player.getElixir().spend(4);
    assertThat(player.getElixir().getCurrent()).isEqualTo(1.0f);

    Card cardSlot0 = player.getHand().getCard(0);

    // Create action
    PlayerActionDTO action = PlayerActionDTO.builder()
        .handIndex(0)
        .x(10f)
        .y(20f)
        .build();

    // Queue and Update
    deploymentSystem.queueAction(player, action);
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);

    // 1. Verify Elixir Unchanged
    assertThat(player.getElixir().getCurrent()).isEqualTo(1.0f);

    // 2. Verify Hand NOT Cycled
    assertThat(player.getHand().getCard(0)).isEqualTo(cardSlot0);
  }

  @Test
  void testDeploySpawnerBuilding() {
    // Create a Tombstone-like card
    TroopStats skeletonStats = TroopStats.builder()
        .name("Skeleton")
        .build();

    Card tombstone = Card.builder()
        .id("tombstone")
        .name("Tombstone")
        .type(CardType.BUILDING)
        .cost(3)
        .buildingHealth(500)
        .spawnInterval(3.0f)
        .deathSpawnCount(4)
        .troop(TroopStats.builder().name("TombstoneBuilding").build()) // Index 0: Building
        .troop(skeletonStats) // Index 1: Spawned Unit
        .build();

    // Deck of all Tombstones to bypass shuffle RNG
    List<Card> allTombstones = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      allTombstones.add(tombstone);
    }

    Player spawnerPlayer = new Player(Team.RED, new Deck(allTombstones), false);

    PlayerActionDTO action = PlayerActionDTO.builder()
        .handIndex(0)
        .x(5f)
        .y(5f)
        .build();

    deploymentSystem.queueAction(spawnerPlayer, action);
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);

    // Process pending spawns to move them to the active entity list
    gameState.processPending();

    // Verify a Building was added
    assertThat(gameState.getEntities()).hasSize(1);
    Entity entity = gameState.getEntities().get(0);

    assertThat(entity).isInstanceOf(Building.class);
    assertThat(entity.getName()).isEqualTo("Tombstone");

    // Check component
    assertThat(entity.getSpawner()).isNotNull();
    assertThat(entity.getSpawner().getSpawnInterval()).isEqualTo(3.0f);
    assertThat(entity.getSpawner().getDeathSpawnCount()).isEqualTo(4);
  }

  @Test
  void testDeploySpawnerBuilding_shouldPropagateSpawnPauseTime() {
    // Regression test: Ensure spawnPauseTime is passed to SpawnerComponent
    TroopStats skeletonStats = TroopStats.builder()
        .name("Skeleton")
        .build();

    float spawnPauseTime = 3.5f;

    Card tombstone = Card.builder()
        .id("tombstone")
        .name("Tombstone")
        .type(CardType.BUILDING)
        .cost(3)
        .buildingHealth(500)
        .spawnInterval(0.5f)
        .spawnPauseTime(spawnPauseTime)
        .deathSpawnCount(0)
        .troop(TroopStats.builder().name("TombstoneBuilding").build())
        .troop(skeletonStats)
        .build();

    List<Card> deckCards = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      deckCards.add(tombstone);
    }

    Player player = new Player(Team.RED, new Deck(deckCards), false);

    PlayerActionDTO action = PlayerActionDTO.builder()
        .handIndex(0)
        .x(5f)
        .y(5f)
        .build();

    deploymentSystem.queueAction(player, action);
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);
    gameState.processPending();

    Entity entity = gameState.getEntities().get(0);
    assertThat(entity.getSpawner()).isNotNull();
    assertThat(entity.getSpawner().getSpawnPauseTime()).isEqualTo(spawnPauseTime);
  }

  @Test
  void testDeployRegularBuilding() {
    Card cannon = Card.builder()
        .id("cannon")
        .name("Cannon")
        .type(CardType.BUILDING)
        .cost(3)
        .buildingHealth(500)
        // No spawn stats
        .build();

    // Deck of all Cannons to bypass shuffle RNG
    List<Card> allCannons = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      allCannons.add(cannon);
    }

    Player buildingPlayer = new Player(Team.BLUE, new Deck(allCannons), false);

    PlayerActionDTO action = PlayerActionDTO.builder()
        .handIndex(0)
        .x(10f)
        .y(10f)
        .build();

    deploymentSystem.queueAction(buildingPlayer, action);
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);

    // Process pending spawns
    gameState.processPending();

    assertThat(gameState.getEntities()).hasSize(1);
    Entity entity = gameState.getEntities().get(0);

    // Should be a Building with null spawner
    assertThat(entity).isInstanceOf(Building.class);
    assertThat(entity.getSpawner()).isNull();
  }

  @Test
  void testCastSpell() {
    // We need a target for the spell to hit to verify damage
    Troop enemy = Troop.builder()
        .name("Target")
        .team(Team.RED)
        .position(new Position(10f, 10f))
        .health(new Health(100))
        .build();
    enemy.onSpawn();
    gameState.spawnEntity(enemy);
    gameState.processPending(); // Add enemy to alive entities
    enemy.update(2.0f); // Finish deploying so enemy is targetable

    Card fireball = Card.builder()
        .id("fireball")
        .name("Fireball")
        .type(CardType.SPELL)
        .cost(4)
        .projectile(ProjectileStats.builder()
            .damage(50)
            .radius(2.0f)
            .speed(0) // 0 speed = Instant/Direct application
            .build())
        .build();

    // Deck of all Fireballs
    List<Card> allFireballs = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      allFireballs.add(fireball);
    }

    Player spellPlayer = new Player(Team.BLUE, new Deck(allFireballs), false);
    // Give enough elixir
    spellPlayer.getElixir().update(100f);

    // Cast fireball at enemy position
    PlayerActionDTO action = PlayerActionDTO.builder()
        .handIndex(0)
        .x(10f)
        .y(10f)
        .build();

    deploymentSystem.queueAction(spellPlayer, action);
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);

    // Verify spell effect (damage) was applied immediately via CombatSystem.applySpellDamage
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(50);
  }

  @Test
  void testCastTravelingSpell_createsProjectile() {
    // Place a target enemy
    Troop enemy = Troop.builder()
        .name("Target")
        .team(Team.RED)
        .position(new Position(10f, 10f))
        .health(new Health(500))
        .build();
    enemy.onSpawn();
    gameState.spawnEntity(enemy);
    gameState.processPending();

    // Use new ProjectileStats structure
    Card arrows = Card.builder()
        .id("arrows")
        .name("Arrows")
        .type(CardType.SPELL)
        .cost(3)
        .projectile(ProjectileStats.builder()
            .damage(303)
            .radius(4.0f)
            .speed(8.0f) // > 0 means it creates a projectile
            .build())
        .build();

    List<Card> allArrows = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      allArrows.add(arrows);
    }

    Player spellPlayer = new Player(Team.BLUE, new Deck(allArrows), false);
    spellPlayer.getElixir().update(100f);

    PlayerActionDTO action = PlayerActionDTO.builder()
        .handIndex(0)
        .x(10f)
        .y(10f)
        .build();

    deploymentSystem.queueAction(spellPlayer, action);
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);

    // Traveling spell should NOT deal immediate damage
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(500);

    // Should have spawned a projectile
    assertThat(gameState.getProjectiles()).hasSize(1);
    assertThat(gameState.getProjectiles().get(0).isPositionTargeted()).isTrue();
    assertThat(gameState.getProjectiles().get(0).getDamage()).isEqualTo(303);
  }

  @Test
  void testCastSpell_shouldScaleDamageByLevel() {
    // Target with high HP so we can measure damage
    Troop enemy = Troop.builder()
        .name("Target")
        .team(Team.RED)
        .position(new Position(10f, 10f))
        .health(new Health(10000))
        .build();
    enemy.onSpawn();
    gameState.spawnEntity(enemy);
    gameState.processPending();
    enemy.update(2.0f);

    int baseDamage = 100;
    Rarity rarity = Rarity.RARE;
    int level = 11;

    Card fireball = Card.builder()
        .id("fireball")
        .name("Fireball")
        .type(CardType.SPELL)
        .cost(4)
        .rarity(rarity)
        .projectile(ProjectileStats.builder()
            .damage(baseDamage)
            .radius(2.0f)
            .speed(0) // Instant
            .build())
        .build();

    List<Card> deck = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      deck.add(fireball);
    }

    // Player at level 11 with Rare card
    Player spellPlayer = new Player(Team.BLUE, new Deck(deck), false,
        new LevelConfig(level));
    spellPlayer.getElixir().update(100f);

    PlayerActionDTO action = PlayerActionDTO.builder()
        .handIndex(0)
        .x(10f)
        .y(10f)
        .build();

    deploymentSystem.queueAction(spellPlayer, action);
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);

    // Damage should be scaled, not raw
    int expectedDamage = LevelScaling.scaleCard(baseDamage, rarity, level);
    assertThat(expectedDamage).isGreaterThan(baseDamage); // Sanity check scaling did something
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(10000 - expectedDamage);
  }
}
