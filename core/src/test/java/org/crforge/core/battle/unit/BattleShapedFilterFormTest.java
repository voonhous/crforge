/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A shaped area effect of the newer area effect class, which has no hit switches and no damage type
 * column, as the Giant hero form's landing and the Ice Golemite hero form's damage circle are
 * written: its damage is its Damage column, a damage type written inline or named from the damage
 * types table. It hits as the filter form does - on the same schedule, nearest first, its damage
 * queued as that damage type on each object - but lists the objects its filter passes in its shape,
 * a circle of the shape's radius or a rectangle, in place of the circle of its own radius. Each
 * scene casts Zap rewritten in that form, with a shape row of the configured tables written to the
 * size the scene needs, Zap's own circle, the Knight's collision radius and the towers written too,
 * so every place is chosen against sizes the test sets.
 */
class BattleShapedFilterFormTest {

  /** The level the scene's cards are played at. */
  private static final int LEVEL = 1;

  /** Zap's own radius, written; the shapes reach past it or fall short of it. */
  private static final int ZAP_RADIUS = 2500;

  /** The Knight's collision radius, written. */
  private static final int KNIGHT_COLLISION = 500;

  /** The circle written on the Giant hero form's landing shape: within Zap's own radius. */
  private static final int SMALL_CIRCLE = 1000;

  /** The circle written on the Ice Golemite hero form's damage shape. */
  private static final int LARGE_CIRCLE = 4000;

  /** The rectangle written on the evolved Baby Dragon's wind shape: its width and its length. */
  private static final int RECTANGLE_WIDTH = 8000;

  private static final int RECTANGLE_LENGTH = 9000;

  /** Steps a battle until its counter shows the given tick. */
  private static void stepTo(Standard1v1Battle match, int tick) {
    while (match.getBattle().getTick() < tick) {
      match.getBattle().step();
    }
  }

  /**
   * The configured tables with Zap rewritten as a shaped row of the newer class: no buff, the given
   * shape, its own circle of {@link #ZAP_RADIUS} and a life of 1 ms, so its one hit falls on its
   * first update, and what the edit sets. The shape's row is written as the shape edit sets it, the
   * Knight's collision radius as {@link #KNIGHT_COLLISION}, and the towers as {@link
   * GameData#writeTowers} writes them.
   */
  private static GameTables shapedZap(
      Path folder, String shape, Consumer<ObjectNode> shapeEdit, Consumer<ObjectNode> edit)
      throws IOException {
    BattleAreaEffectFilterFormTest.filterForm(
        folder,
        "Zap",
        columns -> {
          columns.remove("Buff");
          columns.put("Shape", shape);
          columns.put("Radius", ZAP_RADIUS);
          columns.put("LifeDuration", 1);
          edit.accept(columns);
        });
    GameData.alterLoaded(
        folder,
        "shapes",
        rows -> {
          ObjectNode columns = GameData.columns(rows, shape);
          columns.removeAll();
          shapeEdit.accept(columns);
        });
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> GameData.columns(rows, "Knight").put("CollisionRadius", KNIGHT_COLLISION));
    GameData.writeTowers(folder);
    return GameTables.load(folder);
  }

  /** Plays a Knight for side 1 on tick 190 at the point given. */
  private static void playKnight(Standard1v1Battle match, int x, int y, String name) {
    match.play(190, match.getWorld().getRecords().card("Knight"), LEVEL, 1, x, y, name);
  }

  /** A circle shape's columns: the given radius. */
  private static Consumer<ObjectNode> circle(int radius) {
    return columns -> columns.put("ClassType", "Circle").put("Radius", radius);
  }

  /** Records every typed hit dealt, in order, as the target's row, side and the amount. */
  private static List<String> hits(Standard1v1Battle match) {
    List<String> hits = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void typedHitDealt(
                  int tick,
                  WorldEntity source,
                  WorldEntity target,
                  int amount,
                  int damageId,
                  DamageResult result) {
                hits.add(target.getData().name() + " side " + target.side() + " " + amount);
              }
            });
    return hits;
  }

  @Test
  @DisplayName(
      "a circle shaped row whose damage is a table deals it to what its shape's circle reaches,"
          + " not to what its own radius would")
  void aShapedCircleDealsItsDamageTableInTheShape(@TempDir Path folder) throws IOException {
    // The Giant hero form's landing shape, written as a circle well within Zap's own radius.
    GameTables tables =
        shapedZap(
            folder,
            "GiantHero_LandingAEO_Shape",
            circle(SMALL_CIRCLE),
            columns -> {
              ObjectNode damage = columns.putObject("Damage");
              damage.put("BaseDamage", 53);
              damage.put("Effect", "vfx_giant_hero_ground_hit");
            });
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<String> hits = hits(match);
    int x = 14500;
    int y = 22000;
    // The near Knight half the circle's radius from the point; the far one Zap's own radius across
    // and as far along.
    playKnight(match, x, y + SMALL_CIRCLE / 2, "near");
    playKnight(match, x - ZAP_RADIUS, y + SMALL_CIRCLE / 2, "far");
    match.play(200, match.getWorld().getRecords().card("Zap"), LEVEL, 0, x, y, "zap");
    stepTo(match, 300);
    CharacterEntity near = match.getPlays().get(0).units().get(0);
    CharacterEntity far = match.getPlays().get(1).units().get(0);
    // The near Knight stands within the circle; the far one, some 2550 from the point, within Zap's
    // own radius and its collision radius (3000) but beyond the circle and its collision radius
    // (1500).
    assertThat(hits).containsExactly("Knight side 1 53");
    assertThat(near.getHitPoints().getHitPoints()).isEqualTo(near.getHitPoints().getMaximum() - 53);
    assertThat(far.getHitPoints().getHitPoints()).isEqualTo(far.getHitPoints().getMaximum());
  }

  @Test
  @DisplayName(
      "a circle shaped row whose damage names a damage type deals that type's base damage to a"
          + " troop and its tower damage to a crown tower, nearest first")
  void aShapedCircleDealsTheNamedDamageType(@TempDir Path folder) throws IOException {
    // The Ice Golemite hero form's damage shape, written as a circle reaching the princess tower
    // behind the Knight, the type it names written as the newer data writes it.
    shapedZap(
        folder,
        "IceGolemiteHero_AEO_Shape",
        circle(LARGE_CIRCLE),
        columns -> columns.put("Damage", "IceGolemiteHero_AEO_Damage"));
    GameData.alterLoaded(
        folder,
        "damage_types",
        rows -> {
          ObjectNode columns = GameData.columns(rows, "IceGolemiteHero_AEO_Damage");
          columns.removeAll();
          columns.put("BaseDamage", 27);
          columns.put("TowerDamage", 2);
        });
    Standard1v1Battle match = new Standard1v1Battle(GameTables.load(folder), LEVEL, false);
    List<String> hits = hits(match);
    // The Knight a quarter of the circle's radius in front of the point; the written right princess
    // tower of side 1 2000 behind it, within the circle and its collision radius; the king tower
    // beyond them.
    int x = 14500;
    int y = 23500;
    playKnight(match, x, y - LARGE_CIRCLE / 4, "knight");
    match.play(200, match.getWorld().getRecords().card("Zap"), LEVEL, 0, x, y, "zap");
    stepTo(match, 300);
    CharacterEntity knight = match.getPlays().get(0).units().get(0);
    assertThat(hits).containsExactly("Knight side 1 27", "PrincessTower side 1 2");
    assertThat(knight.getHitPoints().getHitPoints())
        .isEqualTo(knight.getHitPoints().getMaximum() - 27);
  }

  @Test
  @DisplayName(
      "a rectangle shaped row deals its damage to what its rectangle reaches, beyond its own"
          + " radius, nearest first")
  void aShapedRectangleDealsItsDamageInTheRectangle(@TempDir Path folder) throws IOException {
    // The evolved Baby Dragon's wind shape, written as a rectangle wider than Zap's own circle.
    GameTables tables =
        shapedZap(
            folder,
            "BabyDragon_EV1_wind_aeo_shape",
            columns ->
                columns
                    .put("ClassType", "Rectangle")
                    .put("Width", RECTANGLE_WIDTH)
                    .put("Height", RECTANGLE_LENGTH),
            columns -> columns.putObject("Damage").put("BaseDamage", 53));
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<String> hits = hits(match);
    int x = 14500;
    int y = 20000;
    playKnight(match, x - (RECTANGLE_WIDTH / 2 - KNIGHT_COLLISION), y, "knight");
    match.play(200, match.getWorld().getRecords().card("Zap"), LEVEL, 0, x, y, "zap");
    stepTo(match, 300);
    CharacterEntity knight = match.getPlays().get(0).units().get(0);
    // Half the width less its collision radius along the width from the point (3500): inside the
    // rectangle, beyond Zap's own radius and the Knight's collision radius (3000). The written
    // right princess tower of side 1, behind the point, is within the rectangle's length with its
    // collision radius, and is hit after the nearer Knight.
    assertThat(hits).containsExactly("Knight side 1 53", "PrincessTower side 1 53");
    assertThat(knight.getHitPoints().getHitPoints())
        .isEqualTo(knight.getHitPoints().getMaximum() - 53);
  }
}
