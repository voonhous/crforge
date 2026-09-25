package org.crforge.core.battle.deploy;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * How the index-th unit of a card starts: deploying at once, or waiting its turn first.
 *
 * <p>A unit with a deploy time waits when its card staggers its units: every unit of a card whose
 * first unit is a building waits the card's stagger, any later unit of the first group waits its
 * index times the stagger, and a unit of the second group waits the second stagger times its place
 * in that group, counted from one. Every other unit starts deploying at once.
 *
 * @param state the state the unit is created in: waiting to deploy or deploying
 * @param waitMs the wait, in milliseconds, or -1 for none
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the building rule, the first group's index times the stagger taking precedence"
            + " over the second group's, the second group counted from one, and deploying at once"
            + " otherwise.")
public record InitialDelay(int state, int waitMs) {

  /** The state of a unit waiting its turn to deploy. */
  public static final int WAITING = 11;

  /** The state of a deploying unit. */
  public static final int DEPLOYING = 4;

  /**
   * Selects one unit's start.
   *
   * @param index the unit's place in the formation
   * @param primaryCount the first group's size
   * @param deployTimeMs the unit's deploy time
   * @param summonDelayMs the card's stagger
   * @param secondaryDelayMs the card's second stagger
   * @param firstUnitIsBuilding whether the card's first unit is a building
   */
  public static InitialDelay select(
      int index,
      int primaryCount,
      int deployTimeMs,
      int summonDelayMs,
      int secondaryDelayMs,
      boolean firstUnitIsBuilding) {
    if (deployTimeMs < 1) {
      return new InitialDelay(-1, -1);
    }
    if (summonDelayMs >= 1 && firstUnitIsBuilding) {
      return new InitialDelay(WAITING, summonDelayMs);
    }
    if (index != 0 && summonDelayMs >= 1) {
      return new InitialDelay(WAITING, summonDelayMs * index);
    }
    if (secondaryDelayMs >= 1 && index >= primaryCount) {
      return new InitialDelay(WAITING, secondaryDelayMs * (index - primaryCount + 1));
    }
    return new InitialDelay(DEPLOYING, -1);
  }
}
