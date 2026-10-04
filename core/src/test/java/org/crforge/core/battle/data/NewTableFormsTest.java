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
import org.crforge.core.battle.unit.AreaEffectData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tables of data version 16.402.18 write three forms the battle does not read: an area effect's
 * damage as a table of a base and a tower damage, or as the name of a damage type row; an area
 * effect's targets chosen by a filter in place of its air, ground and enemy switches; and a unit's
 * death damage as a death area effect of those forms. Each is refused, never read as 0 or false.
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

  /** Whether an area effect row is refused: as it is built, or by a column it lists. */
  private static boolean refused(String areaEffect, String column) {
    try {
      return records.areaEffect(areaEffect).unmodelledColumns().contains(column);
    } catch (UnsupportedOperationException e) {
      return e.getMessage().contains(" sets " + column + " ");
    }
  }

  @Test
  @DisplayName("an area effect's damage written as a table is refused as the area effect is built")
  void aDamageTableIsRefused() {
    List<String> rows = damageWritten(true);
    assertThat(rows).hasSize(42).contains("Zap", "GolemDeathExplosion");
    for (String row : rows) {
      assertThatThrownBy(() -> records.areaEffect(row))
          .as(row)
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageStartingWith(
              "the area_effect_objects row " + row + " sets Damage to a table of BaseDamage")
          .hasMessageEndingWith(" where a number is read, which is not modelled");
    }
  }

  @Test
  @DisplayName("an area effect's damage written as a damage type's name is refused")
  void aDamageByNameIsRefused() {
    List<String> rows = damageWritten(false);
    assertThat(rows).hasSize(6).contains("ElectroWizardZap", "IceWizardCold");
    for (String row : rows) {
      JsonNode damage = tables.table("area_effect_objects").row(row).value("Damage");
      assertThat(tables.table("damage_types").has(damage.asText())).as(row).isTrue();
      assertThatThrownBy(() -> records.areaEffect(row))
          .as(row)
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessage(
              "the area_effect_objects row "
                  + row
                  + " sets Damage to the text \""
                  + damage.asText()
                  + "\" where a number is read, which is not modelled");
    }
  }

  @Test
  @DisplayName(
      "an area effect without a shape that chooses its targets by a filter, setting neither its air"
          + " nor its ground switch, is refused by its Filter")
  void aFilterInPlaceOfTheSwitchesIsRefused() {
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
      assertThat(refused(row, "Filter") || refused(row, "Damage"))
          .as("%s refused by its Filter or its Damage", row)
          .isTrue();
    }
    // Neither of them is read as hitting nothing: Zap's damage table is refused as it is built,
    // and Heal, without one, lists its Filter.
    assertThat(records.areaEffect("Heal").unmodelledColumns()).contains("Filter");
  }

  @Test
  @DisplayName(
      "a unit whose death area effect writes a damage table or a filter is refused as it dies,"
          + " never read as a death without damage")
  void aDeathAreaEffectOfTheNewFormsIsRefused() {
    int refusedDeaths = 0;
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
        assertThat(refused(areaEffect, "Damage") || refused(areaEffect, "Filter"))
            .as("%s's death area effect %s refused", row.name(), areaEffect)
            .isTrue();
        refusedDeaths++;
      }
    }
    assertThat(refusedDeaths).isEqualTo(26);
    AreaEffectData heal = records.areaEffect("Heal");
    assertThat(heal.unmodelledColumns()).contains("Filter");
  }
}
