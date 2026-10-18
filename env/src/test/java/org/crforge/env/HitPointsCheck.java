/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.pathfinding.combat.HitPoints;

/**
 * The hit point fields of a row against the battle core and the health bar its row gives it, read
 * from the tables: a seat is shown its own entities' and every tower's hit points; an enemy
 * character's only while the screen draws its health bar (a character row naming a health bar, a
 * building row, neither setting HideHealthbar; a building's bar while damaged or with a life time
 * or an elixir production), never underground.
 */
final class HitPointsCheck {

  private HitPointsCheck() {}

  /** Whether the tables give an enemy character a health bar shown now. */
  static boolean barShown(GameTables tables, int seat, WorldEntity world) {
    if (world.side() == seat || !(world instanceof CharacterEntity character)) {
      return true;
    }
    if (character.underground()) {
      return false;
    }
    String name = character.getData().name();
    GameRow row = null;
    boolean building = false;
    if (tables.table("characters").has(name)) {
      row = tables.table("characters").row(name);
    } else if (tables.table("buildings").has(name)) {
      row = tables.table("buildings").row(name);
      building = true;
    }
    if (row == null) {
      return true;
    }
    if (row.bool("HideHealthbar")) {
      return false;
    }
    if (!building) {
      return !row.string("HealthBar").isEmpty();
    }
    HitPoints points = character.getHitPoints();
    return row.intValue("LifeTime") > 0
        || row.intValue("ManaCollectAmount") > 0
        || points.getHitPoints() < points.getMaximum()
        || points.getShield() > 0;
  }

  /** Checks a row's hit points, maximum and shield. */
  static void check(GameTables tables, int seat, WorldEntity world, int[] row) {
    HitPoints points = world.getHitPoints();
    assertThat(row[Schema.E_MAX_HP]).isEqualTo(points.getMaximum());
    if (barShown(tables, seat, world)) {
      assertThat(row[Schema.E_HP]).isEqualTo(points.getHitPoints());
      assertThat(row[Schema.E_SHIELD]).isEqualTo(points.getShield());
    } else {
      assertThat(row[Schema.E_HP]).isEqualTo(Schema.UNKNOWN);
      assertThat(row[Schema.E_SHIELD]).isEqualTo(Schema.UNKNOWN);
    }
  }
}
