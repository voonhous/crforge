package org.crforge.core.pathfinding.math;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The two lookup tables reproduce the published samples exactly. */
class TrigTablesTest {

  private static final int[] EXPECTED_SINE = {
    0, 18, 36, 54, 71, 89, 107, 125, 143, 160, 178, 195, 213, 230, 248, 265, 282, 299, 316, 333,
    350, 367, 384, 400, 416, 433, 449, 465, 481, 496, 512, 527, 543, 558, 573, 587, 602, 616, 630,
    644, 658, 672, 685, 698, 711, 724, 737, 749, 761, 773, 784, 796, 807, 818, 828, 839, 849, 859,
    868, 878, 887, 896, 904, 912, 920, 928, 935, 943, 949, 956, 962, 968, 974, 979, 984, 989, 994,
    998, 1002, 1005, 1008, 1011, 1014, 1016, 1018, 1020, 1022, 1023, 1023, 1024, 1024
  };

  private static final int[] EXPECTED_ATAN = {
    0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 8, 9, 9, 10, 10, 11, 11, 11, 12, 12,
    13, 13, 14, 14, 14, 15, 15, 16, 16, 17, 17, 17, 18, 18, 19, 19, 19, 20, 20, 21, 21, 21, 22, 22,
    22, 23, 23, 24, 24, 24, 25, 25, 25, 26, 26, 27, 27, 27, 28, 28, 28, 29, 29, 29, 30, 30, 30, 31,
    31, 31, 32, 32, 32, 33, 33, 33, 34, 34, 34, 35, 35, 35, 35, 36, 36, 36, 37, 37, 37, 37, 38, 38,
    38, 39, 39, 39, 39, 40, 40, 40, 40, 41, 41, 41, 41, 42, 42, 42, 42, 43, 43, 43, 43, 44, 44, 44,
    44, 45, 45, 45
  };

  @Test
  void sineTableMatchesThePublishedSamples() {
    assertThat(EXPECTED_SINE).hasSize(TrigTables.SINE_ENTRIES);
    assertThat(TrigTables.sineTable()).containsExactly(EXPECTED_SINE);
  }

  @Test
  void atanTableMatchesThePublishedSamples() {
    assertThat(EXPECTED_ATAN).hasSize(TrigTables.ATAN_ENTRIES);
    assertThat(TrigTables.atanTable()).containsExactly(EXPECTED_ATAN);
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
