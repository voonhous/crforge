package org.crforge.desktop.battle;

import java.util.List;

/**
 * The two decks the visualizer's Ladder battle is played with, by the card row names of the game
 * tables.
 *
 * <p>They are the decks the visualizer has always shown, named by the tables' row names, so the
 * same mechanics stay on show. Neither holds the Mirror, a variant card or a champion, and no slot
 * is marked for an evolution or a hero: every card is played in its basic form at {@link
 * BattleSession#LEVEL}.
 */
public final class BattleDecks {

  private BattleDecks() {
    // Constants
  }

  /**
   * The blue side's deck (side 0, the bottom of the arena): charge, hook, a damage ramp, a deploy
   * stun, a live spawner, an area spell and a radial formation.
   *
   * <ul>
   *   <li>DarkPrince - charge and shield
   *   <li>Prince - charge
   *   <li>Fisherman - hook
   *   <li>InfernoDragon - damage that ramps up on one target
   *   <li>ElectroWizard - stun on deploy
   *   <li>Witch - spawns skeletons as she fights
   *   <li>Zap - area spell with a stun
   *   <li>SkeletonArmy - a radial formation of many units
   * </ul>
   */
  public static final List<String> BLUE =
      List.of(
          "DarkPrince",
          "Prince",
          "Fisherman",
          "InfernoDragon",
          "ElectroWizard",
          "Witch",
          "Zap",
          "SkeletonArmy");

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
}
