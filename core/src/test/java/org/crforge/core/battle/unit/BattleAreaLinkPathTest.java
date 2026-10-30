/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An area effect whose row sets LinkToInstigatorLife ends as its parent leaves the battle. The
 * battle core keeps a parent only on the paths that hand one (an action's spawn, an ability, ...);
 * a unit's death, its direct hit and a projectile's impact make their area effect with none, where
 * the game makes it with the dying unit, the hitting unit or the projectile as its parent. A linked
 * row made on one of those paths would never end with what made it, so it is refused, naming the
 * path. A placement, which stands for a spell's cast, makes its area effect with no parent, as the
 * game's cast does, and runs.
 */
class BattleAreaLinkPathTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String AREA = "Test_linked_area";

  /**
   * The configured tables with a long-lived linked area, and the given edit of one more table's
   * rows.
   */
  private static GameTables tables(Path folder, String table, Consumer<ObjectNode> edit)
      throws IOException {
    GameData.altered(
        folder,
        "area_effect_objects",
        rows -> {
          ObjectNode row = rows.putObject(AREA);
          row.put("index", rows.size());
          row.put("class", "LogicAreaEffectObjectData");
          ObjectNode columns = row.putObject("columns");
          columns.put("LifeDuration", 99999);
          columns.put("Rarity", "Common");
          columns.put("LinkToInstigatorLife", true);
        });
    GameData.alterLoaded(folder, table, edit);
    return GameTables.load(folder);
  }

  /** Steps the battle the given number of ticks. */
  private static void step(Standard1v1Battle battle, int ticks) {
    for (int tick = 0; tick < ticks; tick++) {
      battle.getBattle().step();
    }
  }

  /** A deployed unit of a row, of the configured level. */
  private static CharacterEntity deploy(Standard1v1Battle battle, String row, int side, int y) {
    return battle.deploy(0, battle.getWorld().getRecords().unit(row), LEVEL, side, 3500, y);
  }

  @Test
  @DisplayName("a linked area a unit's death makes is refused, naming the death path")
  void deathAreaRefused(@TempDir Path folder) throws IOException {
    GameTables tables =
        tables(
            folder,
            "characters",
            rows -> GameData.columns(rows, "Knight").put("DeathAreaEffect", AREA));
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity knight = deploy(battle, "Knight", 0, 10000);
    step(battle, 2);
    battle.getWorld().getHits().kill(knight, null);

    assertThatThrownBy(() -> step(battle, 4))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(AREA)
        .hasMessageContaining("LinkToInstigatorLife")
        .hasMessageContaining("death");
  }

  @Test
  @DisplayName("a linked area a unit's direct hit makes is refused, naming the on-hit path")
  void onHitAreaRefused(@TempDir Path folder) throws IOException {
    GameTables tables =
        tables(
            folder,
            "characters",
            rows -> GameData.columns(rows, "Knight").put("AreaEffectOnHit", AREA));
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    deploy(battle, "Knight", 0, 10000);
    deploy(battle, "Knight", 1, 11000);

    assertThatThrownBy(() -> step(battle, 200))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(AREA)
        .hasMessageContaining("LinkToInstigatorLife")
        .hasMessageContaining("area_effect_on_hit");
  }

  @Test
  @DisplayName("a linked area a projectile's impact makes is refused, naming the projectile path")
  void projectileAreaRefused(@TempDir Path folder) throws IOException {
    // The Archer's own projectile row, read from the tables, made to drop the area.
    String arrow = GameData.tables().table("characters").row("Archer").string("Projectile");
    GameTables tables =
        tables(
            folder,
            "projectiles",
            rows -> GameData.columns(rows, arrow).put("SpawnAreaEffectObject", AREA));
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    deploy(battle, "Archer", 0, 10000);
    deploy(battle, "Knight", 1, 14000);

    assertThatThrownBy(() -> step(battle, 300))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(AREA)
        .hasMessageContaining("LinkToInstigatorLife")
        .hasMessageContaining("projectile");
  }

  @Test
  @DisplayName("a linked area a placement makes, as a spell's cast does, runs with no parent")
  void placedAreaRuns(@TempDir Path folder) throws IOException {
    GameTables tables = tables(folder, "characters", rows -> {});
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    battle.placeAreaEffect(1, AREA, LEVEL, 0, 3500, 10000, "linked");
    step(battle, 10);

    assertThat(battle.getWorld().getHolder().entities())
        .anyMatch(
            entity ->
                entity instanceof AreaEffectEntity area && area.getData().name().equals(AREA));
  }
}
