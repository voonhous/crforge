package org.crforge.core.battle.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.unit.AreaDamageType;
import org.crforge.core.battle.unit.AreaEffectData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tables of data version 16.402.18 write an area effect in the filter form: its targets chosen
 * by a filter in place of its air, ground and enemy switches, and its damage as a table of a base
 * and a tower damage or as the name of a damage type row; a unit's death damage is a death area
 * effect of that form. Each row of the form is read as the filter form, its damage as its damage
 * type, never as 0 or as hitting nothing; a shaped row so written too, its shape where it lists
 * what it reaches.
 */
class NewTableFormsTest {

  private static GameTables tables;
  private static BattleRecords records;

  @BeforeAll
  static void load() {
    tables = GameData.tables();
    records = GameData.records();
  }

  /** The area effect rows whose Damage is written in the given form. */
  private static List<String> damageWritten(boolean asTable) {
    List<String> rows = new ArrayList<>();
    for (GameRow row : tables.table("area_effect_objects").rows()) {
      JsonNode damage = row.value("Damage");
      if (damage != null && (asTable ? damage.isObject() : damage.isTextual())) {
        rows.add(row.name());
      }
    }
    return rows;
  }

  /** The damage type an inline table or a damage types row gives. */
  private static AreaDamageType typeOf(String name, JsonNode table) {
    JsonNode tower = table.get("TowerDamage");
    return new AreaDamageType(
        name,
        table.has("BaseDamage") ? table.get("BaseDamage").asInt() : 0,
        tower == null || tower.isNull() ? AreaDamageType.NO_TOWER_DAMAGE : tower.asInt());
  }

  @Test
  @DisplayName(
      "an area effect's damage written as a table is read as its damage type, for a shaped row"
          + " too")
  void aDamageTableIsReadAsItsDamageType() {
    List<String> rows = damageWritten(true);
    // How many rows write it so is the data's; the battle reads each.
    assertThat(rows).isNotEmpty();
    for (String row : rows) {
      GameRow source = tables.table("area_effect_objects").row(row);
      AreaEffectData data = records.areaEffect(row);
      assertThat(data.filterHits()).as(row).isTrue();
      assertThat(data.typedDamage()).as(row).isEqualTo(typeOf(null, source.value("Damage")));
      assertThat(data.unmodelledColumns()).as(row).doesNotContain("Damage", "Filter");
    }
    // A shaped row so written, as the Giant hero form's landing is, lists what it reaches in its
    // shape's circle.
    GameRow landingRow = tables.table("area_effect_objects").row("GiantHero_LandingAEO");
    assertThat(landingRow.value("Damage").isObject()).isTrue();
    AreaEffectData landing = records.areaEffect("GiantHero_LandingAEO");
    assertThat(landing.shaped()).isTrue();
    assertThat(landing.shapeRadius())
        .isEqualTo(tables.table("shapes").row(landingRow.string("Shape")).value("Radius").asInt());
    assertThat(landing.unmodelledColumns()).isEmpty();
  }

  @Test
  @DisplayName(
      "an area effect's damage written as a damage type's name is read as that row, for a shaped"
          + " row too")
  void aDamageByNameIsReadAsThatRow() {
    List<String> rows = damageWritten(false);
    // How many rows write it so is the data's; the battle reads each.
    assertThat(rows).isNotEmpty();
    for (String row : rows) {
      GameRow source = tables.table("area_effect_objects").row(row);
      JsonNode damage = source.value("Damage");
      assertThat(tables.table("damage_types").has(damage.asText())).as(row).isTrue();
      GameRow type = tables.table("damage_types").row(damage.asText());
      AreaEffectData data = records.areaEffect(row);
      assertThat(data.filterHits()).as(row).isTrue();
      assertThat(data.typedDamage())
          .as(row)
          .isEqualTo(
              new AreaDamageType(
                  type.name(),
                  type.intValue("BaseDamage"),
                  type.has("TowerDamage")
                      ? type.intValue("TowerDamage")
                      : AreaDamageType.NO_TOWER_DAMAGE));
    }
    // A shaped row so written, as the Ice Golemite hero form's damage circle is, lists what it
    // reaches in its shape's circle and deals its named type.
    GameRow stormRow = tables.table("area_effect_objects").row("IceGolemiteHero_Damage_AEO");
    assertThat(stormRow.value("Damage").isTextual()).isTrue();
    AreaEffectData storm = records.areaEffect("IceGolemiteHero_Damage_AEO");
    assertThat(storm.shaped()).isTrue();
    assertThat(storm.shapeRadius())
        .isEqualTo(tables.table("shapes").row(stormRow.string("Shape")).value("Radius").asInt());
    assertThat(storm.typedDamage().name()).isEqualTo(stormRow.value("Damage").asText());
    assertThat(storm.unmodelledColumns()).isEmpty();
  }

  @Test
  @DisplayName(
      "an area effect without a shape that chooses its targets by a filter, setting neither its air"
          + " nor its ground switch, is read as the filter form, its filter its own")
  void aFilterInPlaceOfTheSwitchesIsTheFilterForm() {
    List<String> rows = new ArrayList<>();
    for (GameRow row : tables.table("area_effect_objects").rows()) {
      if (!row.string("Filter").isEmpty()
          && row.string("Shape").isEmpty()
          && !row.has("HitsAir")
          && !row.has("HitsGround")) {
        rows.add(row.name());
      }
    }
    assertThat(rows).isNotEmpty();
    for (String row : rows) {
      AreaEffectData data = records.areaEffect(row);
      assertThat(data.filterHits()).as(row).isTrue();
      assertThat(data.filter())
          .as(row)
          .isEqualTo(tables.table("area_effect_objects").row(row).string("Filter"));
      assertThat(data.unmodelledColumns()).as(row).doesNotContain("Filter");
    }
  }

  @Test
  @DisplayName(
      "a unit whose death area effect writes a damage table or a filter has it read as the filter"
          + " form, never as a death without damage")
  void aDeathAreaEffectOfTheNewFormsIsTheFilterForm() {
    int deaths = 0;
    for (String table : List.of("characters", "buildings")) {
      for (GameRow row : tables.table(table).rows()) {
        String areaEffect = row.string("DeathAreaEffect");
        if (areaEffect.isEmpty()) {
          continue;
        }
        GameRow effect = tables.table("area_effect_objects").row(areaEffect);
        if (!effect.has("Damage") && effect.string("Filter").isEmpty()) {
          continue;
        }
        assertThat(records.unit(row.name()).deathAreaEffect()).as(row.name()).isEqualTo(areaEffect);
        AreaEffectData data = records.areaEffect(areaEffect);
        assertThat(data.filterHits())
            .as("%s's death area effect %s", row.name(), areaEffect)
            .isTrue();
        assertThat(data.unmodelledColumns())
            .as("%s's death area effect %s", row.name(), areaEffect)
            .doesNotContain("Damage", "Filter");
        deaths++;
      }
    }
    // How many units die so is the data's.
    assertThat(deaths).isPositive();
  }

  @Test
  @DisplayName(
      "a filter form row's hit action, one hit per target, hit action on itself and end on its"
          + " first hit are read, never refused, a Clone's switch among them")
  void theHitColumnsOfTheFilterFormAreRead() {
    List<String> columns =
        List.of("OnHitAction", "OneHitPerTarget", "OnHitSelfAction", "ExpireOnTrigger", "Clone");
    List<String> rows = new ArrayList<>();
    for (GameRow row : tables.table("area_effect_objects").rows()) {
      if (row.string("Filter").isEmpty() || !row.string("Shape").isEmpty()) {
        continue;
      }
      if (columns.stream().noneMatch(row::has)) {
        continue;
      }
      rows.add(row.name());
      AreaEffectData data = records.areaEffect(row.name());
      assertThat(data.filterHits()).as(row.name()).isTrue();
      assertThat(data.unmodelledColumns()).as(row.name()).doesNotContainAnyElementsOf(columns);
      assertThat(data.oneHitPerTarget()).as(row.name()).isEqualTo(row.bool("OneHitPerTarget"));
      assertThat(data.expireOnTrigger()).as(row.name()).isEqualTo(row.bool("ExpireOnTrigger"));
    }
    assertThat(rows).isNotEmpty();
    AreaEffectData earthquake = records.areaEffect("EarthquakeHiddenDamage");
    assertThat(earthquake.onHitAction())
        .isEqualTo(
            tables
                .table("area_effect_objects")
                .row("EarthquakeHiddenDamage")
                .string("OnHitAction"));
    assertThat(earthquake.unmodelledColumns()).isEmpty();
    assertThat(records.areaEffect("BoostAOE_TrickOrTreat_Pekka").onHitSelfAction())
        .isEqualTo(
            tables
                .table("area_effect_objects")
                .row("BoostAOE_TrickOrTreat_Pekka")
                .string("OnHitSelfAction"));
  }

  @Test
  @DisplayName(
      "a filter form row's target limit, biggest targets first, projectile and projectiles onto"
          + " each target are read, never refused")
  void theTargetColumnsOfTheFilterFormAreRead() {
    List<String> columns =
        List.of("MaximumTargets", "HitBiggestTargets", "Projectile", "TargetProjectiles");
    List<String> rows = new ArrayList<>();
    for (GameRow row : tables.table("area_effect_objects").rows()) {
      if (row.string("Filter").isEmpty() || !row.string("Shape").isEmpty()) {
        continue;
      }
      if (columns.stream().noneMatch(row::has)) {
        continue;
      }
      rows.add(row.name());
      AreaEffectData data = records.areaEffect(row.name());
      assertThat(data.filterHits()).as(row.name()).isTrue();
      assertThat(data.unmodelledColumns()).as(row.name()).doesNotContainAnyElementsOf(columns);
      assertThat(data.maximumTargets()).as(row.name()).isEqualTo(row.intValue("MaximumTargets"));
      assertThat(data.hitBiggestTargets()).as(row.name()).isEqualTo(row.bool("HitBiggestTargets"));
    }
    assertThat(rows).contains("Vines_AeO", "Lightning", "RoyalDeliveryArea");
    // Left out, a projectile goes onto each object hit; the Royal Delivery's goes onto its point.
    assertThat(records.areaEffect("Lightning").targetProjectiles()).isTrue();
    assertThat(records.areaEffect("RoyalDeliveryArea").targetProjectiles()).isFalse();
    assertThat(records.areaEffect("Vines_AeO").unmodelledColumns()).isEmpty();
    assertThat(records.areaEffect("Lightning").unmodelledColumns()).isEmpty();
    assertThat(records.areaEffect("RoyalDeliveryArea").unmodelledColumns()).isEmpty();
  }

  @Test
  @DisplayName(
      "a projectile's action on reaching its target, written inline, is the actions table's row"
          + " named after the projectile and the column, as its other action columns are")
  void anInlineReachedAction() {
    assertThat(records.projectile("AngryBarbarian_EV1_RangedProjectile").onTargetReachedAction())
        .isEqualTo("AngryBarbarian_EV1_RangedProjectile_OnTargetReachedAction");
  }
}
