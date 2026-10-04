package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A row that writes a column in a form the battle does not read is refused as the battle builds it,
 * never read as 0, false or nothing: an area effect's damage written as a table of a base and a
 * tower damage, or as the name of a damage type row; an area effect that chooses what it hits by a
 * filter in place of its air, ground and enemy switches; and a unit's death area effect of that
 * form. Each scene alters one row of the configured tables to the form.
 */
class BattleTableValueShapeTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The tick the area effects are placed on. */
  private static final int CAST_TICK = 25;

  /** The tick by which a scene's refusal is due. */
  private static final int LAST_TICK = 200;

  private static final int X = 3500;

  private static final int Y = 11000;

  /** Steps a battle until the tick, or until it throws. */
  private static void run(Standard1v1Battle match) {
    while (match.getBattle().getTick() < LAST_TICK) {
      match.getBattle().step();
    }
  }

  /** The configured tables with the area effect row's columns edited. */
  private static GameTables withAreaEffect(Path folder, String row, Consumer<ObjectNode> edit)
      throws IOException {
    return GameData.altered(
        folder, "area_effect_objects", rows -> edit.accept(GameData.columns(rows, row)));
  }

  @Test
  @DisplayName("an area effect whose damage is a table of a base and a tower damage is refused")
  void aDamageTableIsRefused(@TempDir Path folder) throws IOException {
    ObjectNode damage = JsonNodeFactory.instance.objectNode().put("BaseDamage", 75);
    damage.put("TowerDamage", 19);
    Standard1v1Battle match =
        new Standard1v1Battle(
            withAreaEffect(
                folder,
                "Zap",
                columns -> {
                  columns.set("Damage", damage);
                  columns.remove("CrownTowerDamagePercent");
                }),
            LEVEL,
            false);
    match.placeAreaEffect(CAST_TICK, "Zap", LEVEL, 1, X, Y, "Zap");

    assertThatThrownBy(() -> run(match))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "the area_effect_objects row Zap sets Damage to a table of BaseDamage, TowerDamage where"
                + " a number is read, which is not modelled");
  }

  @Test
  @DisplayName("an area effect whose damage names a damage type row is refused")
  void aDamageByNameIsRefused(@TempDir Path folder) throws IOException {
    Standard1v1Battle match =
        new Standard1v1Battle(
            withAreaEffect(folder, "Zap", columns -> columns.put("Damage", "ElectroWizZap")),
            LEVEL,
            false);
    match.placeAreaEffect(CAST_TICK, "Zap", LEVEL, 1, X, Y, "Zap");

    assertThatThrownBy(() -> run(match))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "the area_effect_objects row Zap sets Damage to the text \"ElectroWizZap\" where a"
                + " number is read, which is not modelled");
  }

  @Test
  @DisplayName(
      "an area effect without a shape that chooses its hits by a filter, setting neither its air"
          + " nor its ground switch, is refused")
  void aFilterInPlaceOfTheSwitchesIsRefused(@TempDir Path folder) throws IOException {
    Standard1v1Battle match =
        new Standard1v1Battle(
            withAreaEffect(
                folder,
                "Zap",
                columns -> {
                  columns.remove("HitsAir");
                  columns.remove("HitsGround");
                  columns.remove("OnlyEnemies");
                  columns.put("Filter", "CommonAreaDamageFilter");
                }),
            LEVEL,
            false);
    match.placeAreaEffect(CAST_TICK, "Zap", LEVEL, 1, X, Y, "Zap");

    assertThatThrownBy(() -> run(match))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("the area effect Zap sets columns not modelled: [Filter]");
  }

  @Test
  @DisplayName("a unit whose death area effect chooses its hits by a filter is refused as it dies")
  void aDeathAreaEffectByFilterIsRefused(@TempDir Path folder) throws IOException {
    GameTables tables =
        withAreaEffect(
            folder,
            "FreezeIceGolemite",
            columns -> {
              columns.remove("HitsAir");
              columns.remove("HitsGround");
              columns.remove("OnlyEnemies");
              columns.remove("AffectsHidden");
              columns.put("Filter", "CommonAreaDamageFilter");
            });
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    match.deploy(
        CAST_TICK,
        new BattleRecords(tables).unit("IceGolemite").toBuilder().lifeTimeMs(1000).build(),
        LEVEL,
        1,
        X,
        Y,
        "IceGolemite");

    assertThatThrownBy(() -> run(match))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("the area effect FreezeIceGolemite sets columns not modelled: [Filter]");
  }

  @Test
  @DisplayName("a variable whose row gives it a start other than 0 is refused as the battle starts")
  void aVariableDefaultIsRefused(@TempDir Path folder) throws IOException {
    GameTables tables =
        GameData.altered(
            folder,
            "variables",
            rows -> GameData.columns(rows, "InfernoDragon_EV1_AttackCount").put("DefaultValue", 7));

    assertThatThrownBy(() -> new Standard1v1Battle(tables, LEVEL, false))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "the variables row InfernoDragon_EV1_AttackCount sets DefaultValue, which is not"
                + " modelled");
  }
}
