/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.pathfinding.combat;

import static org.crforge.core.util.ValidationUtils.checkArgument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * The hit points of one entity: what every damage source lowers and what the alive and removal
 * tests read.
 *
 * <p>An entity carries one of these only when its hit points at its level are positive. It is
 * created at the maximum, with both team pools at the maximum too and no shield. Each damage source
 * may carry a dedupe id; the ids that have already landed on this object are listed here with the
 * tick they landed on, so that one projectile or one area hit lands on an entity once.
 *
 * <p>An entity is alive while its hit points are above zero; an entity without this object is
 * alive. It is removable when it has asked to be removed or is no longer alive.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: creation at the maximum, the alive test, the removal test and the fields the"
            + " damage chain reads, and the lifetime decay's step and carry. Not modelled yet: the subtraction, so nothing lowers the hit"
            + " points; the shield's hit points at the level; the percentage applied to the"
            + " maximum and the two-against-two raise; the sources a reflected attack has"
            + " already hit.")
public final class HitPoints {

  /** Current hit points; zero once the entity is dead. */
  @Getter @Setter private int hitPoints;

  /** The maximum, which is the hit points at the entity's level. */
  @Getter @Setter private int maximum;

  /** Heading of the hit that killed the entity, stored at death. */
  @Getter @Setter private int lastHitHeading;

  /** Hit points per team of an entity both sides may damage, lowered instead of the hit points. */
  private final int[] teamPools = new int[2];

  /** Shield hit points, which take damage before the hit points. */
  @Getter @Setter private int shield;

  /** The shield's maximum. */
  @Getter @Setter private int shieldMaximum;

  /**
   * The lifetime decay's step: hundredths of a hit point each visit takes, set from the maximum and
   * the row's lifetime; 0 for no decay.
   */
  @Getter @Setter private int decayStep;

  /** The hundredths the decay has taken and not yet removed from the hit points. */
  @Getter private int decayCarry;

  /** Dedupe ids of the damage sources that have landed on this object. */
  private final List<Integer> dedupeIds = new ArrayList<>();

  /** The tick each listed dedupe id landed on, in the same order. */
  private final List<Integer> dedupeTicks = new ArrayList<>();

  /**
   * Creates the object at its maximum.
   *
   * @param maximum the hit points at the entity's level, at least 1
   */
  public HitPoints(int maximum) {
    checkArgument(maximum >= 1, () -> "hit points are created only when positive, got " + maximum);
    this.hitPoints = maximum;
    this.maximum = maximum;
    this.teamPools[0] = maximum;
    this.teamPools[1] = maximum;
  }

  /**
   * The decay's step for a maximum and a lifetime: the maximum times 100,000 over the lifetime,
   * then over 20, both divisions truncating toward zero; the product is 32-bit and wraps as the
   * game's does. A lifetime below 1 ms gives 0.
   *
   * @param maximum the maximum hit points
   * @param lifeTimeMs the row's lifetime
   */
  public static int decayStep(int maximum, int lifeTimeMs) {
    if (lifeTimeMs < 1) {
      return 0;
    }
    return maximum * 100_000 / lifeTimeMs / 20;
  }

  /**
   * One step of the lifetime decay: the step joins the carried hundredths, and once they reach 100
   * the whole hundreds leave the hit points. The step that takes the last one leaves the hit points
   * and the carry at 0.
   *
   * @return true when this step took the last hit point
   */
  public boolean decay() {
    decayCarry += decayStep;
    if (decayCarry < 100) {
      return false;
    }
    int hundreds = decayCarry / 100;
    decayCarry -= hundreds * 100;
    hitPoints -= hundreds;
    if (hitPoints > 0) {
      return false;
    }
    hitPoints = 0;
    decayCarry = 0;
    return true;
  }

  /**
   * Ends the lifetime decay, as a data swap onto a row without a lifetime does: no step from now,
   * and the hundredths carried so far dropped.
   */
  public void endDecay() {
    decayStep = 0;
    decayCarry = 0;
  }

  /** True while the hit points are above zero. */
  public boolean alive() {
    return hitPoints > 0;
  }

  /** The alive answer of an entity: true without a hit-points object. */
  public static boolean alive(HitPoints hitPoints) {
    return hitPoints == null || hitPoints.alive();
  }

  /**
   * Whether the holder's cleanup should drop the entity: it has asked to be removed, or it is not
   * alive.
   *
   * @param removalRequested the entity's own removal request
   * @param hitPoints the entity's hit points, or null when it has none
   */
  public static boolean removable(boolean removalRequested, HitPoints hitPoints) {
    return removalRequested || !alive(hitPoints);
  }

  /**
   * The hit points of one team's pool.
   *
   * @param team 0 or 1
   */
  public int teamPool(int team) {
    return teamPools[team];
  }

  public void setTeamPool(int team, int value) {
    teamPools[team] = value;
  }

  /** The dedupe ids listed so far, in the order they landed. */
  public List<Integer> dedupeIds() {
    return Collections.unmodifiableList(dedupeIds);
  }

  /** True when a damage source with this dedupe id has already landed. */
  public boolean isDedupeListed(int dedupeId) {
    return dedupeIds.contains(dedupeId);
  }

  /** The tick a listed dedupe id last landed on. */
  public int dedupeTick(int dedupeId) {
    int index = dedupeIds.indexOf(dedupeId);
    checkArgument(index >= 0, () -> "dedupe id " + dedupeId + " is not listed");
    return dedupeTicks.get(index);
  }

  /** Lists a dedupe id that has just landed, with the tick it landed on. */
  public void listDedupe(int dedupeId, int tick) {
    dedupeIds.add(dedupeId);
    dedupeTicks.add(tick);
  }

  /** Refreshes the tick of a dedupe id that has landed again. */
  public void refreshDedupe(int dedupeId, int tick) {
    int index = dedupeIds.indexOf(dedupeId);
    checkArgument(index >= 0, () -> "dedupe id " + dedupeId + " is not listed");
    dedupeTicks.set(index, tick);
  }
}
