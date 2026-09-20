package org.crforge.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FormationHelperTest {

  /** Expected offsets for every index of a formation, as {x, y} pairs in game units. */
  private static void assertFormation(
      int primary, int secondary, int radius, int width, int angleShift, int[][] expected) {
    assertThat(expected.length).isEqualTo(primary + secondary);
    for (int i = 0; i < expected.length; i++) {
      FormationLayout.Offset offset =
          FormationHelper.offset(i, primary, secondary, radius, width, angleShift);
      assertThat(new int[] {offset.x(), offset.y()}).as("index %d", i).containsExactly(expected[i]);
    }
  }

  @Test
  void sine1024_roundsToNearestAtWholeDegrees() {
    assertThat(FormationHelper.sine1024(0)).isZero();
    assertThat(FormationHelper.sine1024(6)).isEqualTo(107);
    assertThat(FormationHelper.sine1024(24)).isEqualTo(416);
    assertThat(FormationHelper.sine1024(30)).isEqualTo(512);
    assertThat(FormationHelper.sine1024(90)).isEqualTo(1024);
  }

  @Test
  void sine1024_foldsQuadrantsAndNegativeAngles() {
    assertThat(FormationHelper.sine1024(114)).isEqualTo(935); // sin(66)
    assertThat(FormationHelper.sine1024(180)).isZero();
    assertThat(FormationHelper.sine1024(204)).isEqualTo(-416);
    assertThat(FormationHelper.sine1024(270)).isEqualTo(-1024);
    assertThat(FormationHelper.sine1024(-90)).isEqualTo(-1024);
    assertThat(FormationHelper.sine1024(384)).isEqualTo(416); // 384 = 360 + 24
  }

  @Test
  void skeletonArmy_fifteenUnitsWithCollisionRadius500() {
    // Varying-radius layout: indices 0, 7 and 14 land on the center before collision resolution
    assertFormation(
        15,
        0,
        500,
        0,
        0,
        new int[][] {
          {0, 0},
          {-563, 1266},
          {-2061, 1855},
          {-878, 285},
          {-2297, -241},
          {-400, -231},
          {-1087, -1495},
          {0, 0},
          {288, -1357},
          {1630, -2243},
          {800, -462},
          {2297, -241},
          {439, 142},
          {1374, 1236},
          {0, 0}
        });
  }

  @Test
  void skeletonArmy_negativeProductsTruncateTowardZero() {
    // sin(204) * 1387 = -576992; truncation gives -563 where floor division would give -564
    FormationLayout.Offset offset = FormationHelper.offset(1, 15, 0, 500, 0, 0);

    assertThat(offset.x()).isEqualTo(-563);
  }

  @Test
  void smallRings_useCountSpecificRotations() {
    assertFormation(2, 0, 500, 0, 0, new int[][] {{-500, 0}, {500, 0}});
    assertFormation(3, 0, 500, 0, 0, new int[][] {{0, -577}, {499, 288}, {-499, 288}});
    assertFormation(
        4, 0, 600, 0, 0, new int[][] {{-652, 652}, {-652, -652}, {652, -652}, {652, 652}});
    assertFormation(
        5,
        0,
        700,
        0,
        0,
        new int[][] {{0, -1311}, {1246, -404}, {770, 1060}, {-770, 1060}, {-1246, -404}});
    assertFormation(
        6,
        0,
        600,
        0,
        0,
        new int[][] {
          {0, 1341}, {-1161, 670}, {-1161, -670}, {0, -1341}, {1161, -670}, {1161, 670}
        });
  }

  @Test
  void sevenUnits_placeCenterUnitPlusRingOfSix() {
    assertFormation(
        7,
        0,
        500,
        0,
        0,
        new int[][] {
          {0, 0}, {0, 1387}, {-1201, 693}, {-1201, -693}, {0, -1387}, {1201, -693}, {1201, 693}
        });
  }

  @Test
  void angleShift_rotatesSingleTypeRing() {
    assertFormation(
        5,
        0,
        500,
        0,
        90,
        new int[][] {{936, 0}, {288, 890}, {-756, 550}, {-756, -550}, {288, -890}});
  }

  @Test
  void mixedGroups_interleavePrimaryAndSecondaryUnits() {
    assertFormation(
        3,
        3,
        1000,
        0,
        0,
        new int[][] {{999, -577}, {0, -1154}, {-999, -577}, {-999, 577}, {0, 1154}, {999, 577}});
    assertFormation(1, 2, 3000, 0, 0, new int[][] {{0, -1731}, {-1223, 1223}, {1223, 1223}});
    assertFormation(2, 1, 500, 0, 0, new int[][] {{288, -288}, {-288, -288}, {0, 408}});
  }

  @Test
  void nonzeroWidth_replacesRingWithTwoRowLine() {
    assertFormation(
        6,
        0,
        500,
        14000,
        0,
        new int[][] {
          {-7000, -250}, {-4200, 250}, {-1400, -250}, {1400, 250}, {4200, -250}, {7000, 250}
        });
  }

  @Test
  void widthWithSingleSecondary_placesPrimaryAtCenter() {
    assertFormation(1, 1, 3500, -1000, 0, new int[][] {{0, 0}, {-1000, 3500}});
  }

  @Test
  void singleUnit_isAtCenter() {
    assertThat(FormationHelper.offset(0, 1, 0, 500, 0, 0)).isEqualTo(FormationLayout.Offset.ZERO);
  }

  /** Each rejected input names the single value that broke, not a combined catch-all message. */
  @Test
  void invalidIndexOrCounts_areRejected() {
    assertThatThrownBy(() -> FormationHelper.offset(15, 15, 0, 500, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Formation index 15 is out of range for 15 primary and 0 secondary units");
    assertThatThrownBy(() -> FormationHelper.offset(-1, 15, 0, 500, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Formation index must be >= 0, got: -1");
    assertThatThrownBy(() -> FormationHelper.offset(0, 0, 0, 500, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("primaryCount must be >= 1, got: 0");
    assertThatThrownBy(() -> FormationHelper.offset(0, 1, -1, 500, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("secondaryCount must be >= 0, got: -1");
  }

  /** The last valid index of a group is accepted; only one past it is rejected. */
  @Test
  void lastValidIndex_isAccepted() {
    assertThat(FormationHelper.offset(14, 15, 0, 500, 0, 0)).isNotNull();
    assertThat(FormationHelper.offset(3, 2, 2, 500, 0, 0)).isNotNull();
  }
}
