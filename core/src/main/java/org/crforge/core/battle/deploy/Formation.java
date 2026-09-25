package org.crforge.core.battle.deploy;

import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.math.FixedMath;

/**
 * Where the index-th unit of a card stands relative to the placed point.
 *
 * <p>One unit stands on the point. Two to six stand on a ring, turned by a count-specific angle and
 * the unit's angle shift; seven put one on the point and six on a ring; eight or more spiral out,
 * each at a sixth of the widened radius given by three times its index modulo 7, so every seventh
 * lands on the point. A card with two groups interleaves them around the ring, and a card with a
 * width lays its units in a line instead. For the placing side of team 0 the offset's length along
 * the arena is reversed, and a formation placed in a lane named by the lane sequence is mirrored
 * across the width.
 *
 * <p>All of it is integer arithmetic with the 1024-scaled sine at whole degrees; every division
 * truncates toward zero.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the ring, the centred seven, the spiral of eight and more, the radius widened by"
            + " the count, two interleaved groups, the line of a card with a width, the pair of a"
            + " width with one second unit, the side reversal and the lane mirror.")
public final class Formation {

  private Formation() {
    // Utility class
  }

  /**
   * The offset of one unit.
   *
   * @param index the unit's place in the formation
   * @param primaryCount the first group's size
   * @param radius the radius the caller chose
   * @param width the line's width; 0 for the ring
   * @param sideFlag 1 for a side of team 0
   * @param laneSelector the lane of the placed point
   * @param angleShift degrees the formation is turned by
   * @param secondaryCount the second group's size
   * @param allowReflection whether the lane mirror may apply
   * @param laneSequence whether the lane sequence is on
   * @return {dx, dy}
   */
  public static int[] offset(
      int index,
      int primaryCount,
      int radius,
      int width,
      int sideFlag,
      int laneSelector,
      int angleShift,
      int secondaryCount,
      boolean allowReflection,
      boolean laneSequence) {
    int slots = secondaryCount > 0 ? 2 * Math.max(primaryCount, secondaryCount) : primaryCount;
    boolean specialPair = width != 0 && secondaryCount == 1;
    if (slots == 1) {
      return new int[] {0, 0};
    }
    boolean reflect;
    if (slots == 7) {
      if (index == 0) {
        return new int[] {0, 0};
      }
      index -= 1;
      slots = 6;
      reflect = false;
    } else {
      int reflectedLane = slots == 2 || slots == 3 ? 1 : 2;
      reflect = laneSelector == reflectedLane && laneSequence;
      angleShift +=
          switch (slots) {
            case 2 -> 90;
            case 3 -> 180;
            case 4 -> 45;
            case 5 -> 180;
            default -> 0;
          };
    }
    if (slots >= 3 && width == 0) {
      int sine = FixedMath.sine1024(div(90, primaryCount));
      int denominator = div(sine * 1000, 1024);
      radius = div(radius * 577, denominator);
    }
    if (slots >= 7) {
      int step = (3 * index) % 7;
      radius = div(radius * step, 6);
    }
    if (specialPair) {
      if (index == 0) {
        return new int[] {0, 0};
      }
      return new int[] {reflect ? -width : width, (sideFlag & 1) != 0 ? -radius : radius};
    }
    int xAngle;
    int yAngle;
    int yDivisor;
    if (secondaryCount == 0) {
      int angle = div(360 * index, slots) + angleShift;
      xAngle = angle + 180;
      yAngle = angle + 90;
      yDivisor = 1024;
    } else {
      int secondaryPhase = div(div(360, slots), 2);
      int primaryPhase = secondaryPhase;
      if (primaryCount < secondaryCount) {
        primaryPhase += (180 / secondaryCount) / 2;
      } else if (secondaryCount < primaryCount) {
        secondaryPhase += (180 / primaryCount) / 2;
      }
      int shift = primaryCount == 1 ? 0 : angleShift;
      if (primaryCount == 1 && index == 0) {
        primaryPhase = 90;
      }
      if (primaryCount >= 2 && secondaryCount == 1) {
        if (index < primaryCount) {
          primaryPhase = 90 / primaryCount;
        }
        shift = 0;
      }
      int secondaryAngle = secondaryPhase + shift + div(360 * (index - primaryCount), slots);
      int primaryAngle = primaryPhase + shift + div(360 * index, slots);
      if (index >= primaryCount) {
        xAngle = secondaryAngle + 270;
        yAngle = secondaryAngle + 180;
      } else {
        xAngle = primaryAngle + 90;
        yAngle = primaryAngle;
      }
      yDivisor = -1024;
    }
    int x = div(FixedMath.sine1024(xAngle) * radius, 1024);
    int y = div(FixedMath.sine1024(yAngle) * radius, yDivisor);
    if (width != 0) {
      x = div(index * width, slots - 1) - div(width, 2);
      y = radius * (index % 2) - div(radius, 2);
      if ((sideFlag & 1) != 0) {
        x = -x;
        y = -y;
        reflect = laneSelector == 1;
      }
    } else if ((sideFlag & 1) != 0) {
      y = -y;
    }
    if (reflect && allowReflection) {
      x = -x;
    }
    return new int[] {x, y};
  }

  /** Division truncating toward zero; zero for a zero divisor. */
  private static int div(int a, int b) {
    return b == 0 ? 0 : a / b;
  }
}
