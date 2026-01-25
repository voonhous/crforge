package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Health;
import org.crforge.core.component.Position;
import org.crforge.core.entity.Building;
import org.crforge.core.entity.Entity;
import org.crforge.core.entity.Troop;
import org.crforge.core.player.Deck;
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
    deploymentSystem = new DeploymentSystem(gameState);

    // Create a deck of 8 dummy cards
    List<Card> cards = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      cards.add(Card.builder()
          .name("Troop " + i)
          .cost(3) // Cost is 3
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
    deploymentSystem.update();

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
    deploymentSystem.update();

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
    deploymentSystem.update();

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
    deploymentSystem.update();

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

    Card fireball = Card.builder()
        .id("fireball")
        .name("Fireball")
        .type(CardType.SPELL)
        .cost(4)
        .spellDamage(50)
        .spellRadius(2.0f)
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
    deploymentSystem.update();

    // Verify spell effect (damage) was applied immediately (DeploymentSystem.castSpell logic)
    // Note: The DeploymentSystem calls castSpell which applies damage directly for now
    assertThat(enemy.getHealth().getCurrent()).isEqualTo(50);
  }
}
