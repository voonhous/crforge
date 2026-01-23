package org.crforge.core.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
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
      cards.add(Card.builder()
          .name("Card " + i)
          .type(CardType.TROOP)
          .cost(3)
          .build());
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
  void validateAction_blueShouldOnlyPlaceOnBottomHalf() {
    match.addPlayer(bluePlayer);

    // Valid placement (bottom half)
    PlayerActionDTO validAction = PlayerActionDTO.play(0, 9f, 10f);
    assertThat(match.validateAction(bluePlayer, validAction)).isTrue();

    // Invalid placement (top half)
    PlayerActionDTO invalidAction = PlayerActionDTO.play(0, 9f, 25f);
    assertThat(match.validateAction(bluePlayer, invalidAction)).isFalse();
  }

  @Test
  void validateAction_redShouldOnlyPlaceOnTopHalf() {
    match.addPlayer(redPlayer);

    // Valid placement (top half)
    PlayerActionDTO validAction = PlayerActionDTO.play(0, 9f, 25f);
    assertThat(match.validateAction(redPlayer, validAction)).isTrue();

    // Invalid placement (bottom half)
    PlayerActionDTO invalidAction = PlayerActionDTO.play(0, 9f, 5f);
    assertThat(match.validateAction(redPlayer, invalidAction)).isFalse();
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
    assertThat(match.getMatchDurationTicks()).isEqualTo(180 * 30); // 3 minutes
    assertThat(match.getOvertimeDurationTicks()).isEqualTo(120 * 30); // 2 minutes
    assertThat(match.getGameMode()).isEqualTo(GameMode.STANDARD_1V1);
  }
}