package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.TroopStats;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.projectile.Projectile;
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
 * Tests for the deployDelay mechanic: a spawn animation delay (in seconds) that is additive with
 * deployTime. Units with deployDelay remain inactive for deployTime + deployDelay total.
 */
class DeployDelayTest {

  private GameEngine engine;
  private Player bluePlayer;

  // Archer has deployDelay=0.4 in units.json
  private static final Card ARCHER = CardRegistry.get("archer");
  // Knight has no deployDelay (default 0)
  private static final Card KNIGHT = CardRegistry.get("knight");

  // Deploy on blue's side to avoid red tower aggro
  private static final float DEPLOY_X = 9f;
  private static final float DEPLOY_Y = 7f;

  // Placement sync delay (1.0s) before the troop entity appears
  private static final int SYNC_DELAY_TICKS = GameEngine.TICKS_PER_SECOND;

  private static int ticksFor(float seconds) {
    return (int) (seconds * GameEngine.TICKS_PER_SECOND);
  }

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    engine = new GameEngine();

    Deck blueDeck = buildDeckWith(ARCHER);
    Deck redDeck = buildDeckWith(KNIGHT);

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
  void cardRegistryArcherHasDeployDelay() {
    assertThat(ARCHER).as("Archer card should be loaded").isNotNull();
    assertThat(ARCHER.getUnitStats()).as("Archer should have unit stats").isNotNull();
    assertThat(ARCHER.getUnitStats().getDeployDelay())
        .as("Archer deployDelay should be 0.4")
        .isEqualTo(0.4f);
  }

  @Test
  void cardRegistryKnightHasNoDeployDelay() {
    assertThat(KNIGHT).as("Knight card should be loaded").isNotNull();
    assertThat(KNIGHT.getUnitStats()).as("Knight should have unit stats").isNotNull();
    assertThat(KNIGHT.getUnitStats().getDeployDelay())
        .as("Knight deployDelay should be 0")
        .isEqualTo(0f);
  }

  @Test
  void unitWithDeployDelay_totalDeployTimerIncludesDelay() {
    deployCard(0);

    // Tick past sync delay so the troop spawns
    engine.tick(SYNC_DELAY_TICKS + 2);

    Troop archer = findTroopByName("Archer");

    // The initial deployTimer should be deployTime + deployDelay
    // deployTime is 1.0s, deployDelay is 0.4s, total = 1.4s
    // At tick SYNC_DELAY the troop spawns. At 1.0s after spawn it would
    // normally be done deploying. But with deployDelay it needs 1.4s.

    // Tick until 1.0s after spawn (would be done without deployDelay)
    // We already ticked 2, so tick more to reach 1.0s (ticksFor(1.0f)) after spawn
    engine.tick(ticksFor(1.0f) - 2);
    // Troop should still be deploying (needs 1.4s)
    assertThat(archer.isDeploying())
        .as("Archer should still be deploying at 1.0s after spawn (deployDelay not yet elapsed)")
        .isTrue();

    // Tick past the full deploy period (1.4s after spawn)
    engine.tick(ticksFor(0.5f));
    // Troop should be done deploying.
    assertThat(archer.isDeploying())
        .as("Archer should be done deploying after 1.4s (deployTime + deployDelay)")
        .isFalse();
  }

  @Test
  void unitWithoutDeployDelay_deployTimerUnchanged() {
    // Switch hand to knight by using a deck full of knights
    GameEngine knightEngine = new GameEngine();
    Deck knightDeck = buildDeckWith(KNIGHT);
    Deck redDeck = buildDeckWith(KNIGHT);

    Player knightPlayer = new Player(Team.BLUE, knightDeck, false);
    Player redPlayer = new Player(Team.RED, redDeck, false);

    Standard1v1Match match = new Standard1v1Match(1);
    match.addPlayer(knightPlayer);
    match.addPlayer(redPlayer);
    knightEngine.setMatch(match);
    knightEngine.initMatch();
    knightPlayer.getElixir().add(10);

    // Deploy knight
    PlayerActionDTO action = PlayerActionDTO.builder().handIndex(0).x(DEPLOY_X).y(DEPLOY_Y).build();
    knightEngine.queueAction(knightPlayer, action);

    // Tick past sync delay
    knightEngine.tick(SYNC_DELAY_TICKS + 2);

    Troop knight =
        knightEngine.getGameState().getEntitiesOfType(Troop.class).stream()
            .filter(
                t ->
                    "Knight".equals(t.getName())
                        && t.getTeam() == Team.BLUE
                        && !t.getHealth().isDead())
            .findFirst()
            .orElseThrow(() -> new AssertionError("No Knight found"));

    // Knight has deployTime=1.0 and no deployDelay. After 1.0s it should be done.
    // Already 2 ticks into deploy, so tick more to reach 1.0s total.
    knightEngine.tick(ticksFor(1.0f) - 2);
    // Now at 1.0s after spawn
    assertThat(knight.isDeploying())
        .as("Knight should be done deploying at exactly 1.0s (no deployDelay)")
        .isFalse();
  }

  @Test
  void deployDelay_unitStillDeployingDuringDelayPhase() {
    deployCard(0);

    // Tick past sync delay to spawn the archer
    engine.tick(SYNC_DELAY_TICKS + 2);

    Troop archer = findTroopByName("Archer");

    // At 1.0s after spawn, archer should still be deploying due to deployDelay.
    // Without deployDelay, deployTimer=1.0 would be done after 1.0s.
    // With deployDelay=0.4, deployTimer=1.4 so it needs 1.4s.
    engine.tick(ticksFor(1.0f) - 2); // total 1.0s after spawn
    assertThat(archer.isDeploying())
        .as("Archer should still be deploying at 1.0s (in deployDelay phase)")
        .isTrue();
    assertThat(archer.getHealth().getCurrent())
        .as("Archer should not have taken damage while deploying")
        .isEqualTo(archer.getHealth().getMax());

    // After full deploy (1.4s after spawn), archer should be active
    engine.tick(ticksFor(0.467f)); // total ~1.47s after spawn
    assertThat(archer.isDeploying()).as("Archer should be done deploying after 1.4s").isFalse();
  }

  @Test
  void troopStatsDeployDelayField() {
    // Test the TroopStats builder default
    TroopStats noDelay =
        TroopStats.builder()
            .name("TestUnit")
            .movementType(MovementType.GROUND)
            .targetType(TargetType.ALL)
            .build();
    assertThat(noDelay.getDeployDelay()).as("Default deployDelay should be 0").isEqualTo(0f);

    TroopStats withDelay =
        TroopStats.builder()
            .name("TestUnit")
            .movementType(MovementType.GROUND)
            .targetType(TargetType.ALL)
            .deployDelay(0.4f)
            .build();
    assertThat(withDelay.getDeployDelay()).as("Explicit deployDelay should be 0.4").isEqualTo(0.4f);
  }

  // -- Helpers --

  private void deployCard(int handIndex) {
    PlayerActionDTO action =
        PlayerActionDTO.builder().handIndex(handIndex).x(DEPLOY_X).y(DEPLOY_Y).build();
    engine.queueAction(bluePlayer, action);
  }

  private Troop findTroopByName(String name) {
    return engine.getGameState().getEntitiesOfType(Troop.class).stream()
        .filter(
            t -> name.equals(t.getName()) && t.getTeam() == Team.BLUE && !t.getHealth().isDead())
        .findFirst()
        .orElseThrow(() -> new AssertionError("No " + name + " found"));
  }

  private Deck buildDeckWith(Card card) {
    List<Card> cards = new java.util.ArrayList<>();
    for (int i = 0; i < 8; i++) {
      cards.add(card);
    }
    return new Deck(cards);
  }
}
