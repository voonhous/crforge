/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import java.util.Arrays;
import java.util.Objects;
import org.crforge.core.battle.replay.ReplayScenario;
import org.crforge.core.battle.unit.Standard1v1Battle;

/**
 * One side's setup for a Ladder episode: the tower troop it plays with and the levels of its towers
 * and of its deck cards, as a player's account gives them. A side reset with no setup plays {@link
 * #standard}: the princess towers, every tower at {@link EnvConfig#towerLevel} and every card at
 * {@link EnvConfig#level}.
 *
 * <p>The environment checks a setup against the tables before any episode starts ({@link
 * LadderEnv#checkSetup}), so a setup the game cannot hold is refused rather than played.
 *
 * @param towers the tower selection, a row name of the support cards table that the battle core
 *     builds ({@link ReplayScenario#BUILT_TOWER_SELECTIONS}): the princess towers, the Cannoneer's,
 *     the Dagger Duchess's or the Royal Chef's; its spawn group places the side's towers
 * @param kingLevel the king tower's level, counted from 1: a summoner level of the exp levels table
 * @param towerLevel the level of the two princess slots' towers, counted from 1 across all
 *     rarities, within the levels of the selection's support rarity
 * @param cardLevels the level of each deck card by deck index, {@link Schema#DECK_SIZE} of them,
 *     counted from 1 across all rarities, each within the levels of its card's rarity
 */
public record SideSetup(String towers, int kingLevel, int towerLevel, int[] cardLevels) {

  /** Copies the card levels, so a caller's later change to its array changes no setup. */
  public SideSetup {
    Objects.requireNonNull(towers, "a side setup names its tower selection");
    Objects.requireNonNull(cardLevels, "a side setup gives its card levels");
    cardLevels = cardLevels.clone();
  }

  /**
   * The setup a side reset with none plays: the princess towers, the king and the princess towers
   * at the configuration's tower level, and every deck card at its card level.
   *
   * @param config the environment's configuration
   * @return the setup
   */
  public static SideSetup standard(EnvConfig config) {
    int[] levels = new int[Schema.DECK_SIZE];
    Arrays.fill(levels, config.level());
    return new SideSetup(
        Standard1v1Battle.PRINCESS_TOWERS, config.towerLevel(), config.towerLevel(), levels);
  }

  /** The level of each deck card by deck index, a copy. */
  @Override
  public int[] cardLevels() {
    return cardLevels.clone();
  }

  /** The level of one deck card, without copying the levels. */
  int cardLevel(int deckIndex) {
    return cardLevels[deckIndex];
  }

  /** The tower troop's towers as the battle core builds a side's. */
  Standard1v1Battle.Towers battleTowers(String spawnGroup) {
    return new Standard1v1Battle.Towers(spawnGroup, kingLevel, towerLevel);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof SideSetup that
        && towers.equals(that.towers)
        && kingLevel == that.kingLevel
        && towerLevel == that.towerLevel
        && Arrays.equals(cardLevels, that.cardLevels);
  }

  @Override
  public int hashCode() {
    return Objects.hash(towers, kingLevel, towerLevel, Arrays.hashCode(cardLevels));
  }

  @Override
  public String toString() {
    return "SideSetup[towers="
        + towers
        + ", kingLevel="
        + kingLevel
        + ", towerLevel="
        + towerLevel
        + ", cardLevels="
        + Arrays.toString(cardLevels)
        + "]";
  }
}
