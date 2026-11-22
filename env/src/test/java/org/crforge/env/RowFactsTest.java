/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameAction;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The static facts the registry publishes per vocabulary row ({@link Fields#rowFacts()}, the
 * metadata's {@code fields.rows}): one value per row id, read from the tables the environment
 * loads, and held here to those tables' own columns.
 */
class RowFactsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static GameTables tables;
  private static Vocabulary vocabulary;
  private static RowFacts facts;

  @BeforeAll
  static void loadTables() {
    tables = GameData.tables();
    vocabulary = VectorEnv.vocabulary(tables);
    facts = vocabulary.rowFacts();
  }

  /** The character or building row of a name, the characters asked first, or null for none. */
  private static GameRow unitRow(String name) {
    for (String table : List.of("characters", "buildings")) {
      if (tables.tableNames().contains(table) && tables.table(table).has(name)) {
        return tables.table(table).row(name);
      }
    }
    return null;
  }

  private static int value(String fact, String name) {
    return facts.values(fact)[vocabulary.id(name)];
  }

  @Test
  void everyFactHasOneValuePerVocabularyRow() {
    for (Fields.RowFact fact : Fields.rowFacts()) {
      assertThat(facts.values(fact.name())).as(fact.name()).hasSize(vocabulary.size());
    }
  }

  @Test
  void theMetadataOfATablesVectorPublishesTheFacts() throws IOException {
    JsonNode metadata = JSON.readTree(VectorEnv.metadataJson(EnvConfig.DEFAULT, vocabulary));
    JsonNode fields = metadata.get("fields");
    assertThat(fields).isEqualTo(Fields.toNode(vocabulary));
    // The registry itself is the one without the tables; only the facts' values are added.
    JsonNode plain = Fields.toNode();
    fields
        .fieldNames()
        .forEachRemaining(
            key -> {
              if (!key.equals("rows")) {
                assertThat(fields.get(key)).as(key).isEqualTo(plain.get(key));
              }
            });
    for (Fields.RowFact fact : Fields.rowFacts()) {
      JsonNode node = fields.get("rows").get(fact.name());
      assertThat(node.get("units").asText()).isEqualTo(fact.units());
      List<Integer> values = new ArrayList<>();
      node.get("values").forEach(v -> values.add(v.asInt()));
      int[] expected = facts.values(fact.name());
      assertThat(values).hasSize(expected.length);
      for (int id = 0; id < expected.length; id++) {
        assertThat(values.get(id)).as("%s of %d", fact.name(), id).isEqualTo(expected[id]);
      }
    }
    try (VectorEnv env = VectorEnv.ladder(tables, EnvConfig.DEFAULT, 1, 1)) {
      assertThat(JSON.readTree(env.metadataJson()).get("fields")).isEqualTo(fields);
    }
  }

  @Test
  void theAttackFactsAreTheRowsColumnsAsTheBattleLoadsThem() {
    BattleRecords records = new BattleRecords(tables);
    int units = 0;
    for (String name : vocabulary.names()) {
      GameRow row = unitRow(name);
      int hitSpeed = value("hit_speed_ms", name);
      if (row == null) {
        assertThat(value("bar_rule", name)).as(name).isEqualTo(Fields.BAR_NOT_UNIT);
        for (Fields.RowFact fact : Fields.rowFacts()) {
          assertThat(value(fact.name(), name)).as("%s of %s", fact.name(), name).isZero();
        }
        continue;
      }
      if (hitSpeed == -1) {
        // A row the battle core does not load: no unit of it ever plays.
        assertThatThrownBy(() -> records.unit(name)).as(name).isInstanceOf(RuntimeException.class);
        for (String fact :
            List.of("load_time_ms", "attack_step_ms", "attack_start_delay_ms", "attack_traits")) {
          assertThat(value(fact, name)).as("%s of %s", fact, name).isEqualTo(-1);
        }
        continue;
      }
      units++;
      assertThat(hitSpeed).as(name).isEqualTo(row.intValue("HitSpeed"));
      assertThat(value("load_time_ms", name)).as(name).isEqualTo(row.intValue("LoadTime"));
      boolean tower = row.bool("IsSummoner") || row.bool("IsSummonerTower");
      int traits = value("attack_traits", name);
      assertThat((traits & Fields.TRAIT_LOAD_FIRST_HIT) != 0)
          .as("%s loads first", name)
          .isEqualTo(row.bool("LoadFirstHit") && !tower);
      assertThat((traits & Fields.TRAIT_CHARGE) != 0)
          .as("%s charges", name)
          .isEqualTo(row.intValue("ChargeRange") != 0);
      assertThat((traits & Fields.TRAIT_DASH) != 0)
          .as("%s dashes", name)
          .isEqualTo(
              row.intValue("DashCooldown") > 0
                  || row.intValue("DashMinRange") > 0
                  || row.intValue("DashMaxRange") >= 1
                  || row.intValue("DashCount") > 0);
      assertThat((traits & Fields.TRAIT_SPECIAL) != 0)
          .as("%s has a special", name)
          .isEqualTo(row.intValue("SpecialRange") >= 1 || row.intValue("SpecialLoadTime") >= 1);
      // The step and the start delay: one value over the row's sequence entries and the plain
      // step (a multiplier of 100, no delay), else -1.
      Set<Integer> multipliers = new HashSet<>(List.of(100));
      Set<Integer> delays = new HashSet<>(List.of(0));
      JsonNode list = row.value("AttackSequenceList");
      if (list != null && !list.isNull()) {
        for (JsonNode element : elements(list)) {
          multipliers.add(element.path("HitSpeedMultiplier").asInt(100));
          delays.add(element.path("AttackStartDelay").asInt(0));
        }
      }
      assertThat(value("attack_step_ms", name))
          .as("%s step", name)
          .isEqualTo(multipliers.size() == 1 ? multipliers.iterator().next() / 2 : -1);
      assertThat(value("attack_start_delay_ms", name))
          .as("%s start delay", name)
          .isEqualTo(delays.size() == 1 ? delays.iterator().next() : -1);
    }
    assertThat(units).isPositive();
  }

  @Test
  void theBarRuleIsTheRowsHealthBarColumns() {
    for (String name : vocabulary.names()) {
      GameRow row = unitRow(name);
      int rule = value("bar_rule", name);
      if (row == null) {
        assertThat(rule).as(name).isEqualTo(Fields.BAR_NOT_UNIT);
      } else if (!row.bool("HideHealthbar")) {
        assertThat(rule).as(name).isEqualTo(Fields.BAR_ALWAYS);
      } else if (buildsHealthBar(row)) {
        assertThat(rule).as(name).isIn(Fields.BAR_DAMAGED, Fields.BAR_DAMAGED_ROLLING);
      } else {
        assertThat(rule).as(name).isEqualTo(Fields.BAR_NEVER);
      }
    }
  }

  /** A column's elements: an array's, or the one value. */
  private static List<JsonNode> elements(JsonNode node) {
    List<JsonNode> elements = new ArrayList<>();
    if (node.isArray()) {
      node.forEach(elements::add);
    } else {
      elements.add(node);
    }
    return elements;
  }

  private static boolean buildsHealthBar(GameRow row) {
    JsonNode actions = row.value("OnStartingClientActions");
    if (actions == null) {
      return false;
    }
    for (JsonNode name : elements(actions)) {
      if (name.asText().toLowerCase(Locale.ROOT).contains("healthbar")) {
        return true;
      }
    }
    return false;
  }

  @Test
  void aRowWhoseAbilityRollsItHidesItsBarWhileItRolls() {
    // Every row of the damaged rule whose ability's activation runs an action that hides the bar
    // while it rolls; the facts may name more (the rows such a roll can change a unit to).
    Set<String> hiding = new HashSet<>();
    for (String action : tables.actionNames()) {
      GameAction row = tables.action(action);
      if (row.fields() != null && row.fields().path("HideHealthbarWhileRolling").asBoolean(false)) {
        hiding.add(action);
      }
    }
    for (String name : vocabulary.names()) {
      GameRow row = unitRow(name);
      if (row == null
          || !row.has("Ability")
          || !tables.table("character_abilities").has(row.string("Ability"))) {
        continue;
      }
      GameRow ability = tables.table("character_abilities").row(row.string("Ability"));
      JsonNode activation = ability.value("OnActivationAction");
      String action =
          activation == null
              ? ""
              : activation.isTextual() ? activation.asText() : activation.path("action").asText();
      if (hiding.contains(action) && row.bool("HideHealthbar") && buildsHealthBar(row)) {
        assertThat(value("bar_rule", name)).as(name).isEqualTo(Fields.BAR_DAMAGED_ROLLING);
      }
    }
    // Only a row of the damaged rule rolls its bar away.
    for (String name : vocabulary.names()) {
      if (value("bar_rule", name) == Fields.BAR_DAMAGED_ROLLING) {
        GameRow row = unitRow(name);
        assertThat(row.bool("HideHealthbar") && buildsHealthBar(row)).as(name).isTrue();
      }
    }
  }
}
