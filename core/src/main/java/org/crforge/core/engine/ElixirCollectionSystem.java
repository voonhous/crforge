package org.crforge.core.engine;

import lombok.Setter;
import org.crforge.core.component.ElixirCollectorComponent;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.match.Match;
import org.crforge.core.player.Elixir;
import org.crforge.core.player.Player;

/**
 * System that handles periodic elixir generation for buildings with an {@link
 * ElixirCollectorComponent} (e.g. Elixir Collector).
 *
 * <p>Each tick, the system counts down the collection timer. When the timer fires, elixir is
 * granted to the building's owner. If the owner is at max elixir, the system enters a hold state
 * where the timer pauses until the owner has room. Stunned/frozen buildings do not tick their
 * timer.
 */
public class ElixirCollectionSystem {

  private final GameState gameState;
  @Setter private Match match;

  public ElixirCollectionSystem(GameState gameState) {
    this.gameState = gameState;
  }

  /**
   * Updates all elixir collectors. Called once per tick from GameEngine.
   *
   * @param deltaTime time elapsed since last tick (seconds)
   */
  public void update(float deltaTime) {
    if (match == null) {
      return;
    }

    for (Entity entity : gameState.getAliveEntities()) {
      if (!(entity instanceof Building building)) {
        continue;
      }

      ElixirCollectorComponent collector = building.getElixirCollector();
      if (collector == null) {
        continue;
      }

      // Skip collection during deploy phase
      if (building.isDeploying()) {
        continue;
      }

      // Skip collection while stunned/frozen (consistent with spawner behavior)
      if (building.getMovement() != null && building.getMovement().isMovementDisabled()) {
        continue;
      }

      // If holding elixir (owner was at cap), check if owner can now receive it
      if (collector.isHoldingElixir()) {
        Player owner = findOwner(building);
        if (owner != null && owner.getElixir().getCurrent() < Elixir.MAX_ELIXIR) {
          // Deliver held elixir and restart timer
          owner.getElixir().add(collector.getManaCollectAmount());
          collector.setHoldingElixir(false);
          collector.setCollectionTimer(collector.getManaGenerateTime());
        }
        // Timer stays paused while holding
        continue;
      }

      // Tick the collection timer
      collector.setCollectionTimer(collector.getCollectionTimer() - deltaTime);

      if (collector.getCollectionTimer() <= 0) {
        Player owner = findOwner(building);
        if (owner != null) {
          if (owner.getElixir().getCurrent() >= Elixir.MAX_ELIXIR) {
            // Owner at cap -- enter hold state
            collector.setHoldingElixir(true);
          } else {
            // Grant elixir and reset timer
            owner.getElixir().add(collector.getManaCollectAmount());
            collector.setCollectionTimer(
                collector.getManaGenerateTime() + collector.getCollectionTimer());
          }
        }
      }
    }
  }

  /** Finds the player that owns this building based on team. */
  private Player findOwner(Building building) {
    var players = match.getPlayers(building.getTeam());
    return players.isEmpty() ? null : players.get(0);
  }
}
