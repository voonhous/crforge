package org.crforge.core.player.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.crforge.core.util.GameUnits;

/**
 * Represents a command from a player (or agent) to play a card.
 *
 * <p>This is a Data Transfer Object (DTO) for the GameEngine.
 */
@Getter
@Builder
@ToString
public class PlayerActionDTO {

  // Index in hand (0-3)
  private final int handIndex;
  // Arena X coordinate in integer game units (1,000 per tile)
  private final int x;
  // Arena Y coordinate in integer game units (1,000 per tile)
  private final int y;

  /** Basic validation of the action structure (not game rules). */
  public boolean isValid() {
    return handIndex >= 0 && handIndex < 4;
  }

  /** Helper to create an action at game-unit coordinates. */
  public static PlayerActionDTO play(int index, int x, int y) {
    return PlayerActionDTO.builder().handIndex(index).x(x).y(y).build();
  }

  /**
   * Boundary adapter for callers that work in tiles (user input, external agents). Converts to game
   * units with {@link GameUnits#tiles(double)}.
   */
  public static PlayerActionDTO playAtTiles(int index, float tileX, float tileY) {
    return play(index, GameUnits.tiles(tileX), GameUnits.tiles(tileY));
  }
}
