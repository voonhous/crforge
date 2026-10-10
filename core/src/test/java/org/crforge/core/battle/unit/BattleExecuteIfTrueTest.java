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
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The start gate (ExecuteIfTrue) of a soul's flight, an uppercut and a relative warp: each is asked
 * as the action starts, as for every other class, and a false answer starts nothing. The shipped
 * rows these classes have here set no gate, so each test gives one the gate it is asked.
 */
class BattleExecuteIfTrueTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Witch_Soul_Drain's ConstantFlightDuration, written into its row. */
  private static final int FLIGHT_MS = 1000;

  /** {@link #FLIGHT_MS} in ticks. */
  private static final int FLIGHT_TICKS = FLIGHT_MS / 50;

  /** BossBandit_ability_warp's WarpY, written into its row: down the arena for the bottom side. */
  private static final int WARP_Y = -6000;

  /**
   * The tables with the row's ExecuteIfTrue set to the given expression, the soul's flight time and
   * the warp's distance written.
   */
  private static GameTables gated(Path folder, String row, String gate) throws IOException {
    return GameData.altered(
        folder,
        "actions",
        rows -> {
          ((ObjectNode) rows.get("Witch_Soul_Drain").get("fields"))
              .put("ConstantFlightDuration", FLIGHT_MS);
          ((ObjectNode) rows.get("BossBandit_ability_warp").get("fields")).put("WarpY", WARP_Y);
          ((ObjectNode) rows.get(row).get("fields")).put("ExecuteIfTrue", gate);
        });
  }

  @ParameterizedTest(name = "ExecuteIfTrue {0}")
  @CsvSource({"0, false", "1, true"})
  @DisplayName(
      "a soul's flight starts, and its action later heals the evolved Witch, only past its gate")
  void aSoulFlightAsksItsGate(String gate, boolean heals, @TempDir Path folder) throws IOException {
    GameTables tables = gated(folder, "Witch_Soul_Drain", gate);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity witch =
        match.deploy(0, match.getWorld().getRecords().unit("Witch_EV1"), LEVEL, 0, 14500, 8000);
    match.getBattle().step();
    BattleWorld world = match.getWorld();
    int before = witch.getHitPoints().getHitPoints();

    witch.actionHolder().start(world.getActions().build("Witch_Soul_Drain", world.binding(witch)));
    for (int i = 0; i <= FLIGHT_TICKS + 1; i++) {
      match.getBattle().step();
    }

    assertThat(witch.getHitPoints().getHitPoints() > before)
        .as("the soul's arrival heals her")
        .isEqualTo(heals);
  }

  @ParameterizedTest(name = "ExecuteIfTrue {0}")
  @CsvSource({"0, false", "1, true"})
  @DisplayName("an uppercut starts, holding its unit, only past its gate")
  void anUppercutAsksItsGate(String gate, boolean starts, @TempDir Path folder) throws IOException {
    GameTables tables = gated(folder, "MegaKnight_EV1_uppercut", gate);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<String> started = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void uppercutStarted(
                  int tick,
                  CharacterEntity unit,
                  String action,
                  int phase,
                  WorldEntity instigator,
                  WorldEntity target,
                  boolean finished) {
                started.add(action);
              }
            });
    CharacterEntity mk =
        match.deploy(
            0, match.getWorld().getRecords().unit("MegaKnight_EV1"), LEVEL, 0, 3500, 23500, "mk");
    for (int i = 0; i < 40; i++) {
      match.getBattle().step();
    }
    BattleWorld world = match.getWorld();
    TowerEntity tower = world.princessTowers(1).get(0);
    mk.getUnit().targeting().setReference(tower.getTargetView());
    // Its own attacks may have started an uppercut already; only the one started here is counted.
    started.clear();

    mk.actionHolder()
        .start(
            world.getActions().build("MegaKnight_EV1_uppercut", world.binding(mk)),
            tower.actionHolder());

    assertThat(started).as("the uppercut's start").hasSize(starts ? 1 : 0);
  }

  @ParameterizedTest(name = "ExecuteIfTrue {0}")
  @CsvSource({"0, false", "1, true"})
  @DisplayName("a relative warp moves its unit only past its gate")
  void aRelativeWarpAsksItsGate(String gate, boolean warps, @TempDir Path folder)
      throws IOException {
    GameTables tables = gated(folder, "BossBandit_ability_warp", gate);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity bandit =
        match.deploy(
            0,
            match.getWorld().getRecords().unit("BossBandit"),
            LEVEL,
            0,
            3500,
            12000,
            "BossBandit");
    match.getBattle().step();
    BattleWorld world = match.getWorld();

    bandit
        .actionHolder()
        .start(world.getActions().build("BossBandit_ability_warp", world.binding(bandit)));

    assertThat(new int[] {bandit.getView().getX(), bandit.getView().getY()})
        .containsExactly(3500, warps ? 12000 + WARP_Y : 12000);
  }
}
