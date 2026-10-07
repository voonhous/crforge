package org.crforge.desktop.battle;

/**
 * A bar that an action running on a character shows over it, read from the action's run after a
 * step: the Royal Chef's cooking on its king tower, the Dagger Duchess's charges on her towers.
 *
 * @param kind what the bar shows
 * @param value how much of the bar is filled, in the run's own units; it can run past the maximum
 * @param maximum what a full bar holds, in the same units, above 0
 * @param segments how many equal parts the bar is drawn in, such as one per charge; 0 or 1 for a
 *     bar drawn whole
 */
public record ActionMeter(Kind kind, int value, int maximum, int segments) {

  /** What a bar shows. */
  public enum Kind {
    /** The Royal Chef's cooking toward the next pancake: full when a throw is due. */
    COOKING,
    /**
     * The Dagger Duchess's charges: each landed attack spends one, and they refill while the tower
     * does not attack. One segment per charge, the next filling as the recharge timer runs.
     */
    CHARGES
  }

  /** The share of the bar filled, 0 to 1. */
  public float share() {
    return Math.max(0, Math.min(1, (float) value / maximum));
  }
}
