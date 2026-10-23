/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.battle;

import java.util.List;
import org.crforge.core.battle.match.MatchSide;

/**
 * The two decks the visualizer's Ladder battle is played with, by the card row names of the game
 * tables, each with its slot flags by deck index.
 *
 * <p>The blue deck marks an evolution slot and two hero slots, so an evolved form and two hero
 * forms are on show; the red deck is the one the visualizer has always shown and marks no slot, so
 * every red card is played in its basic form. Neither holds the Mirror, a variant card or a
 * champion card, and every card is at {@link BattleSession#LEVEL}. A hero's ability is used from
 * its king's champion slot button ({@link BattleSession#useAbility}).
 */
public final class BattleDecks {

  private BattleDecks() {
    // Constants
  }

  /**
   * The blue side's deck (side 0, the bottom of the arena): an air push, two hero forms, an
   * evolution, a spell that strikes the strongest targets, a wide area spell, a building and a
   * tunnelling troop.
   *
   * <ul>
   *   <li>LavaHound - an air tank that goes for buildings and bursts into pups when it dies
   *   <li>MegaMinion - in a hero slot: an air troop played in its hero form, the hero its king's
   *       champion slot follows
   *   <li>SkeletonArmy - in the evolution slot: a radial formation of many units, evolved once the
   *       side has cycled it enough times
   *   <li>Lightning - a spell that strikes the targets with the most hit points
   *   <li>Minions - a group of air troops supporting the push
   *   <li>Tombstone - in a hero slot: a spawner building played in its hero form, the hero its
   *       king's champion slot follows
   *   <li>Arrows - an area spell over a wide circle
   *   <li>Miner - tunnels underground to anywhere in the arena
   * </ul>
   */
  public static final List<String> BLUE =
      List.of(
          "LavaHound",
          "MegaMinion",
          "SkeletonArmy",
          "Lightning",
          "Minions",
          "Tombstone",
          "Arrows",
          "Miner");

  /**
   * The blue deck's slot flags, by deck index: SkeletonArmy evolves, MegaMinion and Tombstone are
   * heroes.
   */
  private static final int[] BLUE_SLOTS = {
    0, MatchSide.HERO_SLOT, MatchSide.EVOLUTION_SLOT, 0, 0, MatchSide.HERO_SLOT, 0, 0
  };

  /**
   * The red side's deck (side 1, the top of the arena): a jump, a reflect, a dash, shields, a
   * spawner building, a spawner with a death spawn, a radial formation and a mounted rider.
   *
   * <ul>
   *   <li>MegaKnight - jump and landing damage
   *   <li>ElectroGiant - reflects damage onto its attackers
   *   <li>Assassin - the Bandit's dash
   *   <li>SkeletonWarriors - the Guards' shields
   *   <li>Tombstone - a spawner building
   *   <li>DarkWitch - the Night Witch's bats and death spawn
   *   <li>SkeletonArmy - a radial formation, mirrored for the top side
   *   <li>RamRider - charge and a rider attached to its mount
   * </ul>
   */
  public static final List<String> RED =
      List.of(
          "MegaKnight",
          "ElectroGiant",
          "Assassin",
          "SkeletonWarriors",
          "Tombstone",
          "DarkWitch",
          "SkeletonArmy",
          "RamRider");

  /**
   * The blue deck's slot flags, by deck index: {@link MatchSide#EVOLUTION_SLOT}, {@link
   * MatchSide#HERO_SLOT} or neither.
   *
   * @return a new copy of the flags
   */
  public static int[] blueSlots() {
    return BLUE_SLOTS.clone();
  }

  /**
   * The red deck's slot flags, by deck index: none, every red card is played in its basic form.
   *
   * @return a new array of zeros, one for each red card
   */
  public static int[] redSlots() {
    return new int[RED.size()];
  }
}
