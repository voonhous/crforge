package org.crforge.bridge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An action submitted by the RL agent for one player. Null or handIndex == -1 means no-op (do
 * nothing this step).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StepAction(int handIndex, float x, float y) {
  /** Returns true if this is a no-op action. */
  public boolean isNoop() {
    return handIndex < 0;
  }
}
