/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The pick of a variant card's option from its king's elixir. */
class SpellVariantTest {

  private static final int MAX_MANA = 10;

  /** The full bar at 1x and at 3x. */
  private static final int FULL_BAR_1X = 28000;

  private static final int FULL_BAR_3X = 9300;

  private static final SpellVariant MAIDEN =
      new SpellVariant(
          true,
          List.of(
              new SpellVariant.Option("MergeMaiden_Mounted", 60000, 1200, 6, 0),
              new SpellVariant.Option("MergeMaiden_Normal", 30000, 1200, 3, 0)));

  @Test
  @DisplayName("the free elixir is read in hundredths, held to the most there can be, 0 below 0")
  void freeHundredths() {
    assertThat(SpellVariant.freeHundredths(60178, MAX_MANA)).isEqualTo(601);
    assertThat(SpellVariant.freeHundredths(59999, MAX_MANA)).isEqualTo(599);
    assertThat(SpellVariant.freeHundredths(120000, MAX_MANA)).isEqualTo(1000);
    assertThat(SpellVariant.freeHundredths(0, MAX_MANA)).isZero();
    assertThat(SpellVariant.freeHundredths(-50, MAX_MANA)).isZero();
  }

  @Test
  @DisplayName(
      "the first option whose trigger the elixir reaches is picked, else the last: the mounted"
          + " maiden from 6.0")
  void theFirstOptionReachedElseTheLast() {
    assertThat(MAIDEN.pick(60000, FULL_BAR_1X, MAX_MANA)).isZero();
    assertThat(MAIDEN.pick(100000, FULL_BAR_1X, MAX_MANA)).isZero();
    assertThat(MAIDEN.pick(59999, FULL_BAR_1X, MAX_MANA)).isEqualTo(1);
    assertThat(MAIDEN.pick(30000, FULL_BAR_1X, MAX_MANA)).isEqualTo(1);
    // Below every trigger the last option is picked all the same.
    assertThat(MAIDEN.pick(10000, FULL_BAR_1X, MAX_MANA)).isEqualTo(1);
    // The projection adds 1 at 3x to an amount whose step is 100: no shipped pick moves.
    assertThat(MAIDEN.pick(59999, FULL_BAR_3X, MAX_MANA)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "the projection adds the precast time over the milliseconds an elixir takes, a whole"
          + " quotient, only for a variant that projects")
  void theProjection() {
    // A trigger one above 5.99, which only the projection's 1 at 3x reaches.
    SpellVariant.Option odd = new SpellVariant.Option("MergeMaiden_Mounted", 59901, 1200, 6, 0);
    SpellVariant.Option last = new SpellVariant.Option("MergeMaiden_Normal", 30000, 1200, 3, 0);
    SpellVariant projects = new SpellVariant(true, List.of(odd, last));
    SpellVariant plain = new SpellVariant(false, List.of(odd, last));
    // 1200 / 930 is 1 at 3x; 1200 / 2800 is 0 at 1x.
    assertThat(projects.pick(59900, FULL_BAR_3X, MAX_MANA)).isZero();
    assertThat(projects.pick(59900, FULL_BAR_1X, MAX_MANA)).isEqualTo(1);
    assertThat(plain.pick(59900, FULL_BAR_3X, MAX_MANA)).isEqualTo(1);
  }
}
