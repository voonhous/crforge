package org.crforge.bridge.observation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.bridge.dto.EntityDTO;
import org.crforge.bridge.dto.ObservationDTO;
import org.crforge.bridge.dto.PlayerObsDTO;
import org.crforge.bridge.dto.TowerDTO;
import org.crforge.core.card.Card;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.LevelConfig;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ObservationBuilderTest {

  private GameEngine engine;
  private Player bluePlayer;
  private Player redPlayer;

  @BeforeEach
  void setUp() {
    engine = new GameEngine();

    List<Card> deck =
        List.of(
            CardRegistry.get("knight"),
            CardRegistry.get("archer"),
            CardRegistry.get("fireball"),
            CardRegistry.get("arrows"),
            CardRegistry.get("giant"),
            CardRegistry.get("musketeer"),
            CardRegistry.get("minions"),
            CardRegistry.get("valkyrie"));

    LevelConfig levelConfig = new LevelConfig(11);
    bluePlayer = new Player(Team.BLUE, new Deck(deck), false, levelConfig);
    redPlayer = new Player(Team.RED, new Deck(deck), true, levelConfig);

    Standard1v1Match match = new Standard1v1Match(11);
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);

    engine.setMatch(match);
    engine.initMatch();
  }

  @Test
  void buildReturnsValidObservation() {
    ObservationDTO obs = ObservationBuilder.build(engine, bluePlayer, redPlayer);

    assertThat(obs.frame()).isZero();
    assertThat(obs.gameTimeSeconds()).isZero();
    assertThat(obs.isOvertime()).isFalse();
  }

  @Test
  void buildReturnsPlayerState() {
    ObservationDTO obs = ObservationBuilder.build(engine, bluePlayer, redPlayer);

    PlayerObsDTO blue = obs.bluePlayer();
    assertThat(blue.elixir()).isEqualTo(5.0f);
    assertThat(blue.hand()).hasSize(4);
    assertThat(blue.nextCard()).isNotNull();
    assertThat(blue.crowns()).isZero();

    PlayerObsDTO red = obs.redPlayer();
    assertThat(red.elixir()).isEqualTo(5.0f);
  }

  @Test
  void buildReturnsTowers() {
    ObservationDTO obs = ObservationBuilder.build(engine, bluePlayer, redPlayer);

    // Each team has 3 towers (1 crown + 2 princess)
    List<TowerDTO> blueTowers = obs.bluePlayer().towers();
    assertThat(blueTowers).hasSize(3);

    // All towers should be alive initially
    assertThat(blueTowers).allMatch(TowerDTO::alive);
    assertThat(obs.redPlayer().towers()).allMatch(TowerDTO::alive);
  }

  @Test
  void buildReturnsEntities() {
    ObservationDTO obs = ObservationBuilder.build(engine, bluePlayer, redPlayer);

    // At start, only towers exist (6 towers total)
    List<EntityDTO> entities = obs.entities();
    assertThat(entities).hasSize(6);

    // All entities should be towers
    assertThat(entities).allMatch(e -> e.entityType().equals("TOWER"));
  }

  @Test
  void buildAfterTicksShowsTimeProgression() {
    engine.tick(30); // 1 second

    ObservationDTO obs = ObservationBuilder.build(engine, bluePlayer, redPlayer);
    assertThat(obs.frame()).isEqualTo(30);
    assertThat(obs.gameTimeSeconds()).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(0.01f));
  }

  @Test
  void handCardsHaveCorrectFields() {
    ObservationDTO obs = ObservationBuilder.build(engine, bluePlayer, redPlayer);

    var hand = obs.bluePlayer().hand();
    for (var card : hand) {
      assertThat(card.id()).isNotNull();
      assertThat(card.name()).isNotNull();
      assertThat(card.type()).isIn("TROOP", "SPELL", "BUILDING");
      assertThat(card.cost()).isGreaterThan(0);
    }
  }
}
