package org.crforge.core.player.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Represents a command from a player (or agent) to play a card.
 * <p>
 * This is a Data Transfer Object (DTO) for the GameEngine.
 */
@Getter
@Builder
@ToString
public class PlayerActionDTO {

  // Index in hand (0-3)
  private final int handIndex;
  // Arena X coordinate (Logic coordinates)
  private final float x;
  // Arena Y coordinate (Logic coordinates)
  private final float y;

  /**
   * Basic validation of the action structure (not game rules).
   */
  public boolean isValid() {
    return handIndex >= 0 && handIndex < 4;
  }

  /**
   * Helper to create an action.
   */
  public static PlayerActionDTO play(int index, float x, float y) {
    return PlayerActionDTO.builder()
        .handIndex(index)
        .x(x)
        .y(y)
        .build();
  }
}
