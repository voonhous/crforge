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
 * @param maskVersion the rule the legality mask's play tiles follow ({@link Schema#MASK_VERSION},
 *     the latest, by default): {@link Schema#MASK_LANDING_TILES} opens only the tiles a card lands
 *     on unmoved, {@link Schema#MASK_ALL_TILES} every tile of an open hand slot, as before the
 *     landing rule. It is not part of the observation's schema: a model trained under one rule
 *     reads the same observations and actions under the other
 * @param arena the arena both players' avatars name when an episode is written as a replay ({@link
 *     VectorEnv#replayJson}, a fault bundle's replay), a row name of the arenas table; or null for
 *     none, the default, under which writing a fresh episode as a replay is refused, naming this
 *     setting. An episode started on a replay keeps the replay's own arenas. The battle does not
 *     read it
 */
public record EnvConfig(
    int decisionTicks,
    int level,
    int towerLevel,
    int maxTicks,
    String faultBundles,
    int maskVersion,
    String arena) {

  /**
   * A decision every 5 ticks, four a second, at the battle core's default level, no bundles, the
   * latest mask rule, no arena named.
   */
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
    if (arena != null && arena.isBlank()) {
      throw new IllegalArgumentException("an arena is a row name, or null for none");
    }
    if (maskVersion != Schema.MASK_ALL_TILES && maskVersion != Schema.MASK_LANDING_TILES) {
      throw new IllegalArgumentException(
          "a mask version is "
              + Schema.MASK_ALL_TILES
              + " or "
              + Schema.MASK_LANDING_TILES
              + ", got "
              + maskVersion);
    }
  }

  /** A configuration naming no arena. */
  public EnvConfig(
      int decisionTicks,
      int level,
      int towerLevel,
      int maxTicks,
      String faultBundles,
      int maskVersion) {
    this(decisionTicks, level, towerLevel, maxTicks, faultBundles, maskVersion, null);
  }

  /** A configuration with the latest mask rule, naming no arena. */
  public EnvConfig(
      int decisionTicks, int level, int towerLevel, int maxTicks, String faultBundles) {
    this(decisionTicks, level, towerLevel, maxTicks, faultBundles, Schema.MASK_VERSION);
  }

  /** A configuration that writes no fault bundles, with the latest mask rule, naming no arena. */
  public EnvConfig(int decisionTicks, int level, int towerLevel, int maxTicks) {
    this(decisionTicks, level, towerLevel, maxTicks, null);
  }

  /** This configuration with another decision interval. */
  public EnvConfig withDecisionTicks(int ticks) {
    return new EnvConfig(ticks, level, towerLevel, maxTicks, faultBundles, maskVersion, arena);
  }

  /** This configuration with another tick limit. */
  public EnvConfig withMaxTicks(int ticks) {
    return new EnvConfig(decisionTicks, level, towerLevel, ticks, faultBundles, maskVersion, arena);
  }

  /**
   * This configuration with another mask rule.
   *
   * @param version {@link Schema#MASK_LANDING_TILES} or {@link Schema#MASK_ALL_TILES}
   */
  public EnvConfig withMaskVersion(int version) {
    return new EnvConfig(decisionTicks, level, towerLevel, maxTicks, faultBundles, version, arena);
  }

  /**
   * This configuration writing each faulted episode's bundle under a folder.
   *
   * @param folder the folder, made when the first bundle is written; null for no bundles
   */
  public EnvConfig withFaultBundles(String folder) {
    return new EnvConfig(decisionTicks, level, towerLevel, maxTicks, folder, maskVersion, arena);
  }

  /**
   * This configuration naming the arena both players' avatars name in a written replay.
   *
   * @param row a row name of the arenas table, checked against the tables when an environment is
   *     made; null for none
   */
  public EnvConfig withArena(String row) {
    return new EnvConfig(
        decisionTicks, level, towerLevel, maxTicks, faultBundles, maskVersion, row);
  }
}
