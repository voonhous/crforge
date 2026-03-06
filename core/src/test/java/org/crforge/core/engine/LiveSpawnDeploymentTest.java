package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.crforge.core.card.Card;
import org.crfoge.data.card.CardRegistry;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests that troops with liveSpawn (e.g. Witch) correctly create a SpawnerComponent
 * at deployment time, and that the spawner produces units after the expected delay.
 */
class LiveSpawnDeploymentTest {

  private GameEngine engine;
  private Player bluePlayer;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();

    List<Card> deckCards = new ArrayList<>();
    deckCards.add(Objects.requireNonNull(CardRegistry.get("witch"), "witch not found"));
    deckCards.add(Objects.requireNonNull(CardRegistry.get("knight"), "knight not found"));
    deckCards.add(Objects.requireNonNull(CardRegistry.get("giant"), "giant not found"));
    deckCards.add(Objects.requireNonNull(CardRegistry.get("musketeer"), "musketeer not found"));
    deckCards.add(Objects.requireNonNull(CardRegistry.get("archer"), "archer not found"));
    deckCards.add(Objects.requireNonNull(CardRegistry.get("goblins"), "goblins not found"));
    deckCards.add(Objects.requireNonNull(CardRegistry.get("valkyrie"), "valkyrie not found"));
    deckCards.add(Objects.requireNonNull(CardRegistry.get("bomber"), "bomber not found"));

    bluePlayer = new Player(Team.BLUE, new Deck(deckCards), false);
    Player redPlayer = new Player(Team.RED, new Deck(new ArrayList<>(deckCards)), false);

    Standard1v1Match match = new Standard1v1Match();
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);

    engine = new GameEngine();
    engine.setMatch(match);
    engine.initMatch();
  }

  /**
   * Find the hand slot containing a card by name, or -1 if not in hand.
   */
  private int findCardSlot(String name) {
    for (int i = 0; i < 4; i++) {
      Card c = bluePlayer.getHand().getCard(i);
      if (c != null && name.equals(c.getName())) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Cycle through the hand by playing cheap cards until the target card appears.
   * Gives enough elixir before each play. Returns the slot index of the target card.
   */
  private int cycleUntilInHand(String cardName) {
    for (int attempt = 0; attempt < 8; attempt++) {
      int slot = findCardSlot(cardName);
      if (slot != -1) {
        return slot;
      }
      // Play the cheapest card in hand to cycle
      int cheapestSlot = 0;
      int cheapestCost = Integer.MAX_VALUE;
      for (int i = 0; i < 4; i++) {
        Card c = bluePlayer.getHand().getCard(i);
        if (c != null && c.getCost() < cheapestCost) {
          cheapestCost = c.getCost();
          cheapestSlot = i;
        }
      }
      // Give enough elixir and play
      giveMaxElixir();
      engine.queueAction(bluePlayer, PlayerActionDTO.play(cheapestSlot, 9f, 5f));
      engine.tick();
      engine.tick();
    }
    return findCardSlot(cardName);
  }

  @Test
  void witch_shouldHaveSpawnerComponentAfterDeployment() {
    int slot = cycleUntilInHand("Witch");
    assertThat(slot).as("Witch should be reachable in hand").isGreaterThanOrEqualTo(0);

    // Give enough elixir for Witch (cost 5)
    giveMaxElixir();
    engine.queueAction(bluePlayer, PlayerActionDTO.play(slot, 9f, 10f));

    engine.tick();
    engine.tick();

    Troop witch = engine.getGameState().getAliveEntities().stream()
        .filter(e -> "Witch".equals(e.getName()))
        .map(e -> (Troop) e)
        .findFirst()
        .orElse(null);

    assertThat(witch).as("Witch should be spawned").isNotNull();
    assertThat(witch.getSpawner()).as("Witch should have a SpawnerComponent").isNotNull();
    assertThat(witch.getSpawner().hasLiveSpawn())
        .as("Witch spawner should be a live spawner").isTrue();
    assertThat(witch.getSpawner().getSpawnStats()).isNotNull();
    assertThat(witch.getSpawner().getSpawnStats().getName()).isEqualTo("Skeleton");
    assertThat(witch.getSpawner().getUnitsPerWave()).isEqualTo(4);
  }

  @Test
  void witch_shouldSpawnSkeletonsAfterDelay() {
    int slot = cycleUntilInHand("Witch");
    assertThat(slot).as("Witch should be reachable in hand").isGreaterThanOrEqualTo(0);

    giveMaxElixir();
    engine.queueAction(bluePlayer, PlayerActionDTO.play(slot, 9f, 10f));

    // Run past deployment time (1.0s) but not past spawnStartTime (1.0s)
    engine.runSeconds(0.5f);

    // No skeletons yet (still deploying)
    long skeletonCount = countEntitiesByName("Skeleton");
    assertThat(skeletonCount).as("No skeletons during deploy phase").isZero();

    // Run past deploy time (1.0s) + spawnStartTime (1.0s)
    engine.runSeconds(2.0f);

    // Skeletons should have spawned (4 per wave)
    skeletonCount = countEntitiesByName("Skeleton");
    assertThat(skeletonCount).as("Witch should spawn skeletons after start delay")
        .isGreaterThanOrEqualTo(4);
  }

  @Test
  void witch_shouldSpawnMultipleWaves() {
    int slot = cycleUntilInHand("Witch");
    assertThat(slot).as("Witch should be reachable in hand").isGreaterThanOrEqualTo(0);

    giveMaxElixir();
    engine.queueAction(bluePlayer, PlayerActionDTO.play(slot, 9f, 10f));

    // Run past deploy (1.0s) + spawnStartTime (1.0s) + spawnPauseTime (7.0s) + margin
    // First wave at ~2.0s, second wave at ~9.0s
    engine.runSeconds(10.0f);

    // Use getEntities() (all, not just alive) to count total spawned
    long totalSpawned = engine.getGameState().getEntities().stream()
        .filter(e -> "Skeleton".equals(e.getName()))
        .count();
    assertThat(totalSpawned).as("Witch should spawn multiple waves of skeletons")
        .isGreaterThanOrEqualTo(8);
  }

  /**
   * Set the player's elixir to max (10) so card plays always succeed.
   */
  private void giveMaxElixir() {
    // Spend all, then update for enough time to fill to 10
    float current = bluePlayer.getElixir().getCurrent();
    if (current < 10f) {
      // Regen rate is 1/2.8 per second, so 10 elixir takes 28s
      bluePlayer.getElixir().update(28f);
    }
  }

  private long countEntitiesByName(String name) {
    return engine.getGameState().getAliveEntities().stream()
        .filter(e -> name.equals(e.getName()))
        .count();
  }
}
