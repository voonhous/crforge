package org.crforge.core.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.arena.Arena;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatchTest {

  private Standard1v1Match match;
  private Player bluePlayer;
  private Player redPlayer;

  @BeforeEach
  void setUp() {
    match = new Standard1v1Match();
    bluePlayer = createPlayer(Team.BLUE);
    redPlayer = createPlayer(Team.RED);
  }

  private Player createPlayer(Team team) {
    List<Card> cards = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      cards.add(Card.builder().name("Card " + i).type(CardType.TROOP).cost(3).build());
    }
    return new Player(team, new Deck(cards), false);
  }

  @Test
  void addPlayer_shouldAddPlayerToCorrectTeam() {
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);

    assertThat(match.getPlayers(Team.BLUE)).containsExactly(bluePlayer);
    assertThat(match.getPlayers(Team.RED)).containsExactly(redPlayer);
  }

  @Test
  void addPlayer_shouldThrowWhenTeamFull() {
    match.addPlayer(bluePlayer);

    Player extraBlue = createPlayer(Team.BLUE);

    assertThatThrownBy(() -> match.addPlayer(extraBlue))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("full");
  }

  @Test
  void getAllPlayers_shouldReturnAllPlayers() {
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);

    assertThat(match.getAllPlayers()).containsExactlyInAnyOrder(bluePlayer, redPlayer);
  }

  @Test
  void update_shouldUpdateAllPlayerElixir() {
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);

    float initialBlue = bluePlayer.getElixir().getCurrent();
    float initialRed = redPlayer.getElixir().getCurrent();

    // Update for 1 second
    match.update(1.0f);

    assertThat(bluePlayer.getElixir().getCurrent()).isGreaterThan(initialBlue);
    assertThat(redPlayer.getElixir().getCurrent()).isGreaterThan(initialRed);
  }

  @Test
  void enterOvertime_shouldSetOvertimeOnPlayers() {
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);

    assertThat(match.isOvertime()).isFalse();

    match.enterOvertime();

    assertThat(match.isOvertime()).isTrue();
    // Players' elixir should now use overtime rate (faster regen)
  }

  @Test
  void enterOvertime_shouldOnlyEnterOnce() {
    match.enterOvertime();
    match.enterOvertime(); // Second call should be no-op

    assertThat(match.isOvertime()).isTrue();
  }

  @Test
  void enterOvertime_shouldSetElixirMultiplierTo2() {
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);

    assertThat(match.getElixirMultiplier()).isEqualTo(1);
    match.enterOvertime();
    assertThat(match.getElixirMultiplier()).isEqualTo(2);
    assertThat(bluePlayer.getElixir().getRegenMultiplier()).isEqualTo(2);
    assertThat(redPlayer.getElixir().getRegenMultiplier()).isEqualTo(2);
  }

  @Test
  void enterTripleElixir_shouldSetElixirMultiplierTo3() {
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);

    match.enterOvertime();
    match.enterTripleElixir();

    assertThat(match.getElixirMultiplier()).isEqualTo(3);
    assertThat(bluePlayer.getElixir().getRegenMultiplier()).isEqualTo(3);
    assertThat(redPlayer.getElixir().getRegenMultiplier()).isEqualTo(3);
  }

  @Test
  void enterTripleElixir_idempotent() {
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);

    match.enterOvertime();
    match.enterTripleElixir();
    match.enterTripleElixir(); // Second call should be no-op

    assertThat(match.getElixirMultiplier()).isEqualTo(3);
  }

  @Test
  void validateAction_blueShouldOnlyPlaceOnBottomHalf() {
    match.addPlayer(bluePlayer);

    // Valid placement (bottom half, Y=10)
    PlayerActionDTO validAction = PlayerActionDTO.play(0, 9f, 10f);
    assertThat(match.validateAction(bluePlayer, validAction)).isTrue();

    // Invalid placement (top half, Y=25)
    PlayerActionDTO invalidAction = PlayerActionDTO.play(0, 9f, 25f);
    assertThat(match.validateAction(bluePlayer, invalidAction)).isFalse();
  }

  @Test
  void validateAction_redShouldOnlyPlaceOnTopHalf() {
    match.addPlayer(redPlayer);

    // Valid placement (top half, Y=25)
    PlayerActionDTO validAction = PlayerActionDTO.play(0, 9f, 25f);
    assertThat(match.validateAction(redPlayer, validAction)).isTrue();

    // Invalid placement (bottom half, Y=5)
    PlayerActionDTO invalidAction = PlayerActionDTO.play(0, 9f, 5f);
    assertThat(match.validateAction(redPlayer, invalidAction)).isFalse();
  }

  @Test
  void validateAction_shouldRejectBridgePlacement() {
    match.addPlayer(bluePlayer);

    // Bridge location: X ~ 3.5, Y = 15.5 (River is at Y=16)
    // Arena Left Bridge starts at X=2, width 3.
    // River tiles are Y=15, 16.

    PlayerActionDTO bridgeAction =
        PlayerActionDTO.play(0, Arena.LEFT_BRIDGE_X + 1.0f, Arena.RIVER_Y - 0.5f);

    // Should be rejected for Troops
    assertThat(match.validateAction(bluePlayer, bridgeAction))
        .as("Bridge placement should be rejected for troops")
        .isFalse();
  }

  @Test
  void validateAction_shouldRejectRiverPlacement() {
    match.addPlayer(bluePlayer);

    // River location (not bridge): Center X=9, Y=16
    PlayerActionDTO riverAction = PlayerActionDTO.play(0, 9f, 16f);

    assertThat(match.validateAction(bluePlayer, riverAction))
        .as("River placement should be rejected")
        .isFalse();
  }

  @Test
  void setWinner_shouldEndMatch() {
    assertThat(match.isEnded()).isFalse();

    match.setWinner(Team.BLUE);

    assertThat(match.isEnded()).isTrue();
    assertThat(match.getWinner()).isEqualTo(Team.BLUE);
  }

  @Test
  void standard1v1_shouldHaveCorrectDurations() {
    assertThat(match.getMaxPlayersPerTeam()).isEqualTo(1);
    assertThat(match.getMatchDurationTicks()).isEqualTo(180 * GameEngine.TICKS_PER_SECOND);
    assertThat(match.getOvertimeDurationTicks()).isEqualTo(120 * GameEngine.TICKS_PER_SECOND);
    assertThat(match.getGameMode()).isEqualTo(GameMode.STANDARD_1V1);
  }
}
