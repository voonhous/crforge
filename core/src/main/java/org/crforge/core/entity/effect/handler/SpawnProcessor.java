package org.crforge.core.entity.effect.handler;

import java.util.List;
import org.crforge.core.arena.Arena;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.SpawnSequenceEntry;
import org.crforge.core.card.TroopStats;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.effect.AreaEffectSystem;
import org.crforge.core.player.Team;

/**
 * Handles delayed character spawning for area effects: single delayed spawn (Royal Delivery) and
 * spawn sequences (Graveyard).
 */
public class SpawnProcessor {

  private final AreaEffectSystem.UnitSpawner unitSpawner;

  public SpawnProcessor(AreaEffectSystem.UnitSpawner unitSpawner) {
    this.unitSpawner = unitSpawner;
  }

  public void processSpawn(AreaEffect effect, float deltaTime) {
    AreaEffectStats stats = effect.getStats();

    // Spawn sequence (e.g. Graveyard: 13 skeletons with staggered timing)
    if (!stats.getSpawnSequence().isEmpty()) {
      processSpawnSequence(effect, deltaTime);
      return;
    }

    // Single delayed spawn (e.g. Royal Delivery -> DeliveryRecruit)
    if (stats.getSpawnCharacter() == null || effect.isSpawnTriggered()) {
      return;
    }

    effect.setSpawnDelayAccumulator(effect.getSpawnDelayAccumulator() + deltaTime);
    if (effect.getSpawnDelayAccumulator() >= stats.getSpawnInitialDelay()) {
      int x = effect.getPosition().getX();
      int y = effect.getPosition().getY();
      unitSpawner.spawnUnit(
          x,
          y,
          effect.getTeam(),
          stats.getSpawnCharacter(),
          effect.getLevel(),
          stats.getSpawnDeployTime());
      effect.setSpawnTriggered(true);
    }
  }

  /**
   * Processes a multi-entry spawn sequence (e.g. Graveyard spawns 13 Skeletons at predefined
   * offsets with staggered delays). Positions are mirrored based on arena placement side and team
   * direction.
   */
  private void processSpawnSequence(AreaEffect effect, float deltaTime) {
    AreaEffectStats stats = effect.getStats();
    List<SpawnSequenceEntry> sequence = stats.getSpawnSequence();
    TroopStats spawnChar = stats.getSpawnCharacter();
    if (spawnChar == null || effect.getNextSpawnIndex() >= sequence.size()) {
      return;
    }

    effect.setSpawnDelayAccumulator(effect.getSpawnDelayAccumulator() + deltaTime);
    float elapsed = effect.getSpawnDelayAccumulator();
    int centerX = effect.getPosition().getX();
    int centerY = effect.getPosition().getY();
    // Mirror X on right half of arena (width=18 tiles, midpoint=9,000 game units)
    int xMirror = centerX > Arena.WIDTH_UNITS / 2 ? -1 : 1;
    // BLUE forward = +Y, RED forward = -Y
    int yMirror = effect.getTeam() == Team.BLUE ? 1 : -1;

    while (effect.getNextSpawnIndex() < sequence.size()) {
      SpawnSequenceEntry entry = sequence.get(effect.getNextSpawnIndex());
      if (elapsed < entry.spawnDelay()) {
        break;
      }

      int spawnX = centerX + entry.relativeX() * xMirror;
      int spawnY = centerY + entry.relativeY() * yMirror;

      unitSpawner.spawnUnit(
          spawnX,
          spawnY,
          effect.getTeam(),
          spawnChar,
          effect.getLevel(),
          stats.getSpawnDeployTime());

      effect.setNextSpawnIndex(effect.getNextSpawnIndex() + 1);
    }
  }
}
