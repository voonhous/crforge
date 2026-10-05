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
 * scene casts Zap rewritten in that form, with a shape the configured tables ship.
 */
class BattleShapedFilterFormTest {

  /** The level the scene's cards are played at. */
  private static final int LEVEL = 1;

  /** Steps a battle until its counter shows the given tick. */
  private static void stepTo(Standard1v1Battle match, int tick) {
    while (match.getBattle().getTick() < tick) {
      match.getBattle().step();
    }
  }

  /**
   * The configured tables with Zap rewritten as a shaped row of the newer class: no buff, the given
   * shape, and what the edit sets.
   */
  private static GameTables shapedZap(Path folder, String shape, Consumer<ObjectNode> edit)
      throws IOException {
    return BattleAreaEffectFilterFormTest.filterForm(
        folder,
        "Zap",
        columns -> {
          columns.remove("Buff");
          columns.put("Shape", shape);
          edit.accept(columns);
        });
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
    // The Giant hero form's landing circle: radius 1000, while Zap's own radius is 2500.
    GameTables tables =
        shapedZap(
            folder,
            "GiantHero_LandingAEO_Shape",
            columns -> {
              ObjectNode damage = columns.putObject("Damage");
              damage.put("BaseDamage", 53);
              damage.put("Effect", "vfx_giant_hero_ground_hit");
            });
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<String> hits = hits(match);
    match.play(190, match.getWorld().getRecords().card("Knight"), LEVEL, 1, 14500, 22500, "near");
    match.play(190, match.getWorld().getRecords().card("Knight"), LEVEL, 1, 12000, 22500, "far");
    match.play(200, match.getWorld().getRecords().card("Zap"), LEVEL, 0, 14500, 22000, "zap");
    stepTo(match, 300);
    CharacterEntity near = match.getPlays().get(0).units().get(0);
    CharacterEntity far = match.getPlays().get(1).units().get(0);
    // The near Knight stands 500 from the point, within 1000 and its collision radius; the far one
    // 2550, within Zap's own 2500 and its collision radius but outside the shape.
    assertThat(hits).containsExactly("Knight side 1 53");
    assertThat(near.getHitPoints().getHitPoints()).isEqualTo(near.getHitPoints().getMaximum() - 53);
    assertThat(far.getHitPoints().getHitPoints()).isEqualTo(far.getHitPoints().getMaximum());
  }

  @Test
  @DisplayName(
      "a circle shaped row whose damage names a damage type deals that type's base damage to a"
          + " troop and its tower damage to a crown tower, nearest first")
  void aShapedCircleDealsTheNamedDamageType(@TempDir Path folder) throws IOException {
    // The Ice Golemite hero form's damage circle: radius 4000, the type it names written as the
    // newer data writes it.
    shapedZap(
        folder,
        "IceGolemiteHero_AEO_Shape",
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
    match.play(190, match.getWorld().getRecords().card("Knight"), LEVEL, 1, 14500, 22500, "knight");
    match.play(200, match.getWorld().getRecords().card("Zap"), LEVEL, 0, 14500, 23500, "zap");
    stepTo(match, 300);
    CharacterEntity knight = match.getPlays().get(0).units().get(0);
    assertThat(hits).containsExactly("Knight side 1 27", "PrincessTower side 1 2");
    assertThat(knight.getHitPoints().getHitPoints())
        .isEqualTo(knight.getHitPoints().getMaximum() - 27);
  }

  @Test
  @DisplayName(
      "a rectangle shaped row deals its damage to what its rectangle reaches, beyond its own"
          + " radius")
  void aShapedRectangleDealsItsDamageInTheRectangle(@TempDir Path folder) throws IOException {
    // The evolved Baby Dragon's wind rectangle: 8000 wide and 9000 long.
    GameTables tables =
        shapedZap(
            folder,
            "BabyDragon_EV1_wind_aeo_shape",
            columns -> columns.putObject("Damage").put("BaseDamage", 53));
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<String> hits = hits(match);
    match.play(190, match.getWorld().getRecords().card("Knight"), LEVEL, 1, 11000, 20000, "knight");
    match.play(200, match.getWorld().getRecords().card("Zap"), LEVEL, 0, 14500, 20000, "zap");
    stepTo(match, 300);
    CharacterEntity knight = match.getPlays().get(0).units().get(0);
    // 3500 along the width from the point: inside the rectangle's half width of 4000, outside
    // Zap's own 2500 and the Knight's collision radius.
    assertThat(hits).contains("Knight side 1 53");
    assertThat(knight.getHitPoints().getHitPoints())
        .isEqualTo(knight.getHitPoints().getMaximum() - 53);
  }
}
