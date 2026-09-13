package org.crforge.bridge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.crforge.core.player.dto.PlayerActionDTO;

/**
 * An action submitted by the RL agent for one player. Null or handIndex == -1 means no-op (do
 * nothing this step).
 *
 * <p>The external protocol keeps placement coordinates in tiles (floats, e.g. x=9.5, y=10.0).
 * {@link #toPlayerAction()} is the boundary adapter into the engine's integer game units.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StepAction(int handIndex, float x, float y) {
  /** Returns true if this is a no-op action. */
  public boolean isNoop() {
    return handIndex < 0;
  }

  /** Converts this tile-space action into an engine action in integer game units. */
  public PlayerActionDTO toPlayerAction() {
    return PlayerActionDTO.playAtTiles(handIndex, x, y);
  }
}
