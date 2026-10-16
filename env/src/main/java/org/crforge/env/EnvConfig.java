/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import org.crforge.core.battle.unit.Standard1v1Battle;

/**
 * How a Ladder environment plays its battles.
 *
 * @param decisionTicks ticks one decision advances the battle: both seats act, then the battle
 *     steps this many times, or fewer when the match ends; 1 decides every tick
 * @param level the level every card is played at, counted from 1 across all rarities
 * @param towerLevel the level of every tower
 * @param maxTicks the tick at which an episode is cut short with no result, or 0 for none; a Ladder
 *     match ends by itself, so this is a guard
 * @param faultBundles the folder a faulted episode's bundle is written under (its replay and {@code
 *     fault.json}, see {@link FaultBundle}), or null for none, the default. Bundles are local files
 *     of the machine's run and are never committed
 */
public record EnvConfig(
    int decisionTicks, int level, int towerLevel, int maxTicks, String faultBundles) {

  /** A decision every 5 ticks, four a second, at the battle core's default level, no bundles. */
  public static final EnvConfig DEFAULT =
      new EnvConfig(5, Standard1v1Battle.DEFAULT_LEVEL, Standard1v1Battle.DEFAULT_LEVEL, 0, null);

  public EnvConfig {
    if (decisionTicks < 1) {
      throw new IllegalArgumentException("a decision is at least one tick, got " + decisionTicks);
    }
    if (level < 1 || towerLevel < 1) {
      throw new IllegalArgumentException("levels are counted from 1");
    }
    if (maxTicks < 0) {
      throw new IllegalArgumentException("a negative tick limit: " + maxTicks);
    }
    if (faultBundles != null && faultBundles.isBlank()) {
      throw new IllegalArgumentException("a fault bundle folder is a path, or null for none");
    }
  }

  /** A configuration that writes no fault bundles. */
  public EnvConfig(int decisionTicks, int level, int towerLevel, int maxTicks) {
    this(decisionTicks, level, towerLevel, maxTicks, null);
  }

  /** This configuration with another decision interval. */
  public EnvConfig withDecisionTicks(int ticks) {
    return new EnvConfig(ticks, level, towerLevel, maxTicks, faultBundles);
  }

  /** This configuration with another tick limit. */
  public EnvConfig withMaxTicks(int ticks) {
    return new EnvConfig(decisionTicks, level, towerLevel, ticks, faultBundles);
  }

  /**
   * This configuration writing each faulted episode's bundle under a folder.
   *
   * @param folder the folder, made when the first bundle is written; null for no bundles
   */
  public EnvConfig withFaultBundles(String folder) {
    return new EnvConfig(decisionTicks, level, towerLevel, maxTicks, folder);
  }
}
