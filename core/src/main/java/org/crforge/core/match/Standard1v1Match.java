package org.crforge.core.match;

import org.crforge.core.arena.Arena;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.Tower;
import org.crforge.core.player.Team;

/**
 * Standard 1v1 match configuration.
 * <ul>
 *   <li>Arena: 18x32 tiles</li>
 *   <li>Players: 1 per team</li>
 *   <li>Duration: 3 minutes + 2 minutes overtime</li>
 *   <li>Towers: 1 Crown + 2 Princess per team</li>
 * </ul>
 */
public class Standard1v1Match extends Match {

  // Standard match duration: 3 minutes (180 seconds)
  public static final int MATCH_DURATION_TICKS = 180 * GameEngine.TICKS_PER_SECOND;
  // Overtime duration: 2 minutes
  public static final int OVERTIME_DURATION_TICKS = 120 * GameEngine.TICKS_PER_SECOND;

  public Standard1v1Match() {
    super(Arena.standard());
  }

  @Override
  public int getMaxPlayersPerTeam() {
    return 1;
  }

  @Override
  public int getMatchDurationTicks() {
    return MATCH_DURATION_TICKS;
  }

  @Override
  public int getOvertimeDurationTicks() {
    return OVERTIME_DURATION_TICKS;
  }

  @Override
  public GameMode getGameMode() {
    return GameMode.STANDARD_1V1;
  }

  /**
   * Creates the standard tower layout for this match.
   * Call this after adding the match to GameEngine.
   *
   * @param spawnCallback callback to spawn each tower into the game state
   */
  public void createTowers(TowerSpawnCallback spawnCallback) {
    spawnTowersForTeam(Team.BLUE, spawnCallback);
    spawnTowersForTeam(Team.RED, spawnCallback);
  }

  private void spawnTowersForTeam(Team team, TowerSpawnCallback callback) {
    // Y positions: BLUE at bottom (0), RED at top (HEIGHT)
    float baseY = (team == Team.BLUE) ? 3f : Arena.HEIGHT - 3f;
    float princessY = (team == Team.BLUE) ? 6f : Arena.HEIGHT - 6f;

    // Crown tower (center)
    Tower crownTower = Tower.createCrownTower(team, Arena.WIDTH / 2f, baseY);
    callback.spawn(crownTower);

    // Princess towers (left and right)
    Tower leftPrincess = Tower.createPrincessTower(team, 5f, princessY);
    Tower rightPrincess = Tower.createPrincessTower(team, Arena.WIDTH - 5f, princessY);
    callback.spawn(leftPrincess);
    callback.spawn(rightPrincess);
  }

  /**
   * Callback interface for tower spawning.
   */
  @FunctionalInterface
  public interface TowerSpawnCallback {
    void spawn(Tower tower);
  }
}