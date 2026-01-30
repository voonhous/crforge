package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.TroopStats;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BuildingPlacementIntegrationTest {

  private GameEngine engine;
  private Standard1v1Match match;
  private Card buildingCard;
  private Player bluePlayer;

  @BeforeEach
  void setUp() {
    match = new Standard1v1Match();
    engine = new GameEngine();
    engine.setMatch(match);

    // Create a 3x3 building card (Radius 1.5)
    TroopStats buildingStats = TroopStats.builder()
        .name("Big Building")
        .collisionRadius(1.5f)
        .build();

    buildingCard = Card.builder()
        .name("Big Building")
        .type(CardType.BUILDING)
        .cost(5)
        .troop(buildingStats)
        .build();

    List<Card> cards = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      cards.add(buildingCard);
    }
    bluePlayer = new Player(Team.BLUE, new Deck(cards), false);
    bluePlayer.getElixir().update(100f);

    match.addPlayer(bluePlayer);
    match.addPlayer(new Player(Team.RED, new Deck(cards), false));
    engine.initMatch();
  }

  @Test
  void shouldRejectBuildingOverlapWithTower() {
    // Blue Princess Tower Left is at X ~ 3.5 (Indices 2-4 for X).
    // Y range 5-7. Center ~ 6.5.

    // Try to place building center at (3.5, 6.5). Radius 1.5.
    // X range: 2.0 to 5.0. Y range: 5.0 to 8.0.
    // Overlaps tower heavily.

    PlayerActionDTO action = PlayerActionDTO.play(0, 3.5f, 6.5f);

    boolean valid = match.validateAction(bluePlayer, action);

    assertThat(valid).as("Building overlapping tower should be rejected").isFalse();
  }

  @Test
  void shouldRejectBuildingPartiallyOverlapWithTower() {
    // Princess Tower Left ends at X=4 (Indices 2,3,4).
    // Try to place adjacent but overlapping.
    // Center at 5.0. Radius 1.5. X Range: 3.5 to 6.5.
    // Overlaps tile 3 (X=3.5).

    PlayerActionDTO action = PlayerActionDTO.play(0, 5.0f, 6.5f);

    boolean valid = match.validateAction(bluePlayer, action);

    assertThat(valid).as("Building partially overlapping tower should be rejected").isFalse();
  }

  @Test
  void shouldAllowBuildingAdjacentToTower() {
    // Princess Tower Left ends at X=4 (Indices 2,3,4).
    // Valid tile starts at X=5.
    // Building Radius 1.5. Min X should be >= 5.0.
    // So X center - 1.5 >= 5.0 => X center >= 6.5.

    PlayerActionDTO action = PlayerActionDTO.play(0, 6.5f, 6.5f);

    boolean valid = match.validateAction(bluePlayer, action);

    assertThat(valid).as("Building adjacent to tower should be allowed").isTrue();
  }

  @Test
  void shouldRejectBuildingOverlappingRiver() {
    // River is at Y=15, 16. Blue Zone ends at Y=14.
    // Try to place near river.
    // Center Y=14. Radius 1.5. Max Y = 15.5. Overlaps Y=15 (River).

    PlayerActionDTO action = PlayerActionDTO.play(0, 10.5f, 14.0f);

    boolean valid = match.validateAction(bluePlayer, action);

    assertThat(valid).as("Building overlapping river should be rejected").isFalse();
  }

  @Test
  void shouldAllowBuildingNearRiver() {
    // Blue Zone ends at Y=14.
    // Max Y must be < 15.0.
    // Center Y + 1.5 < 15.0 => Center Y < 13.5.

    PlayerActionDTO action = PlayerActionDTO.play(0, 10.5f, 13.4f);

    boolean valid = match.validateAction(bluePlayer, action);

    assertThat(valid).as("Building near river (but inside zone) should be allowed").isTrue();
  }
}
