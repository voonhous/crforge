package org.crforge.core.match;

import org.crforge.core.arena.Arena;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.player.Team;

/**
 * Standard 1v1 match configuration.
 *
 * <ul>
 *   <li>Arena: 18x32 tiles
 *   <li>Players: 1 per team
 *   <li>Duration: 3 minutes + 2 minutes overtime
 *   <li>Towers: 1 Crown + 2 Princess per team
 * </ul>
 */
public class Standard1v1Match extends Match {

  // Standard match duration: 3 minutes (180 seconds)
  public static final int MATCH_DURATION_TICKS = 180 * GameEngine.TICKS_PER_SECOND;
  // Overtime duration: 2 minutes
  public static final int OVERTIME_DURATION_TICKS = 120 * GameEngine.TICKS_PER_SECOND;

  private final int towerLevel;

  public Standard1v1Match() {
    this(LevelScaling.DEFAULT_TOWER_LEVEL);
  }

  public Standard1v1Match(int towerLevel) {
    super(Arena.standard());
    this.towerLevel = towerLevel;
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
   * Creates the standard tower layout for this match. Call this after adding the match to
   * GameEngine.
   *
   * @param spawnCallback callback to spawn each tower into the game state
   */
  public void createTowers(TowerSpawnCallback spawnCallback) {
    spawnTowersForTeam(Team.BLUE, spawnCallback);
    spawnTowersForTeam(Team.RED, spawnCallback);
  }

  private void spawnTowersForTeam(Team team, TowerSpawnCallback callback) {
    Arena arena = getArena();

    if (team == Team.BLUE) {
      callback.spawn(
          Tower.createCrownTower(
              team, arena.getBlueCrownTowerX(), arena.getBlueCrownTowerY(), towerLevel));
      callback.spawn(
          Tower.createPrincessTower(
              team,
              arena.getBlueLeftPrincessTowerX(),
              arena.getBlueLeftPrincessTowerY(),
              towerLevel));
      callback.spawn(
          Tower.createPrincessTower(
              team,
              arena.getBlueRightPrincessTowerX(),
              arena.getBlueRightPrincessTowerY(),
              towerLevel));
    } else {
      callback.spawn(
          Tower.createCrownTower(
              team, arena.getRedCrownTowerX(), arena.getRedCrownTowerY(), towerLevel));
      callback.spawn(
          Tower.createPrincessTower(
              team,
              arena.getRedLeftPrincessTowerX(),
              arena.getRedLeftPrincessTowerY(),
              towerLevel));
      callback.spawn(
          Tower.createPrincessTower(
              team,
              arena.getRedRightPrincessTowerX(),
              arena.getRedRightPrincessTowerY(),
              towerLevel));
    }
  }

  /** Callback interface for tower spawning. */
  @FunctionalInterface
  public interface TowerSpawnCallback {
    void spawn(Tower tower);
  }
}
