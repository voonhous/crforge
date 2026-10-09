package org.crforge.core.battle.match;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import java.util.List;

/**
 * A card played as one of its options, the Merge Maiden's mounted or on-foot maiden: the option is
 * picked from the king's elixir, less the costs its pending plays promised, as the player gives the
 * play, and the play then runs as that option's card, for its cost.
 *
 * <p>The pick goes through the options in their order and takes the first whose trigger the free
 * elixir reaches, else the last. The free elixir is the king's less the promised costs, held to the
 * most there can be and truncated to hundredths, compared in ten-thousandths. A variant that
 * projects its summon adds the option's precast time over the milliseconds an elixir takes, a whole
 * quotient: 0 at 1x and 2x and 1 at 3x, which moves no shipped pick, as the elixir it is added to
 * steps by 100.
 *
 * @param useProjectedTimeSummon whether the precast time is added to the elixir the pick reads
 * @param options the options in their order
 */
public record SpellVariant(boolean useProjectedTimeSummon, List<Option> options) {

  public SpellVariant {
    checkArgument(!options.isEmpty(), "a variant card has an option to be played as");
    options = List.copyOf(options);
  }

  /**
   * One option of a variant card.
   *
   * @param spell the card row the play runs as
   * @param trigger the free elixir that makes it available, in ten-thousandths
   * @param precastPendingTimeMs the precast time a projecting variant adds to the free elixir
   * @param cost the option row's cost, which the play is gated on and spends
   * @param elixirProductionStopTimeMs the option row's production stop; 0 for none
   */
  public record Option(
      String spell,
      int trigger,
      int precastPendingTimeMs,
      int cost,
      int elixirProductionStopTimeMs) {}

  /**
   * The free elixir in hundredths: the elixir held to {@code MAX_MANA * 10000} and truncated, 0 at
   * or below 0. The elixir is the king's less what its pending plays promised, set aside before the
   * cap.
   *
   * @param elixir the king's elixir less the promised costs, in ten-thousandths
   * @param maxMana the published maximum elixir
   */
  public static int freeHundredths(int elixir, int maxMana) {
    if (elixir <= 0) {
      return 0;
    }
    return Math.min(elixir, maxMana * MatchSide.SCALE) / 100;
  }

  /**
   * The option a play is picked as: the first whose trigger the free elixir reaches, else the last.
   *
   * @param elixir the king's elixir less the promised costs, in ten-thousandths
   * @param fullBarMs the milliseconds a full bar takes now
   * @param maxMana the published maximum elixir
   * @return the option's index
   */
  public int pick(int elixir, int fullBarMs, int maxMana) {
    int free = freeHundredths(elixir, maxMana) * 100;
    int msPerElixir = fullBarMs / maxMana;
    for (int i = 0; i < options.size() - 1; i++) {
      Option option = options.get(i);
      int mana = free;
      if (useProjectedTimeSummon && msPerElixir != 0 && option.precastPendingTimeMs() >= 1) {
        mana += option.precastPendingTimeMs() / msPerElixir;
      }
      if (mana >= option.trigger()) {
        return i;
      }
    }
    return options.size() - 1;
  }
}
