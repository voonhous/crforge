/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import java.util.HashMap;
import java.util.Map;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.UnitData;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.HitPoints;

/**
 * What a seat's player sees of the field's characters and towers: the one rule every part of the
 * observation that names an entity asks, so a change of what the screen shows is a change here
 * alone. The entity rows, the overflow count, the targets a row names, the buildings the deploy
 * mask is built on and the health bar fields all go through it.
 *
 * <p>The screen draws every unit of both sides but one waiting its turn to deploy: a hidden
 * building (a Tesla lowered) is drawn in its lowered pose, an invisible unit (a Royal Ghost, an
 * Archer Queen cloaked, a Suspicious Bush) faint, so a seat sees them, with their flags ({@link
 * #sees}). A ground unit of the enemy travelling underground (tunnelling to its placement, or
 * routing underground where its ability sent it) is drawn as a moving dirt trail alone: the seat
 * sees a row at its current position with its underground flag, nothing of where it will come up,
 * and its row's name only when {@link #TRAIL_NAMES_CARD} says the trail tells the card ({@link
 * #trail}). An enemy character's hit points and shield are shown only where the screen draws its
 * health bar ({@link Bars#shown}). The seat's own entities, every tower, projectiles and area
 * effects are always shown.
 */
final class SeatView {

  /**
   * Whether the trail of a tunnelling enemy unit tells which card it is, so its row names the
   * unit's row and its play names its card from the run. The trails of the two tunnelling cards are
   * different effects, but whether a player tells them apart by eye is not settled, so the trail
   * names nothing until it is: the row's name and the play's card are shown when the unit comes up.
   * The one switch for both.
   */
  static final boolean TRAIL_NAMES_CARD = false;

  private SeatView() {
    // Static rule
  }

  /**
   * Whether a seat's player sees a character or tower: its own, every tower, and every enemy
   * character but one waiting its turn to deploy.
   *
   * @param seat the seat, 0 or 1
   * @param world the character or tower
   */
  static boolean sees(int seat, WorldEntity world) {
    if (world.side() == seat || !(world instanceof CharacterEntity character)) {
      return true;
    }
    return character.getView().getState() != GridEntityState.WAITING_TO_DEPLOY;
  }

  /**
   * Whether a seat's player sees an entity as an underground trail alone: an enemy ground unit
   * travelling underground, tunnelling to its placement or routing underground where its ability
   * sent it (the core's underground test).
   *
   * @param seat the seat, 0 or 1
   * @param world the character or tower, one the seat {@link #sees}
   */
  static boolean trail(int seat, WorldEntity world) {
    return world.side() != seat
        && world instanceof CharacterEntity character
        && character.underground()
        && character.getData().flyingHeight() == 0;
  }

  /**
   * The rows' health bars, as the screen draws them for the other side's player: a character row
   * that names a health bar row and does not set HideHealthbar has one, which the screen shows
   * whenever the unit is on the field and not underground (every health bar row a character names
   * shows on the enemy's screen always); a building row that does not set HideHealthbar has one,
   * which the screen shows while it is damaged or carries a shield, and always for a building with
   * a life time or an elixir production; a row that sets HideHealthbar, or a character row that
   * names no health bar, shows none. The health bar rows themselves (which show always to the
   * enemy) are not among the game tables the environment reads: every health bar row a character
   * names in the tables of the data versions held shows to the enemy always, and the rule takes it
   * so.
   */
  static final class Bars {

    /** A row with no health bar shown to the other side. */
    private static final byte NONE = 1;

    /** A row whose health bar shows to the other side always. */
    private static final byte ALWAYS = 2;

    /** A building row whose health bar shows when damaged, or always with a life or production. */
    private static final byte BUILDING = 3;

    private static final String CHARACTERS = "characters";
    private static final String BUILDINGS = "buildings";

    private final Map<String, Byte> rules = new HashMap<>();

    /**
     * The health bar rules of the tables' character and building rows.
     *
     * @param tables the game tables
     */
    Bars(GameTables tables) {
      for (String table : new String[] {BUILDINGS, CHARACTERS}) {
        if (!tables.tableNames().contains(table)) {
          continue;
        }
        boolean building = table.equals(BUILDINGS);
        for (GameRow row : tables.table(table).rows()) {
          byte rule;
          if (row.bool("HideHealthbar")) {
            rule = NONE;
          } else if (building) {
            rule = BUILDING;
          } else {
            rule = row.string("HealthBar").isEmpty() ? NONE : ALWAYS;
          }
          rules.put(row.name(), rule);
        }
      }
    }

    /**
     * Whether a seat's player is shown an entity's hit points and shield: always for its own and
     * for a tower; for an enemy character, while its health bar shows on the screen and it is not
     * underground.
     *
     * @param seat the seat, 0 or 1
     * @param world the character or tower, one the seat {@link #sees}
     */
    boolean shown(int seat, WorldEntity world) {
      if (world.side() == seat || !(world instanceof CharacterEntity character)) {
        return true;
      }
      if (character.underground()) {
        return false;
      }
      UnitData data = character.getData();
      byte rule = rules.getOrDefault(data.name(), ALWAYS);
      if (rule == NONE) {
        return false;
      }
      if (rule == ALWAYS || data.lifeTimeMs() > 0 || data.manaCollectAmount() > 0) {
        return true;
      }
      HitPoints points = character.getHitPoints();
      return points != null
          && (points.getHitPoints() < points.getMaximum() || points.getShield() > 0);
    }
  }
}
