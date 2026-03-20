package org.crforge.core.entity.effect;

import java.util.List;
import lombok.Setter;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.BuffApplication;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.CombatAbilityBridge;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.effect.handler.AreaEffectContext;
import org.crforge.core.entity.effect.handler.LaserBallHandler;
import org.crforge.core.entity.effect.handler.OneShotHandler;
import org.crforge.core.entity.effect.handler.PullProcessor;
import org.crforge.core.entity.effect.handler.SpawnProcessor;
import org.crforge.core.entity.effect.handler.TargetApplicationRouter;
import org.crforge.core.entity.effect.handler.TargetedEffectHandler;
import org.crforge.core.entity.effect.handler.TickingHandler;
import org.crforge.core.player.Team;

/**
 * Orchestrates area effect processing by dispatching to specialized handlers. Each tick, alive
 * effects are routed to the appropriate handler based on their stats, with pull and spawn
 * processing running independently.
 */
public class AreaEffectSystem {

  /** Callback for spawning units (wired to SpawnerSystem::spawnUnit). */
  @FunctionalInterface
  public interface UnitSpawner {
    void spawnUnit(float x, float y, Team team, TroopStats stats, int level, float deployTime);
  }

  private final GameState gameState;
  private final PullProcessor pullProcessor;
  private final LaserBallHandler laserBallHandler;
  private final TargetedEffectHandler targetedEffectHandler;
  private final OneShotHandler oneShotHandler;
  private final TickingHandler tickingHandler;

  @Setter private SpawnProcessor spawnProcessor;

  public AreaEffectSystem(GameState gameState, CombatAbilityBridge abilityBridge) {
    this.gameState = gameState;
    AreaEffectContext ctx = new AreaEffectContext(gameState, abilityBridge);
    this.pullProcessor = new PullProcessor(ctx);
    this.laserBallHandler = new LaserBallHandler(gameState);
    this.targetedEffectHandler = new TargetedEffectHandler(ctx);
    TargetApplicationRouter router = new TargetApplicationRouter(ctx);
    this.oneShotHandler = new OneShotHandler(router);
    this.tickingHandler = new TickingHandler(router);
  }

  public void setUnitSpawner(UnitSpawner unitSpawner) {
    this.spawnProcessor = new SpawnProcessor(unitSpawner);
  }

  /** Update all active area effects. Should be called once per tick. */
  public void update(float deltaTime) {
    List<AreaEffect> effects = gameState.getEntitiesOfType(AreaEffect.class);
    for (AreaEffect effect : effects) {
      if (!effect.isAlive()) {
        handleControlsBuffCleanup(effect);
        continue;
      }
      // Process first so effects get their final tick before lifetime expiry
      pullProcessor.applyPull(effect, deltaTime);
      processEffect(effect, deltaTime);
      if (spawnProcessor != null) {
        spawnProcessor.processSpawn(effect, deltaTime);
      }
      // Lifetime management after processing (CES: logic in system, not in entity update())
      updateLifetime(effect, deltaTime);
      if (!effect.isAlive()) {
        handleControlsBuffCleanup(effect);
      }
    }
  }

  /**
   * Decrements remaining lifetime and marks the effect dead when expired, with keep-alive guards
   * for pending spawns, active DOT, and pending laser scans.
   */
  private void updateLifetime(AreaEffect effect, float deltaTime) {
    effect.setRemainingLifetime(effect.getRemainingLifetime() - deltaTime);
    if (effect.getRemainingLifetime() <= 0) {
      AreaEffectStats stats = effect.getStats();
      // One-shot effects must survive until this system applies them.
      // Without this guard, effects with very short lifeDuration (e.g. Zap 0.001s)
      // would die before being processed.
      // Laser ball effects (damageTiers non-empty) are not one-shot despite hitSpeed=0.
      if (effect.isOneShot() && !effect.isInitialApplied() && stats.getDamageTiers().isEmpty()) {
        return;
      }
      // Keep alive if a single character spawn is pending (e.g. Royal Delivery spawns at 2.05s
      // but lifeDuration is 2.0s). Skip this guard when a spawn sequence is used instead.
      if (stats.getSpawnCharacter() != null
          && !effect.isSpawnTriggered()
          && stats.getSpawnSequence().isEmpty()) {
        return;
      }
      // Keep alive if spawn sequence has remaining entries (e.g. Graveyard)
      if (!stats.getSpawnSequence().isEmpty()
          && effect.getNextSpawnIndex() < stats.getSpawnSequence().size()) {
        return;
      }
      // Keep alive if targeted effect has pending target selections or active DOT ticks
      if (stats.getTargetCount() > 0
          && (effect.getNextTargetSelectionIndex() < stats.getTargetDelays().size()
              || effect.isDotActive())) {
        return;
      }
      // Keep alive if laser ball has pending scans
      if (effect.getTotalLaserScans() > 0
          && effect.getLaserScanCount() < effect.getTotalLaserScans()) {
        return;
      }
      effect.markDead();
    }
  }

  /**
   * Routes the effect to the appropriate handler based on its stats configuration: - damageTiers
   * not empty -> laser ball - targetCount > 0 -> targeted effect (Vines) - oneShot -> one-shot -
   * else -> ticking
   */
  private void processEffect(AreaEffect effect, float deltaTime) {
    if (!effect.getStats().getDamageTiers().isEmpty()) {
      laserBallHandler.process(effect, deltaTime);
    } else if (effect.getStats().getTargetCount() > 0) {
      targetedEffectHandler.process(effect, deltaTime);
    } else if (effect.isOneShot()) {
      oneShotHandler.process(effect, deltaTime);
    } else {
      tickingHandler.process(effect, deltaTime);
    }
  }

  /**
   * When a controlsBuff area effect dies, remove all applied buffs with the matching buff name from
   * all alive entities. This ensures Tornado buffs are cleaned up when the effect expires.
   */
  private void handleControlsBuffCleanup(AreaEffect effect) {
    if (effect.isBuffsCleaned()) {
      return;
    }
    AreaEffectStats stats = effect.getStats();
    if (!stats.isControlsBuff() || stats.getBuffApplications().isEmpty()) {
      return;
    }
    for (BuffApplication buffApp : stats.getBuffApplications()) {
      String buffName = buffApp.buffName();
      for (Entity entity : gameState.getAliveEntities()) {
        entity.getAppliedEffects().removeIf(e -> buffName.equals(e.getBuffName()));
      }
    }
    effect.setBuffsCleaned(true);
  }
}
