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
 * <p>Match timing derives from Standard1v1Match constants: regular time = {@link
 * Standard1v1Match#MATCH_DURATION_TICKS} (180s), overtime = {@link
 * Standard1v1Match#OVERTIME_DURATION_TICKS} (120s). Since checkTimeLimit() runs before
 * incrementFrame() in each tick, the check sees the frame count from the PREVIOUS tick's increment.
 * The first tick that triggers the regular-time-end check is tick MATCH_DURATION_TICKS + 1
 * (checkTimeLimit sees frameCount = MATCH_DURATION_TICKS). Similarly, overtime ends one tick after
 * MATCH_DURATION_TICKS + OVERTIME_DURATION_TICKS.
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
    engine.tick(Standard1v1Match.MATCH_DURATION_TICKS + 1);

    assertThat(engine.isRunning()).isFalse();
    assertThat(match.isEnded()).isTrue();
    assertThat(match.getWinner()).isEqualTo(Team.BLUE);
    assertThat(match.isOvertime()).isFalse();
  }

  @Test
  void regularTime_tiedCrowns_entersOvertime() {
    // No tower kills -> both teams have 0 crowns
    engine.tick(Standard1v1Match.MATCH_DURATION_TICKS + 1);

    assertThat(match.isOvertime()).isTrue();
    assertThat(engine.isRunning()).isTrue();
    assertThat(match.isEnded()).isFalse();
    assertThat(match.getWinner()).isNull();
  }

  @Test
  void overtime_crownTowerDeath_endsImmediately() {
    // Enter overtime first (tied crowns at regular time end)
    engine.tick(Standard1v1Match.MATCH_DURATION_TICKS + 1);
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
    engine.tick(Standard1v1Match.MATCH_DURATION_TICKS + 1);
    assertThat(match.isOvertime()).isTrue();

    // Damage one red princess tower (don't kill it) so red has lower total HP
    List<Tower> redPrincesses = gameState.getPrincessTowers(Team.RED);
    redPrincesses.get(0).getHealth().takeDamage(500);

    // Advance to overtime end (total 9001 ticks, already did 5401)
    engine.tick(Standard1v1Match.OVERTIME_DURATION_TICKS);

    assertThat(engine.isRunning()).isFalse();
    assertThat(match.isEnded()).isTrue();
    assertThat(match.getWinner()).isEqualTo(Team.BLUE);
  }

  @Test
  void overtime_tiedCrowns_equalHP_draws() {
    // Enter overtime (tied crowns)
    engine.tick(Standard1v1Match.MATCH_DURATION_TICKS + 1);
    assertThat(match.isOvertime()).isTrue();

    // No damage to either side -> equal HP -> draw
    engine.tick(Standard1v1Match.OVERTIME_DURATION_TICKS);

    assertThat(engine.isRunning()).isFalse();
    assertThat(match.isEnded()).isTrue();
    assertThat(match.getWinner()).isNull();
    assertThat(match.isDraw()).isTrue();
  }

  @Test
  void tripleElixir_activatesAt4Minutes() {
    // Enter overtime one tick past matchDuration
    engine.tick(Standard1v1Match.MATCH_DURATION_TICKS + 1);
    assertThat(match.isOvertime()).isTrue();
    assertThat(match.getElixirMultiplier()).isEqualTo(2);

    // Tick to the triple-elixir trigger frame (matchDuration + tripleElixirOffset = 4-minute mark)
    int tripleTriggerFrame =
        Standard1v1Match.MATCH_DURATION_TICKS + Standard1v1Match.TRIPLE_ELIXIR_OFFSET_TICKS;
    engine.tick(tripleTriggerFrame - (Standard1v1Match.MATCH_DURATION_TICKS + 1));
    assertThat(gameState.getFrameCount()).isEqualTo(tripleTriggerFrame);
    // Triple elixir hasn't triggered yet because checkTimeLimit sees frameCount from previous tick
    // One more tick for the check to see the trigger frame
    engine.tick(1);
    assertThat(match.getElixirMultiplier()).isEqualTo(3);
  }

  @Test
  void tripleElixir_notActiveBeforeThreshold() {
    // Enter overtime
    engine.tick(Standard1v1Match.MATCH_DURATION_TICKS + 1);
    assertThat(match.isOvertime()).isTrue();
    assertThat(match.getElixirMultiplier()).isEqualTo(2);

    // Tick to one frame BEFORE the triple-elixir trigger frame
    int tripleTriggerFrame =
        Standard1v1Match.MATCH_DURATION_TICKS + Standard1v1Match.TRIPLE_ELIXIR_OFFSET_TICKS;
    engine.tick(tripleTriggerFrame - (Standard1v1Match.MATCH_DURATION_TICKS + 1) - 1);
    assertThat(gameState.getFrameCount()).isEqualTo(tripleTriggerFrame - 1);
    // One more tick: checkTimeLimit sees (tripleTriggerFrame - 1), still < trigger
    engine.tick(1);
    assertThat(match.getElixirMultiplier()).isEqualTo(2);
  }

  @Test
  void doubleElixir_activatesAt2Minutes() {
    // Frame (matchDuration - doubleElixirOffset) = 2:00 mark
    // At this tick: checkTimeLimit sees the previous frame, no trigger yet
    int doubleTriggerFrame =
        Standard1v1Match.MATCH_DURATION_TICKS - Standard1v1Match.DOUBLE_ELIXIR_OFFSET_TICKS;
    engine.tick(doubleTriggerFrame);
    assertThat(gameState.getFrameCount()).isEqualTo(doubleTriggerFrame);
    assertThat(match.getElixirMultiplier()).isEqualTo(1);

    // One more tick: checkTimeLimit now sees the trigger frame, activates double elixir
    engine.tick(1);
    assertThat(match.getElixirMultiplier()).isEqualTo(2);
    assertThat(match.isOvertime()).isFalse();
  }

  @Test
  void doubleElixir_activeBeforeOvertime() {
    // Advance past double elixir (2 min) but before overtime (3 min)
    int doubleTrigger =
        Standard1v1Match.MATCH_DURATION_TICKS - Standard1v1Match.DOUBLE_ELIXIR_OFFSET_TICKS;
    // run to (doubleTrigger + 2) to be safely past the 2x activation threshold
    engine.tick(doubleTrigger + 2);
    assertThat(match.getElixirMultiplier()).isEqualTo(2);
    assertThat(match.isOvertime()).isFalse();

    // Enter overtime -- multiplier stays at 2x
    engine.tick(Standard1v1Match.MATCH_DURATION_TICKS + 1 - (doubleTrigger + 2));
    assertThat(match.isOvertime()).isTrue();
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
