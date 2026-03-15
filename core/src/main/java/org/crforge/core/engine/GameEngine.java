package org.crforge.core.engine;

import lombok.Getter;
import org.crforge.core.ability.AbilitySystem;
import org.crforge.core.arena.Arena;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.combat.ProjectileSystem;
import org.crforge.core.combat.TargetingSystem;
import org.crforge.core.effect.StatusEffectSystem;
import org.crforge.core.entity.AttachedUnitSystem;
import org.crforge.core.entity.SpawnerSystem;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.effect.AreaEffectSystem;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.match.Match;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.physics.PhysicsSystem;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;

/** Main game engine. Coordinates all systems and runs the simulation tick loop at 30 FPS. */
@Getter
public class GameEngine {

  public static final int TICKS_PER_SECOND = 30;
  public static final float DELTA_TIME = 1.0f / TICKS_PER_SECOND;

  private final GameState gameState;
  private final TargetingSystem targetingSystem;
  private final CombatSystem combatSystem;
  private final DeploymentSystem deploymentSystem;
  private final StatusEffectSystem statusEffectSystem;
  private final SpawnerSystem spawnerSystem;
  private final AreaEffectSystem areaEffectSystem;
  private final AbilitySystem abilitySystem;
  private final AttachedUnitSystem attachedUnitSystem;

  // Initialized when match is set
  private PhysicsSystem physicsSystem;
  private Match match;

  private boolean running;

  public GameEngine() {
    this.gameState = new GameState();
    this.targetingSystem = new TargetingSystem();

    AoeDamageService aoeDamageService = new AoeDamageService(gameState);
    ProjectileSystem projectileSystem = new ProjectileSystem(gameState, aoeDamageService);
    this.combatSystem = new CombatSystem(gameState, aoeDamageService, projectileSystem);
    this.deploymentSystem = new DeploymentSystem(gameState, aoeDamageService);
    this.spawnerSystem = new SpawnerSystem(gameState, aoeDamageService);

    // One-directional callback wiring (no circular dependencies)
    projectileSystem.setUnitSpawner(spawnerSystem::spawnUnit);
    this.gameState.setDeathHandler(spawnerSystem::onDeath);

    this.statusEffectSystem = new StatusEffectSystem();
    this.areaEffectSystem = new AreaEffectSystem(gameState);
    areaEffectSystem.setUnitSpawner(spawnerSystem::spawnUnit);
    this.abilitySystem = new AbilitySystem(gameState);
    this.attachedUnitSystem = new AttachedUnitSystem(gameState);
    this.running = false;
  }

  /** Sets the match configuration. Must be called before initMatch(). */
  public void setMatch(Match match) {
    this.match = match;
    this.physicsSystem = new PhysicsSystem(match.getArena());
    this.physicsSystem.setGameState(gameState);
    this.spawnerSystem.setMatch(match);
    match.setGameState(this.gameState);
  }

  /** Queue a player action for processing on next tick. */
  public void queueAction(Player player, PlayerActionDTO action) {
    if (match == null) {
      throw new IllegalStateException("Match not set");
    }
    if (match.validateAction(player, action)) {
      deploymentSystem.queueAction(player, action);
    }
  }

  /** Initialize a new match. Requires setMatch() to be called first. */
  public void initMatch() {
    if (match == null) {
      throw new IllegalStateException("Match not set. Call setMatch() first.");
    }

    gameState.reset();
    running = true;

    // Let the match create its tower layout
    if (match instanceof Standard1v1Match std1v1) {
      std1v1.createTowers(gameState::spawnEntity);
    }

    // Process initial spawns (also refreshes alive cache)
    gameState.processPending();
  }

  /** Execute a single game tick (1/30 of a second). */
  public void tick() {
    if (!running || gameState.isGameOver() || (match != null && match.isEnded())) {
      return;
    }

    // 1. Process pending spawns/removals from previous tick (also refreshes alive cache)
    gameState.processPending();

    // 2. Update match (player elixir regen, etc.)
    if (match != null) {
      match.update(DELTA_TIME);
    }

    // 3. Process queued deployments (includes server sync delay)
    deploymentSystem.update(DELTA_TIME);

    // 4. Update Spawners (new CES system)
    spawnerSystem.update(DELTA_TIME);

    // 5. Update status effects (Update durations and calculate multipliers)
    statusEffectSystem.update(gameState, DELTA_TIME);

    // 5.5 Sync attached units (position, death, effect propagation from parent)
    attachedUnitSystem.update(DELTA_TIME);

    // 6. Update all entities (timers, cooldowns, etc.)
    for (Entity entity : gameState.getAliveEntities()) {
      entity.update(DELTA_TIME);
    }

    // 7. Update area effects (damage/buff zones like Zap, Poison, Freeze)
    areaEffectSystem.update(DELTA_TIME);

    // 8. Update targeting
    targetingSystem.updateTargets(gameState.getAliveEntities());

    // 9. Update abilities (charge, variable damage) -- before combat so damage mods apply
    abilitySystem.update(DELTA_TIME);

    // 10. Process combat (attacks and projectiles)
    combatSystem.update(DELTA_TIME);

    // 11. Update physics (movement and collisions)
    if (physicsSystem != null) {
      physicsSystem.update(gameState.getAliveEntities(), DELTA_TIME);
    }

    // 12. Process deaths (death handler registered via gameState.setDeathHandler)
    gameState.processDeaths();

    // 13. Check time limit
    checkTimeLimit();

    // 14. Increment frame counter
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
    if (match == null) {
      return;
    }

    int frame = gameState.getFrameCount();
    int matchDuration = match.getMatchDurationTicks();
    int overtimeDuration = match.getOvertimeDurationTicks();

    // Regular time ended
    if (frame >= matchDuration && !match.isOvertime()) {
      int blueCrowns = gameState.getCrownCount(Team.BLUE);
      int redCrowns = gameState.getCrownCount(Team.RED);

      if (blueCrowns != redCrowns) {
        // Someone has more crowns - they win
        endGame(blueCrowns > redCrowns ? Team.BLUE : Team.RED);
      } else {
        // Tied - go to overtime
        match.enterOvertime();
      }
    }

    // Overtime ended
    if (match.isOvertime() && frame >= matchDuration + overtimeDuration) {
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
          // True tie - draw
          endGame(null);
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
    if (match != null) {
      match.setWinner(winner);
    }
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
    return running && !gameState.isGameOver() && (match == null || !match.isEnded());
  }

  /** Returns the arena for this match. */
  public Arena getArena() {
    return match != null ? match.getArena() : null;
  }

  /** Returns true if the match is in overtime. */
  public boolean isOvertime() {
    return match != null && match.isOvertime();
  }

  /** Stop the game. */
  public void stop() {
    running = false;
  }
}
