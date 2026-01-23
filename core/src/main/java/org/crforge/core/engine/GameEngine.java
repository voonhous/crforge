package org.crforge.core.engine;

import lombok.Getter;
import org.crforge.core.arena.Arena;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.combat.TargetingSystem;
import org.crforge.core.entity.Entity;
import org.crforge.core.entity.Tower;
import org.crforge.core.physics.PhysicsSystem;
import org.crforge.core.player.Team;

/** Main game engine. Coordinates all systems and runs the simulation tick loop. Runs at 30 FPS. */
@Getter
public class GameEngine {

  public static final int TICKS_PER_SECOND = 30;
  public static final float DELTA_TIME = 1.0f / TICKS_PER_SECOND;

  // Standard match duration: 3 minutes (180 seconds)
  public static final int MATCH_DURATION_TICKS = 180 * TICKS_PER_SECOND;
  // Overtime duration: 2 minutes
  public static final int OVERTIME_DURATION_TICKS = 120 * TICKS_PER_SECOND;

  private final GameState gameState;
  private final Arena arena;
  private final TargetingSystem targetingSystem;
  private final CombatSystem combatSystem;
  private final PhysicsSystem physicsSystem;

  private boolean running;
  private boolean overtime;

  public GameEngine() {
    this.arena = Arena.standard();
    this.gameState = new GameState();
    this.targetingSystem = new TargetingSystem();
    this.combatSystem = new CombatSystem(gameState);
    this.physicsSystem = new PhysicsSystem(arena);
    this.running = false;
    this.overtime = false;
  }

  /** Initialize a new match with standard tower setup. */
  public void initMatch() {
    gameState.reset();
    running = true;
    overtime = false;

    // Spawn towers for both teams
    spawnTowers(Team.BLUE);
    spawnTowers(Team.RED);

    // Process initial spawns
    gameState.processPending();
  }

  private void spawnTowers(Team team) {
    // Y positions: BLUE at bottom (0), RED at top (HEIGHT)
    float baseY = (team == Team.BLUE) ? 3f : Arena.HEIGHT - 3f;
    float princessY = (team == Team.BLUE) ? 6f : Arena.HEIGHT - 6f;

    // Crown tower (center)
    Tower crownTower = Tower.createCrownTower(team, Arena.WIDTH / 2f, baseY);
    gameState.spawnEntity(crownTower);

    // Princess towers (left and right)
    Tower leftPrincess = Tower.createPrincessTower(team, 5f, princessY);
    Tower rightPrincess = Tower.createPrincessTower(team, Arena.WIDTH - 5f, princessY);
    gameState.spawnEntity(leftPrincess);
    gameState.spawnEntity(rightPrincess);
  }

  /** Execute a single game tick (1/30 of a second). */
  public void tick() {
    if (!running || gameState.isGameOver()) return;

    // 1. Process pending spawns/removals from previous tick
    gameState.processPending();

    // 2. Update all entities (timers, cooldowns, etc.)
    for (Entity entity : gameState.getAliveEntities()) {
      entity.update(DELTA_TIME);
    }

    // 3. Update targeting
    targetingSystem.updateTargets(gameState.getAliveEntities());

    // 4. Process combat (attacks and projectiles)
    combatSystem.update(DELTA_TIME);

    // 5. Update physics (movement and collisions)
    physicsSystem.update(gameState.getAliveEntities(), DELTA_TIME);

    // 6. Process deaths
    gameState.processDeaths();

    // 7. Check time limit
    checkTimeLimit();

    // 8. Increment frame counter
    gameState.incrementFrame();
  }

  /** Run multiple ticks. */
  public void tick(int count) {
    for (int i = 0; i < count && running && !gameState.isGameOver(); i++) {
      tick();
    }
  }

  /** Run the simulation for a specified number of seconds. */
  public void runSeconds(float seconds) {
    int ticks = (int) (seconds * TICKS_PER_SECOND);
    tick(ticks);
  }

  /** Run the simulation until game over or max ticks reached. */
  public void runUntilEnd(int maxTicks) {
    for (int i = 0; i < maxTicks && running && !gameState.isGameOver(); i++) {
      tick();
    }
  }

  private void checkTimeLimit() {
    int frame = gameState.getFrameCount();

    // Regular time ended
    if (frame >= MATCH_DURATION_TICKS && !overtime) {
      int blueCrowns = gameState.getCrownCount(Team.BLUE);
      int redCrowns = gameState.getCrownCount(Team.RED);

      if (blueCrowns != redCrowns) {
        // Someone has more crowns - they win
        endGame(blueCrowns > redCrowns ? Team.BLUE : Team.RED);
      } else {
        // Tied - go to overtime
        overtime = true;
      }
    }

    // Overtime ended
    if (overtime && frame >= MATCH_DURATION_TICKS + OVERTIME_DURATION_TICKS) {
      int blueCrowns = gameState.getCrownCount(Team.BLUE);
      int redCrowns = gameState.getCrownCount(Team.RED);

      if (blueCrowns != redCrowns) {
        endGame(blueCrowns > redCrowns ? Team.BLUE : Team.RED);
      } else {
        // Compare tower health for tiebreaker
        int blueHealth = getTotalTowerHealth(Team.BLUE);
        int redHealth = getTotalTowerHealth(Team.RED);

        if (blueHealth != redHealth) {
          endGame(blueHealth > redHealth ? Team.BLUE : Team.RED);
        } else {
          // True tie - could go either way, give to blue for consistency
          endGame(null); // Draw
        }
      }
    }
  }

  private int getTotalTowerHealth(Team team) {
    return gameState.getEntitiesOfType(Tower.class).stream()
        .filter(t -> t.getTeam() == team)
        .filter(Entity::isAlive)
        .mapToInt(t -> t.getHealth().getCurrent())
        .sum();
  }

  private void endGame(Team winner) {
    running = false;
    // GameState will be marked game over when crown tower is destroyed
    // or we set it here for time-based endings
  }

  /** Spawn an entity into the game. */
  public void spawn(Entity entity) {
    gameState.spawnEntity(entity);
  }

  /** Get elapsed game time in seconds. */
  public float getGameTimeSeconds() {
    return gameState.getGameTimeSeconds();
  }

  /** Check if the game is still running. */
  public boolean isRunning() {
    return running && !gameState.isGameOver();
  }

  /** Stop the game. */
  public void stop() {
    running = false;
  }

  /** Check if in overtime. */
  public boolean isOvertime() {
    return overtime;
  }
}
