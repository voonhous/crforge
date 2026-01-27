package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpellDeploymentTest {

  private GameEngine engine;
  private Standard1v1Match match;
  private Card spellCard;
  private Card troopCard;

  @BeforeEach
  void setUp() {
    match = new Standard1v1Match();
    engine = new GameEngine();
    engine.setMatch(match);

    spellCard = Card.builder()
        .name("Fireball")
        .type(CardType.SPELL)
        .cost(4)
        .spellDamage(100)
        .spellRadius(2.5f)
        .build();

    troopCard = Card.builder()
        .name("Knight")
        .type(CardType.TROOP)
        .cost(3)
        .build();
  }

  private Player createPlayerWithAll(Card card, Team team) {
    List<Card> cards = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      cards.add(card);
    }
    Player player = new Player(team, new Deck(cards), false);
    player.getElixir().update(100f); // Max elixir
    return player;
  }

  @Test
  void spell_shouldBeDeployableOnEnemySide() {
    // Setup player with ONLY spells so slot 0 is guaranteed to be a spell
    Player bluePlayer = createPlayerWithAll(spellCard, Team.BLUE);
    match.addPlayer(bluePlayer);
    match.addPlayer(createPlayerWithAll(spellCard, Team.RED));
    engine.initMatch();

    // Enemy side (Top half)
    float enemyY = 25.0f;

    // Slot 0 is Fireball (Guaranteed)
    PlayerActionDTO action = PlayerActionDTO.play(0, 10f, enemyY);

    // Should pass validation
    boolean valid = match.validateAction(bluePlayer, action);
    assertThat(valid).as("Spell should be allowed on enemy side").isTrue();

    // Queue and update
    engine.queueAction(bluePlayer, action);
    engine.tick();
    engine.tick();

    // Elixir should be spent
    assertThat(bluePlayer.getElixir().getCurrent()).isLessThan(9.0f);
  }

  @Test
  void troop_shouldNotBeDeployableOnEnemySide() {
    // Setup player with ONLY troops so slot 0 is guaranteed to be a troop
    Player bluePlayer = createPlayerWithAll(troopCard, Team.BLUE);
    match.addPlayer(bluePlayer);
    match.addPlayer(createPlayerWithAll(troopCard, Team.RED));
    engine.initMatch();

    // Enemy side
    float enemyY = 25.0f;

    // Slot 0 is Knight (Guaranteed)
    PlayerActionDTO action = PlayerActionDTO.play(0, 10f, enemyY);

    boolean valid = match.validateAction(bluePlayer, action);
    assertThat(valid).as("Troop should NOT be allowed on enemy side").isFalse();

    // Attempt queue
    engine.queueAction(bluePlayer, action);
    engine.tick();

    // Elixir should not decrease (stays at max 10.0)
    assertThat(bluePlayer.getElixir().getCurrent()).isEqualTo(10.0f);
  }

  @Test
  void spell_shouldBeDeployableOnOwnSide() {
    Player bluePlayer = createPlayerWithAll(spellCard, Team.BLUE);
    match.addPlayer(bluePlayer);
    match.addPlayer(createPlayerWithAll(spellCard, Team.RED));
    engine.initMatch();

    float ownY = 5.0f;
    PlayerActionDTO action = PlayerActionDTO.play(0, 10f, ownY);
    assertThat(match.validateAction(bluePlayer, action)).isTrue();
  }
}
