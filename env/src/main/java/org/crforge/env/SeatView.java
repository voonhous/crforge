/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.data.GameAction;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.CharacterEntity;
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
 * health bar ({@link Bars#shown}): always for most rows, and for a row that sets HideHealthbar from
 * its first damage on. The seat's own entities, every tower, projectiles and area effects are
 * always shown.
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
   * The health bars as the screen draws them for the other side's player, by row, and which units
   * have been damaged since they came on the field.
   *
   * <p>A character or building row that does not set HideHealthbar has the health bar its HealthBar
   * column names, or, naming none, a default one picked by its hit points. Every health bar row
   * those can reach shows on the enemy's screen always, so the enemy sees the hit points and shield
   * always (an unhurt unit's hit points are its maximum). The health bar rows themselves are not
   * among the game tables the environment reads; the rule takes their show-to-the-enemy-always
   * column as set, which holds for every health bar row a character or building can reach in the
   * tables of 16.402.18, 16.402.19 and 16.402.21.
   *
   * <p>A row that sets HideHealthbar (many champion, hero and evolution rows, the Dark Prince, the
   * Guards) has no such bar. Its bar is the one its starting client actions build: a row whose
   * starting client actions name a health bar shows its hit points and shield from the unit's first
   * damage on, and keeps showing them for the unit's life, healed back to full or not, as a player
   * sees on an enemy Dark Prince, Guards or Archer Queen. Damaged means its hit points not at their
   * maximum, or its shield changed while the row has a shield: a hit, a lost shield and a shield
   * that grows all count. While its action that sets HideHealthbarWhileRolling runs (the Barb Log
   * hero's roll) the bar is hidden. A row that sets HideHealthbar and whose starting client actions
   * name no health bar (dummies, ghosts and helper objects) never shows them.
   *
   * <p>The damage is watched at every tick ({@link #watch}), so a unit damaged and healed back to
   * full between two decisions is shown as damaged.
   */
  static final class Bars {

    /** A row with no health bar shown to the other side. */
    private static final byte NONE = 1;

    /** A row whose health bar shows to the other side always. */
    private static final byte ALWAYS = 2;

    /** A row that sets HideHealthbar and builds its own bar: shown from its first damage on. */
    private static final byte DAMAGED = 3;

    private static final String CHARACTERS = "characters";
    private static final String BUILDINGS = "buildings";

    /** The text every starting client action that builds a health bar names. */
    private static final String HEALTH_BAR_ACTION = "healthbar";

    private final Map<String, Byte> rules = new HashMap<>();

    /** The action rows that hide the bar while they run. */
    private final Set<String> hideWhileRunning = new HashSet<>();

    /** The units of rows shown from their first damage on that have been damaged, by battle id. */
    private final Set<Integer> damaged = new HashSet<>();

    /** The shield each unit of such a row had when last watched, by battle id. */
    private final Map<Integer, Integer> shields = new HashMap<>();

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
        for (GameRow row : tables.table(table).rows()) {
          byte rule;
          if (!row.bool("HideHealthbar")) {
            rule = ALWAYS;
          } else {
            rule = buildsHealthBar(row) ? DAMAGED : NONE;
          }
          rules.put(row.name(), rule);
        }
      }
      for (String name : tables.actionNames()) {
        GameAction action = tables.action(name);
        if (action.fields() != null
            && action.fields().path("HideHealthbarWhileRolling").asBoolean(false)) {
          hideWhileRunning.add(name);
        }
      }
    }

    /** Whether a row's starting client actions name a health bar. */
    private static boolean buildsHealthBar(GameRow row) {
      JsonNode actions = row.value("OnStartingClientActions");
      if (actions == null) {
        return false;
      }
      List<JsonNode> names = new ArrayList<>();
      if (actions.isArray()) {
        actions.forEach(names::add);
      } else {
        names.add(actions);
      }
      for (JsonNode name : names) {
        if (name.asText().toLowerCase(Locale.ROOT).contains(HEALTH_BAR_ACTION)) {
          return true;
        }
      }
      return false;
    }

    /** Forgets the last episode's units. */
    void clear() {
      damaged.clear();
      shields.clear();
    }

    /**
     * Watches the field's units after a tick: a unit of a row shown from its first damage on is
     * marked damaged, for its life, once its hit points are not at their maximum or its shield
     * changed while the row has a shield. The first tick a unit is watched only keeps its shield.
     *
     * @param entities the battle's entities
     */
    void watch(List<BattleEntity> entities) {
      for (BattleEntity entity : entities) {
        if (!(entity instanceof CharacterEntity character)
            || rules.getOrDefault(character.getData().name(), ALWAYS) != DAMAGED) {
          continue;
        }
        HitPoints points = character.getHitPoints();
        int id = character.getId();
        if (points == null || damaged.contains(id)) {
          continue;
        }
        Integer last = shields.get(id);
        boolean shieldChanged = false;
        if (last == null) {
          // The first tick it is watched: its shield then is the one compared from.
          shields.put(id, points.getShield());
        } else if (points.getShieldMaximum() >= 1 && last != points.getShield()) {
          shieldChanged = true;
          shields.put(id, points.getShield());
        }
        if (shieldChanged || points.getHitPoints() != points.getMaximum()) {
          damaged.add(id);
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
      byte rule = rules.getOrDefault(character.getData().name(), ALWAYS);
      if (rule == DAMAGED) {
        return damaged.contains(character.getId()) && !rolling(character);
      }
      return rule == ALWAYS;
    }

    /** Whether one of a unit's running actions hides its bar while it runs. */
    private boolean rolling(CharacterEntity character) {
      if (hideWhileRunning.isEmpty()) {
        return false;
      }
      for (ActionInstance instance : character.actionHolder().running()) {
        if (!instance.isFinished() && hideWhileRunning.contains(instance.getAction().name())) {
          return true;
        }
      }
      return false;
    }
  }
}
