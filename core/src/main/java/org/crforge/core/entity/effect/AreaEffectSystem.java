package org.crforge.core.entity.effect;

import java.util.List;
import lombok.Setter;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.BuffApplication;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
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
    void spawnUnit(
        float x, float y, Team team, TroopStats stats, Rarity rarity, int level, float deployTime);
  }

  private final GameState gameState;
  private final PullProcessor pullProcessor;
  private final LaserBallHandler laserBallHandler;
  private final TargetedEffectHandler targetedEffectHandler;
  private final OneShotHandler oneShotHandler;
  private final TickingHandler tickingHandler;

  @Setter private SpawnProcessor spawnProcessor;

  public AreaEffectSystem(GameState gameState) {
    this.gameState = gameState;
    AreaEffectContext ctx = new AreaEffectContext(gameState);
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
      pullProcessor.applyPull(effect, deltaTime);
      processEffect(effect, deltaTime);
      if (spawnProcessor != null) {
        spawnProcessor.processSpawn(effect, deltaTime);
      }
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
