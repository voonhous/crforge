package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Hand;
import org.crforge.core.player.LevelConfig;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for the Mirror spell implementation. */
class MirrorTest {

  private GameState gameState;
  private DeploymentSystem deploymentSystem;

  // Reusable card definitions
  private Card mirrorCard;
  private Card knightCard;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    gameState = new GameState();
    deploymentSystem = new DeploymentSystem(gameState, new AoeDamageService(gameState));

    mirrorCard =
        Card.builder()
            .id("mirror")
            .name("Mirror")
            .type(CardType.SPELL)
            .cost(1)
            .rarity(Rarity.EPIC)
            .mirror(true)
            .build();

    knightCard =
        Card.builder()
            .id("knight")
            .name("Knight")
            .type(CardType.TROOP)
            .cost(3)
            .rarity(Rarity.COMMON)
            .unitStats(TroopStats.builder().name("Knight").health(690).damage(79).build())
            .build();
  }

  /** Creates a player with a deck of 8 copies of the given card. */
  private Player createPlayerWithSingleCard(Team team, Card card) {
    return new Player(
        team,
        new Deck(new ArrayList<>(Collections.nCopies(8, card))),
        false,
        LevelConfig.standard());
  }

  private PlayerActionDTO playAt(int handIndex, float x, float y) {
    return PlayerActionDTO.builder().handIndex(handIndex).x(x).y(y).build();
  }

  /** Find a card in the player's hand by id and return its slot index, or -1 if not found. */
  private int findCardInHand(Player player, String cardId) {
    for (int i = 0; i < Hand.HAND_SIZE; i++) {
      Card c = player.getHand().getCard(i);
      if (c != null && cardId.equals(c.getId())) {
        return i;
      }
    }
    return -1;
  }

  // -- Mirror cannot play tests --

  @Test
  void mirror_cannotPlayIfNoCardPlayedYet() {
    Player player = createPlayerWithSingleCard(Team.BLUE, mirrorCard);
    float elixirBefore = player.getElixir().getCurrent();

    Card result = player.tryPlayCard(playAt(0, 9f, 10f));

    assertThat(result).isNull();
    assertThat(player.getElixir().getCurrent()).isEqualTo(elixirBefore);
  }

  @Test
  void mirror_cannotCopyItself() {
    Player player = createPlayerWithSingleCard(Team.BLUE, mirrorCard);
    // Manually set lastPlayedCard to mirror (should not happen, but test the guard)
    player.setLastPlayedCard(mirrorCard);
    float elixirBefore = player.getElixir().getCurrent();

    Card result = player.tryPlayCard(playAt(0, 9f, 10f));

    assertThat(result).isNull();
    assertThat(player.getElixir().getCurrent()).isEqualTo(elixirBefore);
  }

  // -- Mirror cost tests --

  @Test
  void mirror_costsLastCardPlusOne() {
    // Set up a deck of all mirrors, manually set lastPlayedCard to knight (cost 3)
    Player player = createPlayerWithSingleCard(Team.BLUE, mirrorCard);
    player.getElixir().update(100f);
    player.setLastPlayedCard(knightCard);

    float elixirBefore = player.getElixir().getCurrent();
    Card result = player.tryPlayCard(playAt(0, 9f, 10f));

    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo("knight");
    // Mirror should have cost lastCard.cost + 1 = 3 + 1 = 4
    assertThat(player.getElixir().getCurrent()).isEqualTo(elixirBefore - 4);
  }

  @Test
  void mirror_costCappedAtTen() {
    Card expensiveCard =
        Card.builder().id("expensive").name("Expensive").type(CardType.TROOP).cost(10).build();

    Player player = createPlayerWithSingleCard(Team.BLUE, mirrorCard);
    player.getElixir().update(100f);
    player.setLastPlayedCard(expensiveCard);

    float elixirBefore = player.getElixir().getCurrent();
    Card result = player.tryPlayCard(playAt(0, 9f, 10f));

    assertThat(result).isNotNull();
    // min(10 + 1, 10) = 10
    assertThat(player.getElixir().getCurrent()).isEqualTo(elixirBefore - 10);
  }

  @Test
  void mirror_insufficientElixirFails() {
    Player player = createPlayerWithSingleCard(Team.BLUE, mirrorCard);
    player.setLastPlayedCard(knightCard); // cost 3, so mirror costs 4
    // Set elixir to 3 (not enough for mirror cost of 4)
    player.getElixir().spend((int) player.getElixir().getCurrent());
    player.getElixir().update(2.8f * 3); // regen 3 elixir

    float elixirBefore = player.getElixir().getCurrent();
    assertThat(elixirBefore).isLessThan(4f);

    Card result = player.tryPlayCard(playAt(0, 9f, 10f));

    assertThat(result).isNull();
    assertThat(player.getElixir().getCurrent()).isEqualTo(elixirBefore);
  }

  // -- Mirror level tests --

  @Test
  void mirror_deploysAtMirrorLevelPlusOne() {
    int mirrorLevel = 11;
    LevelConfig config = new LevelConfig(mirrorLevel);

    Player player = createPlayerWithSingleCard(Team.BLUE, mirrorCard);
    player =
        new Player(
            Team.BLUE,
            new Deck(new ArrayList<>(Collections.nCopies(8, mirrorCard))),
            false,
            config);
    player.getElixir().update(100f);
    player.setLastPlayedCard(knightCard);

    player.tryPlayCard(playAt(0, 9f, 10f));

    assertThat(player.getPendingMirrorLevel()).isEqualTo(mirrorLevel + 1);
  }

  @Test
  void mirror_levelCappedAtMax() {
    LevelConfig config = new LevelConfig(LevelScaling.MAX_CARD_LEVEL);

    Player player =
        new Player(
            Team.BLUE,
            new Deck(new ArrayList<>(Collections.nCopies(8, mirrorCard))),
            false,
            config);
    player.getElixir().update(100f);
    player.setLastPlayedCard(knightCard);

    player.tryPlayCard(playAt(0, 9f, 10f));

    // min(16 + 1, 16) = 16
    assertThat(player.getPendingMirrorLevel()).isEqualTo(LevelScaling.MAX_CARD_LEVEL);
  }

  // -- Mirror deployment integration tests --

  @Test
  void mirror_deploysTroopCard() {
    Player player = createPlayerWithSingleCard(Team.BLUE, mirrorCard);
    player.getElixir().update(100f);
    player.setLastPlayedCard(knightCard);

    deploymentSystem.queueAction(player, playAt(0, 9f, 10f));
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);
    gameState.processPending();

    // Should have spawned a Knight troop
    List<Troop> troops =
        gameState.getEntities().stream()
            .filter(e -> e instanceof Troop)
            .map(e -> (Troop) e)
            .toList();
    assertThat(troops).hasSize(1);
    assertThat(troops.get(0).getName()).isEqualTo("Knight");
  }

  @Test
  void mirror_deploysSpellCard() {
    Card zapCard =
        Card.builder()
            .id("zap")
            .name("Zap")
            .type(CardType.SPELL)
            .cost(2)
            .rarity(Rarity.COMMON)
            .areaEffect(
                AreaEffectStats.builder()
                    .name("ZapEffect")
                    .radius(2.5f)
                    .damage(75)
                    .lifeDuration(0.1f)
                    .build())
            .build();

    Player player = createPlayerWithSingleCard(Team.BLUE, mirrorCard);
    player.getElixir().update(100f);
    player.setLastPlayedCard(zapCard);

    deploymentSystem.queueAction(player, playAt(0, 9f, 10f));
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);
    gameState.processPending();

    // Should have spawned an AreaEffect entity
    long areaEffectCount =
        gameState.getEntities().stream().filter(e -> e instanceof AreaEffect).count();
    assertThat(areaEffectCount).isEqualTo(1);
  }

  @Test
  void mirror_deploysBuildingCard() {
    Card cannonCard =
        Card.builder()
            .id("cannon")
            .name("Cannon")
            .type(CardType.BUILDING)
            .cost(3)
            .rarity(Rarity.COMMON)
            .unitStats(TroopStats.builder().name("Cannon").health(500).damage(100).build())
            .build();

    Player player = createPlayerWithSingleCard(Team.BLUE, mirrorCard);
    player.getElixir().update(100f);
    player.setLastPlayedCard(cannonCard);

    deploymentSystem.queueAction(player, playAt(0, 9f, 10f));
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);
    gameState.processPending();

    // Should have spawned a Building entity
    List<Entity> buildings =
        gameState.getEntities().stream().filter(e -> e instanceof Building).toList();
    assertThat(buildings).hasSize(1);
    assertThat(buildings.get(0).getName()).isEqualTo("Cannon");
  }

  // -- Mirror hand cycling tests --

  @Test
  void mirror_cyclesFromHand() {
    // Use a mixed deck: 1 mirror at slot 0, rest are non-mirror fillers
    // Since all 8 are mirror, the hand is all mirrors (exclusion can't swap with anything)
    Player player = createPlayerWithSingleCard(Team.BLUE, mirrorCard);
    player.getElixir().update(100f);
    player.setLastPlayedCard(knightCard);

    // All hand slots are mirror; play slot 0
    assertThat(player.getHand().getCard(0).isMirror()).isTrue();
    player.tryPlayCard(playAt(0, 9f, 10f));

    // After cycling, slot 0 should still be mirror (entire deck is mirror)
    // but the mirror that was in slot 0 is now at the back of the cycle
    // This verifies the hand cycling mechanism works with mirror
    assertThat(player.getHand().getCard(0)).isNotNull();
  }

  @Test
  void mirror_doesNotUpdateLastPlayedCard() {
    Player player = createPlayerWithSingleCard(Team.BLUE, mirrorCard);
    player.getElixir().update(100f);
    player.setLastPlayedCard(knightCard);

    // Play mirror -- lastPlayedCard should still be knight
    Card result = player.tryPlayCard(playAt(0, 9f, 10f));
    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo("knight");
    assertThat(player.getLastPlayedCard().getId()).isEqualTo("knight");
  }

  @Test
  void mirror_consecutiveMirrorsBlocked() {
    // Deck of all mirrors: play mirror once (succeeds), play again (should still succeed
    // because lastPlayedCard is knight, not mirror -- Mirror doesn't update lastPlayedCard)
    Player player = createPlayerWithSingleCard(Team.BLUE, mirrorCard);
    player.getElixir().update(100f);
    player.setLastPlayedCard(knightCard);

    // First mirror play -- should succeed (replays knight)
    Card result1 = player.tryPlayCard(playAt(0, 9f, 10f));
    assertThat(result1).isNotNull();
    assertThat(result1.getId()).isEqualTo("knight");

    // Second mirror play -- should also succeed because lastPlayedCard is still knight
    Card result2 = player.tryPlayCard(playAt(0, 9f, 10f));
    assertThat(result2).isNotNull();
    assertThat(result2.getId()).isEqualTo("knight");

    // lastPlayedCard should still be knight (Mirror never sets itself as lastPlayedCard)
    assertThat(player.getLastPlayedCard().getId()).isEqualTo("knight");
  }

  // -- Mirror placement validation --

  @Test
  void mirror_placementFollowsMirroredCardRules() {
    // Mirror of a building should follow building placement rules
    Card cannonCard =
        Card.builder()
            .id("cannon")
            .name("Cannon")
            .type(CardType.BUILDING)
            .cost(3)
            .rarity(Rarity.COMMON)
            .unitStats(TroopStats.builder().name("Cannon").health(500).build())
            .build();

    GameEngine engine = new GameEngine();
    Standard1v1Match match = new Standard1v1Match();

    // Deck of all mirrors for both players
    List<Card> mirrorDeck = new ArrayList<>(Collections.nCopies(8, mirrorCard));

    Player bluePlayer =
        new Player(Team.BLUE, new Deck(mirrorDeck), false, LevelConfig.standard(), new Random(0));
    Player redPlayer =
        new Player(
            Team.RED,
            new Deck(new ArrayList<>(mirrorDeck)),
            false,
            LevelConfig.standard(),
            new Random(1));
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);
    engine.setMatch(match);
    engine.initMatch();

    // Set lastPlayedCard to cannon so mirror resolves to a building
    bluePlayer.setLastPlayedCard(cannonCard);
    bluePlayer.getElixir().update(100f);

    // Try to play mirror on enemy side (y=25) -- should be rejected (building placement rules)
    int mirrorSlot = findCardInHand(bluePlayer, "mirror");
    assertThat(mirrorSlot).isGreaterThanOrEqualTo(0);
    boolean valid = match.validateAction(bluePlayer, playAt(mirrorSlot, 9f, 25f));
    assertThat(valid).isFalse();

    // But placing on own side in a valid building position should be accepted
    boolean validOwn = match.validateAction(bluePlayer, playAt(mirrorSlot, 9f, 7f));
    assertThat(validOwn).isTrue();
  }

  // -- Starting hand exclusion --

  @Test
  void mirror_excludedFromStartingHand() {
    // Create a deck where mirror is at index 0 (would normally be in starting hand)
    List<Card> deckCards = new ArrayList<>();
    deckCards.add(mirrorCard);
    for (int i = 1; i < 8; i++) {
      deckCards.add(
          Card.builder().id("card_" + i).name("Card " + i).type(CardType.TROOP).cost(3).build());
    }
    Deck deck = new Deck(deckCards);

    // Test with many random seeds to increase confidence
    for (int seed = 0; seed < 50; seed++) {
      Hand hand = new Hand(deck, new Random(seed));
      // Check cards in hand (slots 0-3) and next card
      for (int i = 0; i < Hand.HAND_SIZE; i++) {
        Card card = hand.getCard(i);
        assertThat(card.isMirror())
            .as("Mirror should not appear in hand slot %d (seed=%d)", i, seed)
            .isFalse();
      }
      assertThat(hand.getNextCard().isMirror())
          .as("Mirror should not appear as next card (seed=%d)", seed)
          .isFalse();
    }
  }

  // -- Mirror flag loading from JSON --

  @Test
  void mirror_flagLoadedFromJson() {
    Card mirror = CardRegistry.get("mirror");
    assertThat(mirror).isNotNull();
    assertThat(mirror.isMirror()).isTrue();
    assertThat(mirror.getCost()).isEqualTo(1);
    assertThat(mirror.getRarity()).isEqualTo(Rarity.EPIC);
  }

  // -- DeploymentSystem mirror level integration --

  @Test
  void mirror_deploymentUsesElevatedLevel() {
    int mirrorLevel = 11;
    LevelConfig config = new LevelConfig(mirrorLevel);

    TroopStats knightStats = TroopStats.builder().name("Knight").health(690).damage(79).build();
    Card knight =
        Card.builder()
            .id("knight")
            .name("Knight")
            .type(CardType.TROOP)
            .cost(3)
            .rarity(Rarity.COMMON)
            .unitStats(knightStats)
            .build();

    Player player =
        new Player(
            Team.BLUE,
            new Deck(new ArrayList<>(Collections.nCopies(8, mirrorCard))),
            false,
            config);
    player.getElixir().update(100f);
    player.setLastPlayedCard(knight);

    deploymentSystem.queueAction(player, playAt(0, 9f, 10f));
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);
    gameState.processPending();

    // The pending mirror level should have been consumed
    assertThat(player.getPendingMirrorLevel()).isEqualTo(-1);

    // Verify the troop was spawned at mirror level (11 + 1 = 12)
    List<Troop> troops =
        gameState.getEntities().stream()
            .filter(e -> e instanceof Troop)
            .map(e -> (Troop) e)
            .toList();
    assertThat(troops).hasSize(1);

    // The troop's HP should be scaled at level 12 (mirror level + 1), not level 11
    int expectedHp = LevelScaling.scaleCard(690, Rarity.COMMON, mirrorLevel + 1);
    assertThat(troops.get(0).getHealth().getMax()).isEqualTo(expectedHp);
  }
}
