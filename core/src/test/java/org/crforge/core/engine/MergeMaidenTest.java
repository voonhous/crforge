package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.CardVariant;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Deck;
import org.crforge.core.player.LevelConfig;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for MergeMaiden (Spirit Empress) variant card mechanics. */
class MergeMaidenTest {

  private GameState gameState;
  private DeploymentSystem deploymentSystem;

  // Variant unit stats
  private TroopStats mountedStats;
  private TroopStats normalStats;

  // The variant card
  private Card mergeMaidenCard;

  // Mirror card for interaction tests
  private Card mirrorCard;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    gameState = new GameState();
    deploymentSystem = new DeploymentSystem(gameState, new AoeDamageService(gameState));

    mountedStats =
        TroopStats.builder()
            .name("MergeMaiden_Mounted")
            .health(466)
            .damage(121)
            .speed(1.0f)
            .range(5.0f)
            .attackCooldown(1.4f)
            .loadTime(0.9f)
            .movementType(MovementType.AIR)
            .targetType(TargetType.ALL)
            .build();

    normalStats =
        TroopStats.builder()
            .name("MergeMaiden_Normal")
            .health(486)
            .damage(121)
            .speed(1.0f)
            .range(1.2f)
            .attackCooldown(1.1f)
            .loadTime(0.8f)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.GROUND)
            .build();

    // Variants ordered by descending manaTrigger (mounted first at 6, normal at 3)
    List<CardVariant> variants =
        List.of(
            new CardVariant("MergeMaiden_Mounted", 6, 6, mountedStats),
            new CardVariant("MergeMaiden_Normal", 3, 3, normalStats));

    mergeMaidenCard =
        Card.builder()
            .id("mergemaiden")
            .name("MergeMaiden")
            .type(CardType.TROOP)
            .cost(6)
            .rarity(Rarity.LEGENDARY)
            .variants(variants)
            .mirrorCopiesVariant(true)
            .build();

    mirrorCard =
        Card.builder()
            .id("mirror")
            .name("Mirror")
            .type(CardType.SPELL)
            .cost(1)
            .rarity(Rarity.EPIC)
            .mirror(true)
            .build();
  }

  private Player createPlayer(Team team, Card card) {
    return new Player(
        team,
        new Deck(new ArrayList<>(Collections.nCopies(8, card))),
        false,
        LevelConfig.standard(),
        new Random(42));
  }

  private PlayerActionDTO playAt(int handIndex, float x, float y) {
    return PlayerActionDTO.builder().handIndex(handIndex).x(x).y(y).build();
  }

  // -- Card.resolveVariant unit tests --

  @Test
  void resolveVariant_selectsMountedAt6Elixir() {
    Card resolved = mergeMaidenCard.resolveVariant(6);

    assertThat(resolved.getUnitStats()).isNotNull();
    assertThat(resolved.getUnitStats().getName()).isEqualTo("MergeMaiden_Mounted");
    assertThat(resolved.getUnitStats().getMovementType()).isEqualTo(MovementType.AIR);
  }

  @Test
  void resolveVariant_selectsMountedAt10Elixir() {
    Card resolved = mergeMaidenCard.resolveVariant(10);

    assertThat(resolved.getUnitStats().getName()).isEqualTo("MergeMaiden_Mounted");
    assertThat(resolved.getUnitStats().getMovementType()).isEqualTo(MovementType.AIR);
  }

  @Test
  void resolveVariant_selectsNormalAt5Elixir() {
    // Simulated cost-reduced scenario: player has only 5 elixir
    Card resolved = mergeMaidenCard.resolveVariant(5);

    assertThat(resolved.getUnitStats()).isNotNull();
    assertThat(resolved.getUnitStats().getName()).isEqualTo("MergeMaiden_Normal");
    assertThat(resolved.getUnitStats().getMovementType()).isEqualTo(MovementType.GROUND);
  }

  @Test
  void resolveVariant_selectsNormalAt3Elixir() {
    Card resolved = mergeMaidenCard.resolveVariant(3);

    assertThat(resolved.getUnitStats().getName()).isEqualTo("MergeMaiden_Normal");
    assertThat(resolved.getUnitStats().getMovementType()).isEqualTo(MovementType.GROUND);
  }

  @Test
  void resolveVariant_returnsSelfForNonVariantCard() {
    Card knightCard =
        Card.builder()
            .id("knight")
            .name("Knight")
            .type(CardType.TROOP)
            .cost(3)
            .unitStats(TroopStats.builder().name("Knight").health(690).damage(79).build())
            .build();

    Card resolved = knightCard.resolveVariant(10);

    assertThat(resolved).isSameAs(knightCard);
  }

  @Test
  void resolveVariant_resolvedCardHasVariantCost() {
    Card mounted = mergeMaidenCard.resolveVariant(6);
    assertThat(mounted.getCost()).isEqualTo(6);

    Card normal = mergeMaidenCard.resolveVariant(5);
    assertThat(normal.getCost()).isEqualTo(3);
  }

  @Test
  void resolveVariant_resolvedCardHasNullVariants() {
    Card resolved = mergeMaidenCard.resolveVariant(6);

    // Resolved card should have no variants (prevents re-resolution)
    assertThat(resolved.hasVariants()).isFalse();
    // Calling resolveVariant again should return the same object
    assertThat(resolved.resolveVariant(3)).isSameAs(resolved);
  }

  // -- Deployment integration tests --

  @Test
  void deployment_spawnsMountedVariantWithFullElixir() {
    Player player = createPlayer(Team.BLUE, mergeMaidenCard);
    // Fill elixir to 10 so we have enough and trigger mounted form (>= 6)
    player.getElixir().update(100f);

    deploymentSystem.queueAction(player, playAt(0, 9f, 10f));
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);
    gameState.processPending();

    List<Troop> troops =
        gameState.getEntities().stream()
            .filter(e -> e instanceof Troop)
            .map(e -> (Troop) e)
            .toList();
    assertThat(troops).hasSize(1);
    assertThat(troops.get(0).getName()).isEqualTo("MergeMaiden_Mounted");
    assertThat(troops.get(0).getMovementType()).isEqualTo(MovementType.AIR);
  }

  @Test
  void deployment_spawnsNormalVariantWithReducedElixir() {
    Player player = createPlayer(Team.BLUE, mergeMaidenCard);
    // Set elixir to exactly 5 (not enough for mounted trigger of 6, but >= normal trigger of 3)
    // Start with 5, spend all, then regen to 5
    player.getElixir().spend((int) player.getElixir().getCurrent());
    player.getElixir().add(5.0f);
    assertThat(player.getElixir().getFloor()).isEqualTo(5);

    // The card costs 6 normally, but at 5 elixir we can't afford it.
    // This scenario requires the card cost to be reduced externally.
    // Instead, test the variant resolution directly through a card with cost=3.
    // Create a variant card where the base cost matches what the player can afford.
    List<CardVariant> variants =
        List.of(
            new CardVariant("MergeMaiden_Mounted", 6, 6, mountedStats),
            new CardVariant("MergeMaiden_Normal", 3, 3, normalStats));
    Card reducedCostCard =
        Card.builder()
            .id("mergemaiden")
            .name("MergeMaiden")
            .type(CardType.TROOP)
            .cost(3)
            .rarity(Rarity.LEGENDARY)
            .variants(variants)
            .mirrorCopiesVariant(true)
            .build();

    Player player2 = createPlayer(Team.BLUE, reducedCostCard);
    player2.getElixir().spend((int) player2.getElixir().getCurrent());
    player2.getElixir().add(5.0f);

    deploymentSystem.queueAction(player2, playAt(0, 9f, 10f));
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);
    gameState.processPending();

    List<Troop> troops =
        gameState.getEntities().stream()
            .filter(e -> e instanceof Troop)
            .map(e -> (Troop) e)
            .toList();
    assertThat(troops).hasSize(1);
    assertThat(troops.get(0).getName()).isEqualTo("MergeMaiden_Normal");
    assertThat(troops.get(0).getMovementType()).isEqualTo(MovementType.GROUND);
  }

  @Test
  void deployment_correctHealthScaling() {
    int level = 11;
    LevelConfig config = new LevelConfig(level);
    Player player =
        new Player(
            Team.BLUE,
            new Deck(new ArrayList<>(Collections.nCopies(8, mergeMaidenCard))),
            false,
            config,
            new Random(42));
    player.getElixir().update(100f);

    deploymentSystem.queueAction(player, playAt(0, 9f, 10f));
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);
    gameState.processPending();

    List<Troop> troops =
        gameState.getEntities().stream()
            .filter(e -> e instanceof Troop)
            .map(e -> (Troop) e)
            .toList();
    assertThat(troops).hasSize(1);

    // Should scale using Legendary rarity at level 11
    int expectedHp = LevelScaling.scaleCard(466, Rarity.LEGENDARY, level);
    assertThat(troops.get(0).getHealth().getMax()).isEqualTo(expectedHp);
  }

  // -- Mirror interaction tests --

  @Test
  void mirror_replaysResolvedVariant() {
    // Play MergeMaiden (resolves to Mounted with 10 elixir), then Mirror should replay Mounted
    Player player =
        new Player(
            Team.BLUE,
            new Deck(createMixedDeck(mergeMaidenCard, mirrorCard)),
            false,
            LevelConfig.standard(),
            new Random(42));
    player.getElixir().update(100f);

    // Find and play MergeMaiden from hand
    int mmSlot = findCardInHand(player, "mergemaiden");
    assertThat(mmSlot).as("MergeMaiden should be in hand").isGreaterThanOrEqualTo(0);

    deploymentSystem.queueAction(player, playAt(mmSlot, 9f, 10f));
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);
    gameState.processPending();

    // Verify Mounted was spawned
    List<Troop> troops =
        gameState.getEntities().stream()
            .filter(e -> e instanceof Troop)
            .map(e -> (Troop) e)
            .toList();
    assertThat(troops).hasSize(1);
    assertThat(troops.get(0).getName()).isEqualTo("MergeMaiden_Mounted");

    // Now play Mirror
    player.getElixir().update(100f);
    int mirrorSlot = findCardInHand(player, "mirror");
    assertThat(mirrorSlot).as("Mirror should be in hand").isGreaterThanOrEqualTo(0);

    deploymentSystem.queueAction(player, playAt(mirrorSlot, 9f, 12f));
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);
    gameState.processPending();

    // Mirror should replay Mounted (the resolved variant, not re-evaluate)
    List<Troop> allTroops =
        gameState.getEntities().stream()
            .filter(e -> e instanceof Troop)
            .map(e -> (Troop) e)
            .toList();
    assertThat(allTroops).hasSize(2);
    assertThat(allTroops.get(1).getName()).isEqualTo("MergeMaiden_Mounted");
  }

  @Test
  void mirror_usesVariantCostNotBaseCost() {
    // Play MergeMaiden with 10 elixir (Mounted, cost=6), then Mirror should cost 6+1=7
    Player player =
        new Player(
            Team.BLUE,
            new Deck(createMixedDeck(mergeMaidenCard, mirrorCard)),
            false,
            LevelConfig.standard(),
            new Random(42));
    player.getElixir().update(100f);

    // Play MergeMaiden
    int mmSlot = findCardInHand(player, "mergemaiden");
    deploymentSystem.queueAction(player, playAt(mmSlot, 9f, 10f));
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);
    gameState.processPending();

    // The resolved card (Mounted) has cost=6, so lastPlayedCard.getCost() = 6
    // Mirror should cost min(6 + 1, 10) = 7
    player.getElixir().update(100f);
    float elixirBefore = player.getElixir().getCurrent();
    int mirrorSlot = findCardInHand(player, "mirror");
    player.tryPlayCard(playAt(mirrorSlot, 9f, 12f));
    float elixirAfter = player.getElixir().getCurrent();

    assertThat(elixirBefore - elixirAfter).isEqualTo(7f);
  }

  @Test
  void mirror_doesNotReEvaluateTriggers() {
    // Play MergeMaiden with 5 elixir (Normal form, cost=3), then Mirror with 10 elixir
    // Mirror should still deploy Normal, not re-evaluate to Mounted

    // Use a cost-reduced variant card so we can actually play it at 5 elixir
    List<CardVariant> variants =
        List.of(
            new CardVariant("MergeMaiden_Mounted", 6, 6, mountedStats),
            new CardVariant("MergeMaiden_Normal", 3, 3, normalStats));
    Card reducedCard =
        Card.builder()
            .id("mergemaiden")
            .name("MergeMaiden")
            .type(CardType.TROOP)
            .cost(3)
            .rarity(Rarity.LEGENDARY)
            .variants(variants)
            .mirrorCopiesVariant(true)
            .build();

    Player player =
        new Player(
            Team.BLUE,
            new Deck(createMixedDeck(reducedCard, mirrorCard)),
            false,
            LevelConfig.standard(),
            new Random(42));

    // Set elixir to 5 (enough for cost=3, but below mounted trigger of 6)
    player.getElixir().spend((int) player.getElixir().getCurrent());
    player.getElixir().add(5.0f);

    int mmSlot = findCardInHand(player, "mergemaiden");
    assertThat(mmSlot).isGreaterThanOrEqualTo(0);

    deploymentSystem.queueAction(player, playAt(mmSlot, 9f, 10f));
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);
    gameState.processPending();

    // Verify Normal was spawned
    List<Troop> troops =
        gameState.getEntities().stream()
            .filter(e -> e instanceof Troop)
            .map(e -> (Troop) e)
            .toList();
    assertThat(troops).hasSize(1);
    assertThat(troops.get(0).getName()).isEqualTo("MergeMaiden_Normal");

    // Now play Mirror with full elixir -- should still be Normal (no re-evaluation)
    player.getElixir().update(100f);
    int mirrorSlot = findCardInHand(player, "mirror");
    assertThat(mirrorSlot).isGreaterThanOrEqualTo(0);

    deploymentSystem.queueAction(player, playAt(mirrorSlot, 9f, 12f));
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);
    gameState.processPending();

    // Mirror should replay Normal, not Mounted, despite having 10 elixir
    List<Troop> allTroops =
        gameState.getEntities().stream()
            .filter(e -> e instanceof Troop)
            .map(e -> (Troop) e)
            .toList();
    assertThat(allTroops).hasSize(2);
    assertThat(allTroops.get(1).getName()).isEqualTo("MergeMaiden_Normal");
  }

  // -- Helper methods --

  /** Creates a deck of 4 copies of card1 and 4 copies of card2. */
  private List<Card> createMixedDeck(Card card1, Card card2) {
    List<Card> deck = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      deck.add(card1);
    }
    for (int i = 0; i < 4; i++) {
      deck.add(card2);
    }
    return deck;
  }

  private int findCardInHand(Player player, String cardId) {
    for (int i = 0; i < 4; i++) {
      Card c = player.getHand().getCard(i);
      if (c != null && cardId.equals(c.getId())) {
        return i;
      }
    }
    return -1;
  }
}
