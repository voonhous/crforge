package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A newer data version's evolved Pekka: its killed-done action is a group that creates a context,
 * writes the killed unit's hit points and shield hit points at a card level into it, and sends a
 * soul flying; the group that runs as the soul arrives inherits the context, and its select picks
 * the heal by the sum the context holds: the least below its first band, the middle below its
 * second, else the most. The test writes the whole chain, the two bands set against the sum it
 * works out for the killed unit, so each case is chosen by construction.
 */
class BattlePekkaEvoContextTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The card level index the context writer reads the hit points at, counted from 0. */
  private static final int CONTEXT_LEVEL_INDEX = 10;

  /** The soul's constant flight time, in milliseconds. */
  private static final int FLIGHT_MS = 700;

  /** The shield hit points written on the hero Knight's row, at the first level. */
  private static final int HERO_SHIELD = 300;

  /** The heals the select gives, least to most: buff rows of the configured tables. */
  private static final String HEAL_MIN = "PekkaEV1_HealMin";

  private static final String HEAL_MED = "PekkaEV1_HealMed";
  private static final String HEAL_MAX = "PekkaEV1_HealMax";

  /**
   * The configured tables with the newer version's killed-done chain of the evolved Pekka written
   * in full, its select's two bands the ones given, and the hero Knight's shield written.
   *
   * @param folder the folder the tables are copied into
   * @param first the sum below which the least heal is given
   * @param second the sum below which, failing the first, the middle heal is given
   */
  private static GameTables withContextChain(Path folder, int first, int second)
      throws IOException {
    GameData.altered(
        folder,
        "actions",
        rows -> {
          rows.set(
              "PekkaEV1_OnKill",
              action(
                  "LogicActionGroupData",
                  "ActionGroup",
                  f -> {
                    f.put("ContextMode", "Create");
                    parts(f, "PekkaEV1_WriteVictimHp", "PekkaEV1_SoulDrain");
                  }));
          rows.set(
              "PekkaEV1_WriteVictimHp",
              action(
                  "LogicActionWriteInstigatorInfoToContextData",
                  "ActionWriteInstigatorInfoToContext",
                  f -> {
                    f.put("HitpointsKey", "victim_hp");
                    f.put("HitpointsLevelIndex", CONTEXT_LEVEL_INDEX);
                    f.put("ShieldHitpointsKey", "victim_shield_hp");
                  }));
          rows.set(
              "PekkaEV1_SoulDrain",
              action(
                  "LogicActionSoulDrainData",
                  "ActionSoulDrain",
                  f -> {
                    f.putObject("ActionOnTargetReached").put("action", "PekkaEV1_SoulArrived");
                    f.put("ConstantFlightDuration", FLIGHT_MS);
                  }));
          rows.set(
              "PekkaEV1_SoulArrived",
              action(
                  "LogicActionGroupData",
                  "ActionGroup",
                  f -> {
                    f.put("ContextMode", "Inherit");
                    parts(f, "PekkaEV1_Heal");
                  }));
          rows.set(
              "PekkaEV1_Heal",
              action(
                  "LogicActionSelectData",
                  "ActionSelect",
                  f -> {
                    ArrayNode conditions = f.putArray("PerActionConditions");
                    conditions.add(
                        "as_int(#victim_hp, 0) + as_int(#victim_shield_hp, 0) < " + first);
                    conditions.add(
                        "as_int(#victim_hp, 0) + as_int(#victim_shield_hp, 0) < " + second);
                    ArrayNode heals = f.putArray("SubActions");
                    for (String heal : new String[] {HEAL_MIN, HEAL_MED, HEAL_MAX}) {
                      heals.addObject().put("action", "Give_" + heal);
                    }
                  }));
          for (String heal : new String[] {HEAL_MIN, HEAL_MED, HEAL_MAX}) {
            rows.set(
                "Give_" + heal,
                action(
                    "LogicActionSpawnData",
                    "ActionSpawn",
                    f -> {
                      f.put("ParentGOAsSource", true);
                      f.put("SpawnData", heal);
                      f.put("SpawnTime", 50);
                      f.put("SpawnType", "BuffType");
                    }));
          }
        });
    ObjectMapper mapper = new ObjectMapper();
    Path file = folder.resolve("characters.json");
    ObjectNode document = (ObjectNode) mapper.readTree(file.toFile());
    ObjectNode rows = (ObjectNode) document.get("rows");
    GameData.columns(rows, "Pekka_EV1").put("OnKilledDoneAction", "PekkaEV1_OnKill");
    GameData.columns(rows, "KnightHero").put("ShieldHitpoints", HERO_SHIELD);
    mapper.writeValue(file.toFile(), document);
    return GameTables.load(folder);
  }

  /**
   * A first-level column of a unit's row scaled to the context writer's card level, worked out in
   * the test.
   */
  private static int atContextLevel(String row, int base) {
    return Shipped.scaled(base, Shipped.unitRow(row), CONTEXT_LEVEL_INDEX + 1);
  }

  /**
   * The sum the context holds for a kill of a row: its hit points and its shield hit points, each
   * at the context writer's card level.
   */
  private static int contextSum(String row) {
    return atContextLevel(row, Shipped.number(Shipped.unitRow(row), "Hitpoints"))
        + atContextLevel(row, Shipped.number(Shipped.unitRow(row), "ShieldHitpoints"));
  }

  /** An action row of a class with its fields. */
  private static ObjectNode action(String className, String classType, Consumer<ObjectNode> edit) {
    ObjectNode row = new ObjectMapper().createObjectNode();
    row.put("class", className);
    row.put("ClassType", classType);
    ObjectNode fields = row.putObject("fields");
    fields.put("ClassType", classType);
    edit.accept(fields);
    return row;
  }

  /** A group's parts, none with a delay of its own. */
  private static void parts(ObjectNode fields, String... names) {
    ArrayNode parts = fields.putArray("SubActions");
    ArrayNode delays = fields.putArray("SubActionsDelay");
    for (String name : names) {
      parts.addObject().put("action", name);
      delays.add(0);
    }
  }

  /** An evolved Pekka of side 0 at (9000, 10000) facing a still unit of side 1 just ahead. */
  private static CharacterEntity[] facing(Standard1v1Battle match, String row) {
    CharacterEntity pekka =
        match.deploy(
            0, match.getWorld().getRecords().unit("Pekka_EV1"), LEVEL, 0, 9000, 10000, "pekka");
    CharacterEntity target =
        match.deploy(0, match.getWorld().getRecords().unit(row), LEVEL, 1, 9000, 12500, "target");
    target.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    return new CharacterEntity[] {pekka, target};
  }

  /** Steps until the unit is dead, at most 400 ticks. */
  private static void stepUntilDead(Standard1v1Battle match, CharacterEntity unit) {
    for (int i = 0; i < 400 && unit.getView().isAlive(); i++) {
      match.getBattle().step();
    }
    assertThat(unit.getView().isAlive()).as(unit.name() + " killed").isFalse();
  }

  /** The steps after the kill on which the unit first carries the buff, or -1 within 30. */
  private static int stepsUntilCarried(Standard1v1Battle match, CharacterEntity unit, String buff) {
    for (int i = 0; i < 30; i++) {
      if (unit.getBuffs().carries(buff)) {
        return i;
      }
      match.getBattle().step();
    }
    return -1;
  }

  /**
   * The steps after a kill of the row until the evolved Pekka carries the heal, with neither other
   * heal carried then.
   */
  private static int healDelay(Path folder, int first, int second, String row, String heal)
      throws IOException {
    Standard1v1Battle match =
        new Standard1v1Battle(withContextChain(folder, first, second), LEVEL, false);
    CharacterEntity[] units = facing(match, row);
    stepUntilDead(match, units[1]);
    int steps = stepsUntilCarried(match, units[0], heal);
    for (String other : new String[] {HEAL_MIN, HEAL_MED, HEAL_MAX}) {
      if (!other.equals(heal)) {
        assertThat(units[0].getBuffs().carries(other)).as(other).isFalse();
      }
    }
    return steps;
  }

  @Test
  @DisplayName(
      "a kill of a Knight whose sum stands on the first band and below the second gives the middle"
          + " heal once the soul has flown its flight time, not at the kill")
  void aKnightGivesTheMiddleHealAfterTheFlight(@TempDir Path folder) throws IOException {
    int sum = contextSum("Knight");

    assertThat(healDelay(folder, sum, sum + 1, "Knight", HEAL_MED))
        .isEqualTo(Shipped.ticks(FLIGHT_MS));
  }

  @Test
  @DisplayName("a kill of a Skeleton whose sum stands below the first band gives the least heal")
  void aSkeletonGivesTheLeastHeal(@TempDir Path folder) throws IOException {
    int sum = contextSum("Skeleton");

    assertThat(healDelay(folder, sum + 1, sum + 2, "Skeleton", HEAL_MIN))
        .isEqualTo(Shipped.ticks(FLIGHT_MS));
  }

  @Test
  @DisplayName(
      "a kill of the hero Knight counts its shield: its hit points alone at the writer's level are"
          + " below the second band, with its shield they are not, and the most heal follows")
  void theShieldCounts(@TempDir Path folder) throws IOException {
    int hitpoints =
        atContextLevel("KnightHero", Shipped.number(Shipped.unitRow("KnightHero"), "Hitpoints"));
    int shield = atContextLevel("KnightHero", HERO_SHIELD);
    Standard1v1Battle match =
        new Standard1v1Battle(
            withContextChain(folder, hitpoints, hitpoints + shield), LEVEL, false);
    CharacterEntity[] units = facing(match, "KnightHero");
    assertThat(units[1].contextHitpoints(CONTEXT_LEVEL_INDEX)).containsExactly(hitpoints, shield);
    stepUntilDead(match, units[1]);

    assertThat(stepsUntilCarried(match, units[0], HEAL_MAX)).isEqualTo(Shipped.ticks(FLIGHT_MS));
    assertThat(units[0].getBuffs().carries(HEAL_MED)).isFalse();
    assertThat(units[0].getBuffs().carries(HEAL_MIN)).isFalse();
  }
}
