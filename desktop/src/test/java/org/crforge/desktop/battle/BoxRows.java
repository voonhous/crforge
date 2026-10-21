/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.battle;

import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.projectile.ProjectileData;

/**
 * The rows of the configured tables whose areas are boxes, found as the tests run rather than
 * named: a projectile row with a RadiusY above 0 has an impact area that is a box, and one with a
 * ProjectileRadiusY above 0 a flying body that is a box.
 */
public final class BoxRows {

  private BoxRows() {
    // Utility class
  }

  /**
   * The first card of the tables' other spells, in the table's order, that throws a projectile
   * whose impact area is a box and that spawns a projectile whose flying body is a box.
   *
   * @throws IllegalStateException when the tables hold no such card
   */
  public static String boxCard() {
    for (GameRow row : GameData.tables().table("spells_other").rows()) {
      if (!row.has("Projectile") || row.string("Projectile").isEmpty()) {
        continue;
      }
      ProjectileData thrown = GameData.records().projectile(row.string("Projectile"));
      if (thrown.radiusY() < 1 || thrown.spawnProjectile() == null) {
        continue;
      }
      if (GameData.records().projectile(thrown.spawnProjectile()).projectileRadiusY() >= 1) {
        return row.name();
      }
    }
    throw new IllegalStateException("no spell throws a projectile with a box");
  }

  /** The projectile the box card throws. */
  public static ProjectileData thrown(String card) {
    return GameData.records().projectile(GameData.card(card).projectile());
  }

  /** The projectile the box card's thrown projectile spawns, whose flying body is a box. */
  public static ProjectileData rolling(String card) {
    return GameData.records().projectile(thrown(card).spawnProjectile());
  }
}
