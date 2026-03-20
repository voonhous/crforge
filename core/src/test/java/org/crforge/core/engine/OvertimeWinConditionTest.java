package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for overtime, time-based win conditions, and draw detection.
 *
 * <p>Match timing: regular time = 5400 ticks (180s), overtime = 3600 ticks (120s). Since
 * checkTimeLimit() runs before incrementFrame() in each tick, the check sees the frame count from
 * the PREVIOUS tick's increment. The first tick that triggers the regular-time-end check is tick
 * 5401 (checkTimeLimit sees frameCount=5400). Similarly, overtime ends on tick 9001 (sees
 * frameCount=9000).
 */
class OvertimeWinConditionTest {

  private GameEngine engine;
  private GameState gameState;
  private Standard1v1Match match;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    engine = new GameEngine();
    match = new Standard1v1Match();
    engine.setMatch(match);
    engine.initMatch();
    gameState = engine.getGameState();
  }

  /**
   * Kills a princess tower for the given team by dealing massive damage. Calls processDeaths to
   * trigger death handling (king tower activation, tile freeing, etc.).
   */
  private void killPrincessTower(Team team) {
    List<Tower> princesses = gameState.getPrincessTowers(team);
    princesses.get(0).getHealth().takeDamage(100000);
    gameState.processDeaths();
  }

  /**
   * Kills the crown tower for the given team. processDeaths sets gameState.gameOver and
   * gameState.winner via checkWinCondition.
   */
  private void killCrownTower(Team team) {
    Tower crown = gameState.getCrownTower(team);
    crown.getHealth().takeDamage(100000);
    gameState.processDeaths();
  }

  @Test
  void regularTime_higherCrownCount_wins() {
    // Blue destroys one red princess tower -> blue has 1 crown, red has 0
    killPrincessTower(Team.RED);
    assertThat(gameState.getCrownCount(Team.BLUE)).isEqualTo(1);
    assertThat(gameState.getCrownCount(Team.RED)).isEqualTo(0);

    // Advance to regular time end
    engine.tick(5401);

    assertThat(engine.isRunning()).isFalse();
    assertThat(match.isEnded()).isTrue();
    assertThat(match.getWinner()).isEqualTo(Team.BLUE);
    assertThat(match.isOvertime()).isFalse();
  }

  @Test
  void regularTime_tiedCrowns_entersOvertime() {
    // No tower kills -> both teams have 0 crowns
    engine.tick(5401);

    assertThat(match.isOvertime()).isTrue();
    assertThat(engine.isRunning()).isTrue();
    assertThat(match.isEnded()).isFalse();
    assertThat(match.getWinner()).isNull();
  }

  @Test
  void overtime_crownTowerDeath_endsImmediately() {
    // Enter overtime first (tied crowns at regular time end)
    engine.tick(5401);
    assertThat(match.isOvertime()).isTrue();

    // Kill red crown tower during overtime -> immediate game over via gameState
    killCrownTower(Team.RED);

    assertThat(engine.isRunning()).isFalse();
    assertThat(gameState.isGameOver()).isTrue();
    assertThat(gameState.getWinner()).isEqualTo(Team.BLUE);
  }

  @Test
  void overtime_tiedCrowns_higherTowerHP_wins() {
    // Enter overtime
    engine.tick(5401);
    assertThat(match.isOvertime()).isTrue();

    // Damage one red princess tower (don't kill it) so red has lower total HP
    List<Tower> redPrincesses = gameState.getPrincessTowers(Team.RED);
    redPrincesses.get(0).getHealth().takeDamage(500);

    // Advance to overtime end (total 9001 ticks, already did 5401)
    engine.tick(3600);

    assertThat(engine.isRunning()).isFalse();
    assertThat(match.isEnded()).isTrue();
    assertThat(match.getWinner()).isEqualTo(Team.BLUE);
  }

  @Test
  void overtime_tiedCrowns_equalHP_draws() {
    // Enter overtime (tied crowns)
    engine.tick(5401);
    assertThat(match.isOvertime()).isTrue();

    // No damage to either side -> equal HP -> draw
    engine.tick(3600);

    assertThat(engine.isRunning()).isFalse();
    assertThat(match.isEnded()).isTrue();
    assertThat(match.getWinner()).isNull();
    assertThat(match.isDraw()).isTrue();
  }

  @Test
  void tripleElixir_activatesAt4Minutes() {
    // Enter overtime at tick 5401 (frame 5400 = 3 minutes)
    engine.tick(5401);
    assertThat(match.isOvertime()).isTrue();
    assertThat(match.getElixirMultiplier()).isEqualTo(2);

    // Tick to frame 7200 = 180s + 60s = 4-minute mark (need 7200 - 5401 = 1799 more ticks)
    engine.tick(1799);
    assertThat(gameState.getFrameCount()).isEqualTo(7200);
    // Triple elixir hasn't triggered yet because checkTimeLimit sees frameCount from previous tick
    // We need one more tick for the check to see frame 7200
    engine.tick(1);
    assertThat(match.getElixirMultiplier()).isEqualTo(3);
  }

  @Test
  void tripleElixir_notActiveBeforeThreshold() {
    // Enter overtime
    engine.tick(5401);
    assertThat(match.isOvertime()).isTrue();
    assertThat(match.getElixirMultiplier()).isEqualTo(2);

    // Tick to just before the 4-minute mark (frame 7199)
    engine.tick(1798);
    assertThat(gameState.getFrameCount()).isEqualTo(7199);
    // One more tick: checkTimeLimit sees frame 7199, which is < 7200
    engine.tick(1);
    assertThat(match.getElixirMultiplier()).isEqualTo(2);
  }

  @Test
  void crownTowerDeath_endsGameDuringRegularTime() {
    // Advance partway into the match
    engine.tick(1000);
    assertThat(engine.isRunning()).isTrue();

    // Kill red crown tower -> immediate game over
    killCrownTower(Team.RED);

    assertThat(engine.isRunning()).isFalse();
    assertThat(gameState.isGameOver()).isTrue();
    assertThat(gameState.getWinner()).isEqualTo(Team.BLUE);
    assertThat(match.isOvertime()).isFalse();
  }
}
