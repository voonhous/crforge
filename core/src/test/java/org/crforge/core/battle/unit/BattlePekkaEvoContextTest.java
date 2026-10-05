package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * A newer data version's evolved Pekka: its killed-done action is a group that creates a context,
 * writes the killed unit's hit points and shield hit points at card level 11 into it, and sends a
 * soul flying for 700 ms; the group that runs as the soul arrives inherits the context, and its
 * select picks the heal by the sum the context holds (below 990, below 1990, or more).
 */
class BattlePekkaEvoContextTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The configured tables with the newer version's killed-done chain of the evolved Pekka. */
  private static GameTables withContextChain(Path folder) throws IOException {
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
                    f.put("HitpointsLevelIndex", 10);
                    f.put("ShieldHitpointsKey", "victim_shield_hp");
                  }));
          rows.set(
              "PekkaEV1_SoulDrain",
              action(
                  "LogicActionSoulDrainData",
                  "ActionSoulDrain",
                  f -> {
                    f.putObject("ActionOnTargetReached").put("action", "PekkaEV1_SoulArrived");
                    f.put("ConstantFlightDuration", 700);
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
          ObjectNode heal = (ObjectNode) rows.get("PekkaEV1_Heal").get("fields");
          ArrayNode conditions = heal.putArray("PerActionConditions");
          conditions.add("as_int(#victim_hp, 0) + as_int(#victim_shield_hp, 0) < 990");
          conditions.add("as_int(#victim_hp, 0) + as_int(#victim_shield_hp, 0) < 1990");
        });
    ObjectMapper mapper = new ObjectMapper();
    Path file = folder.resolve("characters.json");
    ObjectNode document = (ObjectNode) mapper.readTree(file.toFile());
    GameData.columns((ObjectNode) document.get("rows"), "Pekka_EV1")
        .put("OnKilledDoneAction", "PekkaEV1_OnKill");
    mapper.writeValue(file.toFile(), document);
    return GameTables.load(folder);
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

  /** The heal the evolved Pekka is given for a kill of the row, after the soul's flight. */
  private static int healDelay(Path folder, String row, String heal, String... others)
      throws IOException {
    Standard1v1Battle match = new Standard1v1Battle(withContextChain(folder), LEVEL, false);
    CharacterEntity[] units = facing(match, row);
    stepUntilDead(match, units[1]);
    int steps = stepsUntilCarried(match, units[0], heal);
    for (String other : others) {
      assertThat(units[0].getBuffs().carries(other)).as(other).isFalse();
    }
    return steps;
  }

  @Test
  @DisplayName(
      "a kill of a Knight (1766 at level 11) gives the middle heal once the soul has flown its"
          + " 700 ms, not at the kill")
  void aKnightGivesTheMiddleHealAfterTheFlight(@TempDir Path folder) throws IOException {
    int steps = healDelay(folder, "Knight", "PekkaEV1_HealMed", "PekkaEV1_HealMin");

    assertThat(steps).isGreaterThanOrEqualTo(14).isLessThan(20);
  }

  @Test
  @DisplayName("a kill of a Skeleton (81 at level 11) gives the least heal")
  void aSkeletonGivesTheLeastHeal(@TempDir Path folder) throws IOException {
    assertThat(healDelay(folder, "Skeleton", "PekkaEV1_HealMin", "PekkaEV1_HealMed"))
        .isGreaterThanOrEqualTo(14);
  }

  @Test
  @DisplayName(
      "a kill of the hero Knight counts its shield: its hit points alone at level 11 are below"
          + " 1990, with its shield they are not, and the most heal follows")
  void theShieldCounts(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = new Standard1v1Battle(withContextChain(folder), LEVEL, false);
    CharacterEntity[] units = facing(match, "KnightHero");
    int[] values = units[1].contextHitpoints(10);
    assertThat(values[0]).isLessThan(1990);
    assertThat(values[0] + values[1]).isGreaterThanOrEqualTo(1990);
    stepUntilDead(match, units[1]);

    assertThat(stepsUntilCarried(match, units[0], "PekkaEV1_HealMax")).isGreaterThanOrEqualTo(14);
    assertThat(units[0].getBuffs().carries("PekkaEV1_HealMed")).isFalse();
  }
}
