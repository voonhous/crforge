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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A projectile of a group whose impact applies its target buff before its damage on a circle is
 * refused. The group is a wave of Arrows or a volley its launcher links into one (GroupProjectiles,
 * the Princess's arrows). The game buffs such an impact's circle through the group: the group's own
 * circle bounds it, and the group keeps a list of the objects the buff has reached, so a later
 * projectile of the group passes them by. The battle core's buff pass reads neither, so it would
 * buff the same objects again; no shipped grouped row applies its buff before its damage, so the
 * refusal is only met by a written row.
 *
 * <p>The scenes write a hand-made slow as the target buff of the grouped projectiles, applied
 * before their damage, and play the group onto a Knight. The same volley with its launcher no
 * longer linking it into a group lands its buff on the Knight, as an ungrouped projectile does.
 */
class BattleProjectileGroupBuffTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The hand-written buff the grouped projectiles carry; no configured table has it. */
  private static final String BUFF = "Test_Group_Before_Damage_Slow";

  /** The buff time, in milliseconds, longer than the scenes' steps after the first impact. */
  private static final int BUFF_TIME_MS = 20000;

  /** The circle the scenes give a grouped projectile that has none, so its impact has an area. */
  private static final int RADIUS = 1500;

  /** The lane the scenes play in, and the Knight's point on the top side's half. */
  private static final int X = 3500;

  private static final int KNIGHT_Y = 17500;

  /** The Princess's point on the bottom side's half, the Knight in her range. */
  private static final int PRINCESS_Y = 10000;

  /** The tick the scenes run to, long after the first projectile has landed. */
  private static final int END = 300;

  /** The card that casts a wave of projectiles in a ring, each wave one group. */
  private static final String ARROWS = "Arrows";

  /** The unit the projectiles land on. */
  private static final String KNIGHT = "Knight";

  /** The unit whose volleys are linked into one group. */
  private static final String PRINCESS = "Princess";

  /** Adds the hand-written slow to the buff rows. */
  private static void writeBuff(Path folder) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(
        folder,
        "character_buffs",
        rows -> {
          ObjectNode row = rows.putObject(BUFF);
          row.put("index", rows.size() - 1);
          row.put("class", "LogicCharacterBuffData");
          ObjectNode columns = row.putObject("columns");
          columns.put("SpeedMultiplier", -30);
          columns.put("Rarity", "Common");
        });
  }

  /**
   * Writes the slow onto each named projectile row, applied before its damage, with a circle of its
   * own when the row has none.
   */
  private static void writeBuffFirst(Path folder, List<String> projectiles) throws IOException {
    GameData.alterLoaded(
        folder,
        "projectiles",
        rows -> {
          for (String projectile : projectiles) {
            ObjectNode columns = GameData.columns(rows, projectile);
            columns
                .put("TargetBuff", BUFF)
                .put("BuffTime", BUFF_TIME_MS)
                .put("ApplyBuffBeforeDamage", true);
            if (!columns.has("Radius") || columns.get("Radius").asInt() < 1) {
              columns.put("Radius", RADIUS);
            }
          }
        });
  }

  /** The tables with Arrows' projectile applying the slow before its damage. */
  private static GameTables arrowsBuffFirst(Path folder) throws IOException {
    writeBuff(folder);
    String projectile = Shipped.text(Shipped.row("spells_other", ARROWS), "Projectile");
    writeBuffFirst(folder, List.of(projectile));
    return GameTables.load(folder);
  }

  /**
   * The tables with every projectile the Princess's volley is made of applying the slow before its
   * damage, and her row linking the volley into one group or not.
   */
  private static GameTables princessBuffFirst(Path folder, boolean grouped) throws IOException {
    writeBuff(folder);
    GameRow princess = Shipped.unitRow(PRINCESS);
    List<String> projectiles = new ArrayList<>();
    projectiles.add(Shipped.text(princess, "Projectile"));
    String first = Shipped.text(princess, "CustomFirstProjectile");
    if (first != null && !projectiles.contains(first)) {
      projectiles.add(first);
    }
    writeBuffFirst(folder, projectiles);
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> GameData.columns(rows, PRINCESS).put("GroupProjectiles", grouped));
    return GameTables.load(folder);
  }

  /** A battle with the top side's Knight standing on its half. */
  private static Standard1v1Battle withKnight(GameTables tables, BattleRecords records) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    match.deploy(0, records.unit(KNIGHT), LEVEL, 1, X, KNIGHT_Y);
    return match;
  }

  /** Steps the battle to the scenes' end. */
  private static void stepToEnd(Battle battle) {
    while (battle.getTick() < END) {
      battle.step();
    }
  }

  @Test
  @DisplayName("a wave of Arrows whose projectile buffs before its damage is refused at its impact")
  void anArrowsWaveBuffingFirstIsRefused(@TempDir Path folder) throws IOException {
    GameTables tables = arrowsBuffFirst(folder);
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = withKnight(tables, records);
    match.play(40, records.card(ARROWS), LEVEL, 0, X, KNIGHT_Y, ARROWS);
    String projectile = Shipped.text(Shipped.row("spells_other", ARROWS), "Projectile");

    assertThatThrownBy(() -> stepToEnd(match.getBattle()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(projectile)
        .hasMessageContaining("group")
        .hasMessageContaining("before its damage");
  }

  @Test
  @DisplayName(
      "a linked volley whose projectiles buff before their damage is refused at its impact")
  void aGroupedVolleyBuffingFirstIsRefused(@TempDir Path folder) throws IOException {
    GameTables tables = princessBuffFirst(folder, true);
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = withKnight(tables, records);
    match.deploy(0, records.unit(PRINCESS), LEVEL, 0, X, PRINCESS_Y);

    assertThatThrownBy(() -> stepToEnd(match.getBattle()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("group")
        .hasMessageContaining("before its damage");
  }

  @Test
  @DisplayName("the same volley not linked into a group lands its buff on the Knight")
  void anUngroupedVolleyBuffingFirstLands(@TempDir Path folder) throws IOException {
    GameTables tables = princessBuffFirst(folder, false);
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity knight = match.deploy(0, records.unit(KNIGHT), LEVEL, 1, X, KNIGHT_Y);
    match.deploy(0, records.unit(PRINCESS), LEVEL, 0, X, PRINCESS_Y);

    stepToEnd(match.getBattle());

    assertThat(knight.getBuffs().items())
        .as("the Knight carries the volley's slow")
        .anyMatch(b -> BUFF.equals(b.getBuff().name()));
  }
}
