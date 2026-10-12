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
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An interval's MaxRepetitions: the run counts the times its counter ran out, and once the count
 * has reached MaxRepetitions the run ends on its next update, before its counter moves. Without the
 * column it never ends on its own; 0 ends it on its first update, before it ever fires.
 *
 * <p>In each scene the altered tables give the Mini Pekka a starting interval of 100 ms, starting
 * at 100 ms, that adds one to TestVariable on the Mini Pekka each time it fires. The battle runs
 * for 3 seconds, long enough for 30 firings.
 */
class BattleIntervalRepetitionsTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String START = "Test_repeat_start";

  private static final String COUNT = "Test_repeat_count";

  /** The steps the battle runs: 3 seconds. */
  private static final int STEPS = 60;

  /** An action row of a class, its fields to fill. */
  private static ObjectNode action(ObjectNode rows, String name, String type) {
    ObjectNode row = rows.putObject(name);
    row.put("class", "Logic" + type + "Data");
    row.put("ClassType", type);
    ObjectNode fields = row.putObject("fields");
    fields.put("ClassType", type);
    return fields;
  }

  /**
   * The configured tables with the Mini Pekka's counting interval.
   *
   * @param maxRepetitions the interval's MaxRepetitions, or null to leave the column out
   */
  private static GameTables countingInterval(Path folder, Integer maxRepetitions)
      throws IOException {
    GameData.altered(
        folder,
        "actions",
        actions -> {
          ObjectNode interval = action(actions, START, "ActionInterval");
          interval.put("Interval", 100);
          interval.put("StartCounterAt", 100);
          if (maxRepetitions != null) {
            interval.put("MaxRepetitions", maxRepetitions);
          }
          interval.putObject("ActionToExecute").put("action", COUNT);
          ObjectNode count = action(actions, COUNT, "ActionSetVariable");
          count.put("Variable", GameData.TEST_VARIABLE);
          count.put("Value", GameData.TEST_VARIABLE + " + 1");
        });
    GameData.alterLoaded(
        folder,
        "characters",
        characters -> GameData.columns(characters, "MiniPekka").put("OnStartingAction", START));
    GameData.addTestVariable(folder);
    return GameTables.load(folder);
  }

  /** Plays one Mini Pekka for the bottom side and answers how often its interval fired. */
  private static int firings(GameTables tables) {
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    battle.play(1, battle.getWorld().getRecords().card("MiniPekka"), LEVEL, 0, 3500, 8000, "mp");
    while (battle.getBattle().getTick() <= STEPS) {
      battle.getBattle().step();
    }
    CharacterEntity miniPekka =
        battle.getWorld().getHolder().entities().stream()
            .filter(CharacterEntity.class::isInstance)
            .map(CharacterEntity.class::cast)
            .filter(unit -> unit.getData().name().equals("MiniPekka"))
            .findFirst()
            .orElseThrow();
    return miniPekka.variable(battle.getWorld().variableKey(GameData.TEST_VARIABLE));
  }

  @Test
  @DisplayName("MaxRepetitions 2 ends the run after its second firing")
  void theRunEndsAfterItsRepetitions(@TempDir Path folder) throws IOException {
    assertThat(firings(countingInterval(folder, 2))).isEqualTo(2);
  }

  @Test
  @DisplayName("MaxRepetitions 0 ends the run before it ever fires")
  void zeroRepetitionsNeverFire(@TempDir Path folder) throws IOException {
    assertThat(firings(countingInterval(folder, 0))).isZero();
  }

  @Test
  @DisplayName("without MaxRepetitions the run keeps firing every interval")
  void withoutTheColumnTheRunNeverEnds(@TempDir Path folder) throws IOException {
    assertThat(firings(countingInterval(folder, null))).isGreaterThan(10);
  }
}
