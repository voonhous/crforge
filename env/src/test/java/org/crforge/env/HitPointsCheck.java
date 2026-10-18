/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.pathfinding.combat.HitPoints;

/**
 * The hit point fields of a row against the battle core and the health bar its row gives it, read
 * from the tables. A seat is shown its own entities' and every tower's hit points. An enemy
 * character's are never shown underground; else, for a row that does not set HideHealthbar always
 * (its health bar shows to the enemy always); for a row that sets it and whose starting client
 * actions name no health bar never; for a row that sets it and builds a bar, from the unit's first
 * damage on and for its life, but not while an action of it that hides the bar while rolling runs.
 *
 * <p>The check sees the battle only at decisions, so it holds what those facts settle there: a unit
 * whose hit points are off their maximum, or that was shown before, is shown; an unhurt unit never
 * shown before may have been damaged and healed between two decisions, so either is taken. One
 * check follows one battle's units.
 */
final class HitPointsCheck {

  private final GameTables tables;

  /** The enemy units of rows shown from their first damage on that were shown, by identity. */
  private final Set<WorldEntity> shownOnce = Collections.newSetFromMap(new IdentityHashMap<>());

  /** The action rows that hide the bar while they run: the Barb Log hero's roll. */
  private final Set<String> hideWhileRolling = new HashSet<>();

  HitPointsCheck(GameTables tables) {
    this.tables = tables;
    for (String name : tables.actionNames()) {
      if (tables.action(name).fields().path("HideHealthbarWhileRolling").asBoolean(false)) {
        hideWhileRolling.add(name);
      }
    }
  }

  /** What a seat's player is shown of an entity's hit points now. */
  private enum Bar {
    SHOWN,
    HIDDEN,
    EITHER
  }

  private Bar bar(int seat, WorldEntity world) {
    if (world.side() == seat || !(world instanceof CharacterEntity character)) {
      return Bar.SHOWN;
    }
    if (character.underground()) {
      return Bar.HIDDEN;
    }
    String name = character.getData().name();
    GameRow row = null;
    if (tables.table("characters").has(name)) {
      row = tables.table("characters").row(name);
    } else if (tables.table("buildings").has(name)) {
      row = tables.table("buildings").row(name);
    }
    if (row == null || !row.bool("HideHealthbar")) {
      return Bar.SHOWN;
    }
    JsonNode actions = row.value("OnStartingClientActions");
    if (actions == null || !actions.toString().toLowerCase(Locale.ROOT).contains("healthbar")) {
      return Bar.HIDDEN;
    }
    for (ActionInstance instance : character.actionHolder().running()) {
      if (!instance.isFinished() && hideWhileRolling.contains(instance.getAction().name())) {
        return Bar.HIDDEN;
      }
    }
    HitPoints points = character.getHitPoints();
    if (points.getHitPoints() != points.getMaximum() || shownOnce.contains(world)) {
      return Bar.SHOWN;
    }
    return Bar.EITHER;
  }

  /** Checks a row's hit points, maximum and shield. */
  void check(int seat, WorldEntity world, int[] row) {
    HitPoints points = world.getHitPoints();
    assertThat(row[Schema.E_MAX_HP]).isEqualTo(points.getMaximum());
    Bar bar = bar(seat, world);
    if (bar == Bar.EITHER && row[Schema.E_HP] != Schema.UNKNOWN) {
      bar = Bar.SHOWN;
    }
    if (bar == Bar.SHOWN) {
      assertThat(row[Schema.E_HP]).isEqualTo(points.getHitPoints());
      assertThat(row[Schema.E_SHIELD]).isEqualTo(points.getShield());
      if (world.side() != seat) {
        shownOnce.add(world);
      }
    } else {
      assertThat(row[Schema.E_HP]).isEqualTo(Schema.UNKNOWN);
      assertThat(row[Schema.E_SHIELD]).isEqualTo(Schema.UNKNOWN);
    }
  }
}
