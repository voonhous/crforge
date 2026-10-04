package org.crforge.core.battle.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.crforge.core.battle.unit.AreaDamageType;
import org.crforge.core.battle.unit.AreaEffectData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tables of data version 16.402.18 write an area effect in the filter form: its targets chosen
 * by a filter in place of its air, ground and enemy switches, and its damage as a table of a base
 * and a tower damage or as the name of a damage type row; a unit's death damage is a death area
 * effect of that form. Each row of the form without a shape is read as the filter form, its damage
 * as its damage type, never as 0 or as hitting nothing; a shaped row whose damage is written so is
 * still refused as it is built.
 *
 * <p>Run when the configured game tables are those of 16.402.18, or sit beside a folder of them as
 * in a checkout of the game data repository; skipped otherwise.
 */
class NewTableFormsTest {

  /** The data version whose forms these are. */
  private static final String VERSION = "16.402.18";

  private static GameTables tables;
  private static BattleRecords records;

  @BeforeAll
  static void load() {
    Optional<Path> folder = folder();
    assumeTrue(folder.isPresent(), "no game tables of " + VERSION + " configured");
    tables = GameTables.load(folder.get());
    assertThat(tables.version()).isEqualTo(VERSION);
    records = new BattleRecords(tables);
  }

  /** The configured folder when it is of the version, else a folder of the version beside it. */
  private static Optional<Path> folder() {
    Optional<Path> configured = GameTables.configuredDirectory();
    if (configured.isEmpty()) {
      return Optional.empty();
    }
    Path folder = configured.get().toAbsolutePath();
    if (folder.getFileName().toString().equals(VERSION)) {
      return Optional.of(folder);
    }
    Path beside = folder.resolveSibling(VERSION);
    return Files.isDirectory(beside) ? Optional.of(beside) : Optional.empty();
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
      "an area effect's damage written as a table is read as its damage type, and refused for a"
          + " shaped row")
  void aDamageTableIsReadAsItsDamageType() {
    List<String> rows = damageWritten(true);
    assertThat(rows).hasSize(42).contains("Zap", "GolemDeathExplosion");
    for (String row : rows) {
      GameRow source = tables.table("area_effect_objects").row(row);
      if (!source.string("Shape").isEmpty()) {
        assertThatThrownBy(() -> records.areaEffect(row))
            .as(row)
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageStartingWith(
                "the area_effect_objects row " + row + " sets Damage to a table of BaseDamage")
            .hasMessageEndingWith(" where a number is read, which is not modelled");
        continue;
      }
      AreaEffectData data = records.areaEffect(row);
      assertThat(data.filterHits()).as(row).isTrue();
      assertThat(data.typedDamage()).as(row).isEqualTo(typeOf(null, source.value("Damage")));
      assertThat(data.unmodelledColumns()).as(row).doesNotContain("Damage", "Filter");
    }
    assertThat(records.areaEffect("Zap").typedDamage()).isEqualTo(new AreaDamageType(null, 75, 19));
  }

  @Test
  @DisplayName(
      "an area effect's damage written as a damage type's name is read as that row, and refused"
          + " for a shaped row")
  void aDamageByNameIsReadAsThatRow() {
    List<String> rows = damageWritten(false);
    assertThat(rows).hasSize(6).contains("ElectroWizardZap", "IceWizardCold");
    for (String row : rows) {
      GameRow source = tables.table("area_effect_objects").row(row);
      JsonNode damage = source.value("Damage");
      assertThat(tables.table("damage_types").has(damage.asText())).as(row).isTrue();
      if (!source.string("Shape").isEmpty()) {
        assertThatThrownBy(() -> records.areaEffect(row))
            .as(row)
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessage(
                "the area_effect_objects row "
                    + row
                    + " sets Damage to the text \""
                    + damage.asText()
                    + "\" where a number is read, which is not modelled");
        continue;
      }
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
    assertThat(rows).hasSizeGreaterThan(100).contains("Zap", "Heal", "Rage");
    for (String row : rows) {
      AreaEffectData data = records.areaEffect(row);
      assertThat(data.filterHits()).as(row).isTrue();
      assertThat(data.filter())
          .as(row)
          .isEqualTo(tables.table("area_effect_objects").row(row).string("Filter"));
      assertThat(data.unmodelledColumns()).as(row).doesNotContain("Filter");
    }
    assertThat(records.areaEffect("Rage").filter()).isEqualTo("all_friendly_troops");
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
    assertThat(deaths).isEqualTo(26);
  }
}
