package org.crforge.bridge.dto;

/**
 * Result of a step action, returned to the RL agent.
 *
 * @param blueActionFailed true if blue submitted an action that was rejected (not enough elixir,
 *     invalid placement)
 * @param redActionFailed true if red submitted an action that was rejected
 */
public record StepResultDTO(
    ObservationDTO observation,
    RewardDTO reward,
    boolean terminated,
    boolean truncated,
    boolean blueActionFailed,
    boolean redActionFailed) {
  /** Backwards-compatible constructor without action failure flags. */
  public StepResultDTO(
      ObservationDTO observation, RewardDTO reward, boolean terminated, boolean truncated) {
    this(observation, reward, terminated, truncated, false, false);
  }
}
