package org.crforge.core.pathfinding.math;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Behaviour of the shared integer helpers used by grid routing, movement and targeting. */
class FixedMathTest {

  @Nested
  @DisplayName("division")
  class Division {

    @ParameterizedTest(name = "{0} / {1} = {2}")
    @CsvSource({"7, 2, 3", "-7, 2, -3", "7, -2, -3", "-7, -2, 3", "0, 5, 0", "1, 2, 0", "-1, 2, 0"})
    void truncatesTowardZero(int a, int b, int expected) {
      assertThat(FixedMath.div(a, b)).isEqualTo(expected);
      assertThat(FixedMath.divOrZero(a, b)).isEqualTo(expected);
    }

    @Test
    void divThrowsOnAZeroDivisor() {
      assertThatThrownBy(() -> FixedMath.div(5, 0)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void divOrZeroAnswersZeroOnAZeroDivisor() {
      assertThat(FixedMath.divOrZero(5, 0)).isZero();
      assertThat(FixedMath.divOrZero(-5, 0)).isZero();
      assertThat(FixedMath.divOrZero(0, 0)).isZero();
    }

    @ParameterizedTest(name = "{0} % {1} = {2}")
    @CsvSource({"7, 2, 1", "-7, 2, -1", "7, -2, 1", "-7, -2, -1"})
    void remainderTakesTheSignOfTheDividend(int a, int b, int expected) {
      assertThat(FixedMath.mod(a, b)).isEqualTo(expected);
    }

    @Test
    void shiftingIsNotDividing() {
      // The two forms differ for negative operands, which is why call sites must pick explicitly.
      assertThat(-7 >> 1).isEqualTo(-4);
      assertThat(FixedMath.div(-7, 2)).isEqualTo(-3);
    }
  }

  @Nested
  @DisplayName("32-bit wrap")
  class Wrap {

    @Test
    void keepsValuesThatAlreadyFit() {
      assertThat(FixedMath.s32(0L)).isZero();
      assertThat(FixedMath.s32(123456L)).isEqualTo(123456);
      assertThat(FixedMath.s32(-123456L)).isEqualTo(-123456);
      assertThat(FixedMath.s32(Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
      assertThat(FixedMath.s32(Integer.MIN_VALUE)).isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    void discardsTheBitsAboveBit31() {
      assertThat(FixedMath.s32((1L << 32) - 1)).isEqualTo(-1);
      assertThat(FixedMath.s32(1L << 31)).isEqualTo(Integer.MIN_VALUE);
      assertThat(FixedMath.s32(1L << 32)).isZero();
      assertThat(FixedMath.s32((long) Integer.MAX_VALUE + 1)).isEqualTo(Integer.MIN_VALUE);
    }
  }

  @Nested
  @DisplayName("integer square root")
  class Isqrt {

    @ParameterizedTest(name = "isqrt({0}) = {1}")
    @CsvSource({
      "0, 0",
      "1, 1",
      "2, 1",
      "3, 1",
      "4, 2",
      "999999, 999",
      "1000000, 1000",
      "2147395599, 46339",
      "2147395600, 46340",
      "2147483647, 46340"
    })
    void isTheFloorOfTheExactRoot(int n, int expected) {
      assertThat(FixedMath.isqrt(n)).isEqualTo(expected);
    }

    @Test
    void isExactAcrossEveryPerfectSquareAndItsNeighbours() {
      for (int root = 1; root <= 46340; root++) {
        int square = root * root;
        assertThat(FixedMath.isqrt(square)).isEqualTo(root);
        assertThat(FixedMath.isqrt(square - 1)).isEqualTo(root - 1);
        if (root < 46340) {
          assertThat(FixedMath.isqrt(square + 1)).isEqualTo(root);
        }
      }
    }

    @Test
    void rejectsNegativeInput() {
      assertThatThrownBy(() -> FixedMath.isqrt(-1)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("guarded squares and distance")
  class Guarded {

    @ParameterizedTest(name = "guardedSumOfSquares({0}, {1}) = {2}")
    @CsvSource({
      "0, 0, 0",
      "3, 4, 25",
      "-3, -4, 25",
      "46340, 0, 2147395600",
      "46341, 0, 2147483647",
      "0, -46341, 2147483647",
      "46340, 46340, 2147483647",
      "30000, 40000, 2147483647"
    })
    void saturatesInsteadOfOverflowing(int dx, int dy, int expected) {
      assertThat(FixedMath.guardedSumOfSquares(dx, dy)).isEqualTo(expected);
    }

    @Test
    void squaredDistanceSaturatesAtTheSameBoundary() {
      assertThat(FixedMath.squaredDistance(0, 0, 46341, 0)).isEqualTo(Integer.MAX_VALUE);
      assertThat(FixedMath.squaredDistance(0, 0, 3, 4)).isEqualTo(25);
      assertThat(FixedMath.squaredDistance(3500, 10000, 3731, 22854)).isEqualTo(165278677);
    }

    @ParameterizedTest(name = "guardedDistance({0}, {1}) = {2}")
    @CsvSource({
      "0, 0, 0",
      "3, 4, 5",
      "300, 400, 500",
      "-300, -400, 500",
      "46341, 0, 65535",
      "46340, 46340, 65535"
    })
    void distanceIsTheRootOfTheGuardedSum(int dx, int dy, int expected) {
      assertThat(FixedMath.guardedDistance(dx, dy)).isEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("normalize")
  class Normalize {

    @Test
    void scalesTheVectorAndReturnsItsOriginalLength() {
      int[] vector = {300, 400};
      assertThat(FixedMath.normalize(vector, 256)).isEqualTo(500);
      assertThat(vector).containsExactly(153, 204);
    }

    @Test
    void keepsTheSignOfEachComponent() {
      int[] vector = {-300, 400};
      assertThat(FixedMath.normalize(vector, 256)).isEqualTo(500);
      assertThat(vector).containsExactly(-153, 204);
    }

    @Test
    void leavesAZeroVectorUntouched() {
      int[] vector = {0, 0};
      assertThat(FixedMath.normalize(vector, 256)).isZero();
      assertThat(vector).containsExactly(0, 0);
    }

    @Test
    void scalesUpAsWellAsDown() {
      int[] shortVector = {1, 0};
      assertThat(FixedMath.normalize(shortVector, 256)).isEqualTo(1);
      assertThat(shortVector).containsExactly(256, 0);

      int[] vector = {300, 400};
      assertThat(FixedMath.normalize(vector, 1000)).isEqualTo(500);
      assertThat(vector).containsExactly(600, 800);
    }

    @Test
    void rejectsAVectorThatIsNotTwoElements() {
      assertThatThrownBy(() -> FixedMath.normalize(new int[] {1, 2, 3}, 256))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("approximate distance")
  class ApproxDistance {

    @ParameterizedTest(name = "approxDistance({0}, {1}) = {2}")
    @CsvSource({
      "0, 0, 0",
      "3, 4, 5",
      "1000, 0, 1000",
      "0, 1000, 1000",
      "600, 800, 1048",
      "-600, -800, 1048",
      "7, 7, 9"
    })
    void overestimatesDiagonalsSlightly(int dx, int dy, int expected) {
      assertThat(FixedMath.approxDistance(dx, dy)).isEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("trigonometry")
  class Trigonometry {

    @ParameterizedTest(name = "sine1024({0}) = {1}")
    @CsvSource({
      "0, 0",
      "30, 512",
      "45, 724",
      "90, 1024",
      "135, 724",
      "180, 0",
      "225, -724",
      "270, -1024",
      "360, 0",
      "450, 1024"
    })
    void sineIsScaledToAQuarterTurnOf1024(int degrees, int expected) {
      assertThat(FixedMath.sine1024(degrees)).isEqualTo(expected);
    }

    @Test
    void sineReducesNegativeAnglesIntoAFullTurn() {
      assertThat(FixedMath.sine1024(-90)).isEqualTo(-1024);
      assertThat(FixedMath.sine1024(-30)).isEqualTo(FixedMath.sine1024(330));
    }

    @ParameterizedTest(name = "angleOfVector({0}, {1}) = {2}")
    @CsvSource({
      "0, 0, 0",
      "100, 0, 0",
      "0, 100, 90",
      "-100, 0, 180",
      "0, -100, 270",
      "100, 100, 45",
      "-100, 100, 135",
      "-100, -100, 225",
      "100, -100, 315",
      "81, 243, 72",
      "243, 81, 18",
      "-181, 181, 135"
    })
    void headingIsMeasuredCounterClockwiseFromTheXAxis(int x, int y, int expected) {
      assertThat(FixedMath.angleOfVector(x, y)).isEqualTo(expected);
    }
  }
}
