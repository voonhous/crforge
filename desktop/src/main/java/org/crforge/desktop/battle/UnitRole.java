/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.battle;

import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.util.GameUnits;

/**
 * What a troop or building does in a fight, as the visualizer's role icons show it: how it attacks,
 * what job it does when it does not attack (spawning units, collecting elixir), whether its hits
 * damage an area, and whether it goes for buildings only.
 *
 * <p>The role is read from the unit's own row, never from its name, so every unit gets one, spawned
 * units and the content of a new data version included. It is presentation only: the battle never
 * reads it.
 *
 * @param attack how the unit attacks
 * @param work the job of a unit that does not attack, drawn in place of an attack icon; {@link
 *     Work#NONE} for every unit that attacks
 * @param splash true when its hits, or its projectiles' impacts, damage an area
 * @param buildingsOnly true for a unit that attacks buildings only
 */
public record UnitRole(Attack attack, Work work, boolean splash, boolean buildingsOnly) {

  /**
   * The reach from which a unit that hits directly is drawn as ranged rather than melee: two tiles.
   * A drawing choice, not a game rule; the battle never reads it.
   */
  public static final int RANGED_REACH = 2 * GameUnits.UNITS_PER_TILE;

  /** No role: a unit that does not attack, and every entity that is not a character. */
  public static final UnitRole NONE = new UnitRole(Attack.NONE, Work.NONE, false, false);

  /** How a unit attacks, each drawn as its own icon. */
  public enum Attack {
    /** It does not attack: no icon. */
    NONE,
    /** It hits directly within reach: a sword. */
    MELEE,
    /** It fires projectiles, or hits directly from {@link #RANGED_REACH} or farther: an arrow. */
    RANGED,
    /** Its hit destroys it: a bomb. */
    KAMIKAZE
  }

  /** The job of a unit that does not attack, each drawn as its own icon. */
  public enum Work {
    /** No job of its own, or it attacks: no work icon. */
    NONE,
    /** It makes units while it lives (its SpawnCharacter, SpawnNumber): three small figures. */
    SPAWNER,
    /** It pays its side elixir while it lives (its ManaCollectAmount): an elixir drop. */
    COLLECTOR
  }

  /** A unit's role from its row. */
  public static UnitRole of(UnitData data) {
    ProjectileData projectile = data.projectile();
    Attack attack;
    if (data.kamikaze()) {
      attack = Attack.KAMIKAZE;
    } else if (projectile != null || (data.damage() > 0 && data.range() >= RANGED_REACH)) {
      attack = Attack.RANGED;
    } else if (data.damage() > 0) {
      attack = Attack.MELEE;
    } else {
      attack = Attack.NONE;
    }
    // A unit that fires several projectiles writes their spread in the area damage radius, so the
    // area of a projectile shooter is its projectile's own.
    boolean splash = projectile != null ? projectile.radius() > 0 : data.areaDamageRadius() > 0;
    Work work = Work.NONE;
    if (attack == Attack.NONE) {
      if (data.spawnCharacter() != null && data.spawnNumber() > 0) {
        work = Work.SPAWNER;
      } else if (data.manaCollectAmount() > 0) {
        work = Work.COLLECTOR;
      }
    }
    return new UnitRole(attack, work, splash, data.targetOnlyBuildings());
  }
}
