/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.math;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two lookup tables, written out as integer literals, match the rounded trigonometric values
 * they are defined as. The values are recomputed here with {@link StrictMath}, whose results are
 * the same on every JVM.
 */
class TrigTablesTest {

  @Test
  @DisplayName("the sine table is round(1024 * sin(d degrees)) for every d in 0..90")
  void sineTableMatchesItsDefinition() {
    int[] expected = new int[TrigTables.SINE_ENTRIES];
    for (int degrees = 0; degrees < expected.length; degrees++) {
      expected[degrees] =
          (int)
              StrictMath.round(
                  TrigTables.SINE_SCALE * StrictMath.sin(StrictMath.toRadians(degrees)));
    }
    assertThat(TrigTables.sineTable()).containsExactly(expected);
  }

  @Test
  @DisplayName("the arc-tangent table is round(atan(k / 128) in degrees) for every k in 0..128")
  void atanTableMatchesItsDefinition() {
    int[] expected = new int[TrigTables.ATAN_ENTRIES];
    for (int ratio = 0; ratio < expected.length; ratio++) {
      expected[ratio] =
          (int)
              StrictMath.round(
                  StrictMath.toDegrees(
                      StrictMath.atan(ratio / (double) TrigTables.ATAN_RATIO_SCALE)));
    }
    assertThat(TrigTables.atanTable()).containsExactly(expected);
  }

  @Test
  @DisplayName("no entry sits within a rounding error of a half, so the definition is unambiguous")
  void noEntryIsARoundingTie() {
    for (int degrees = 0; degrees < TrigTables.SINE_ENTRIES; degrees++) {
      double value = TrigTables.SINE_SCALE * StrictMath.sin(StrictMath.toRadians(degrees));
      assertThat(Math.abs(value - Math.floor(value) - 0.5))
          .as("sine %d", degrees)
          .isGreaterThan(1e-6);
    }
    for (int ratio = 0; ratio < TrigTables.ATAN_ENTRIES; ratio++) {
      double value =
          StrictMath.toDegrees(StrictMath.atan(ratio / (double) TrigTables.ATAN_RATIO_SCALE));
      assertThat(Math.abs(value - Math.floor(value) - 0.5))
          .as("atan %d", ratio)
          .isGreaterThan(1e-6);
    }
  }

  @Test
  void tablesAreCopiesSoCallersCannotCorruptThem() {
    int[] table = TrigTables.sineTable();
    table[0] = 999;
    assertThat(TrigTables.sine(0)).isZero();
  }

  @Test
  void quarterTurnEndpointsAreTheExpectedScale() {
    assertThat(TrigTables.sine(0)).isZero();
    assertThat(TrigTables.sine(90)).isEqualTo(TrigTables.SINE_SCALE);
    assertThat(TrigTables.atan(0)).isZero();
    assertThat(TrigTables.atan(TrigTables.ATAN_RATIO_SCALE)).isEqualTo(45);
  }
}
