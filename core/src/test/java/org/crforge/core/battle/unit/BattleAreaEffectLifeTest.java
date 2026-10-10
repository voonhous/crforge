/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When an area effect's life ends. The game keeps every area effect, a row with hit switches as
 * well as one with a filter, until its countdown is below 0: one whose countdown reaches 0 exactly
 * has one more update, and its life-end action waits for that update.
 *
 * <p>The scene: the bottom side's Rage Barbarian walks up the left lane to the top side's princess
 * tower and dies to it. Its death area effect, RageBarbarianDummyForSpawn, written to live 50 ms,
 * throws the bottle; the bottle's BarbarianRage is written to live 5500 ms. Both rows have hit
 * switches.
 */
class BattleAreaEffectLifeTest {

  /** The battle stream's seed of the scene. */
  private static final int SEED = 1;

  /** The level the Rage Barbarian is played at, against towers at their first level. */
  private static final int LEVEL = 1;

  /** The death area effect's row. */
  private static final String DEATH_AREA = "RageBarbarianDummyForSpawn";

  /** The area effect the bottle makes. */
  private static final String RAGE = "BarbarianRage";

  /** The tick the scene runs to, after the rage has left on either version. */
  private static final int END = 400;

  /**
   * The tables with the two area effects' lives written as the scene counts on them, and the card
   * summoning one Rage Barbarian.
   */
  private static GameTables lives(Path folder) throws IOException {
    GameData.altered(
        folder,
        "area_effect_objects",
        rows -> {
          GameData.columns(rows, DEATH_AREA).put("LifeDuration", 50);
          GameData.columns(rows, RAGE).put("LifeDuration", 5500);
        });
    GameData.alterLoaded(
        folder,
        "spells_characters",
        rows -> GameData.columns(rows, "RageBarbarian").put("SummonNumber", 1));
    return GameTables.load(folder);
  }

  /** What the scene saw of one area effect. */
  private static final class Life {

    /** The tick it was made. */
    int created;

    /** The countdown after each update, in order. */
    final List<Integer> countdowns = new ArrayList<>();

    /** The battle's tick counter after each step that left it listed. */
    final List<Integer> listed = new ArrayList<>();
  }

  /**
   * Runs the scene: the Rage Barbarian played on tick 20 on the left lane, the towers fighting.
   *
   * @param removals collects the tick of the step whose cleanup removed the Rage Barbarian, the
   *     tick it died on
   * @return what it saw of each area effect, by its row's name
   */
  private static Map<String, Life> scene(GameTables tables, List<Integer> removals) {
    Standard1v1Battle match = new Standard1v1Battle(tables, 1, true);
    match.getWorld().seed(SEED);
    match.play(20, match.getWorld().getRecords().card("RageBarbarian"), LEVEL, 0, 3500, 20000, "R");
    Map<String, Life> lives = new LinkedHashMap<>();
    Map<Integer, Life> byId = new LinkedHashMap<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaEffectCreated(
                  int tick, AreaEffectEntity areaEffect, String how, String source) {
                Life life = new Life();
                life.created = tick;
                lives.put(areaEffect.getData().name(), life);
                byId.put(areaEffect.getId(), life);
              }

              @Override
              public void entityRemoved(int tick, WorldEntity removed) {
                if (removed.getData().name().equals("RageBarbarian")) {
                  removals.add(tick);
                }
              }

              @Override
              public void areaEffectUpdated(
                  int tick,
                  AreaEffectEntity areaEffect,
                  int before,
                  int after,
                  int hits,
                  int radius,
                  List<Integer> damages) {
                byId.get(areaEffect.getId()).countdowns.add(after);
              }
            });
    while (match.getBattle().getTick() < END) {
      match.getBattle().step();
      for (Map.Entry<Integer, Life> entry : byId.entrySet()) {
        if (match.getWorld().liveObject(entry.getKey()) != null) {
          entry.getValue().listed.add(match.getBattle().getTick());
        }
      }
    }
    return lives;
  }

  @Test
  @DisplayName(
      "an area effect whose countdown reaches 0 has one more update and"
          + " leaves once its countdown is below 0")
  void anAreaEffectStaysUntilItsCountdownIsBelowZero(@TempDir Path folder) throws IOException {
    List<Integer> removals = new ArrayList<>();
    Map<String, Life> lives = scene(lives(folder), removals);
    assertThat(removals).as("the tower kills the Rage Barbarian").isNotEmpty();
    int died = removals.get(0);

    Life death = lives.get(DEATH_AREA);
    assertThat(death.created).as("made on the tick it dies").isEqualTo(died);
    assertThat(death.countdowns).as("its updates").containsExactly(0, -50);
    assertThat(death.listed).as("listed after").containsExactly(died + 1, died + 2);

    Life rage = lives.get(RAGE);
    assertThat(rage.countdowns).as("5500 ms of life: 111 updates").hasSize(111);
    assertThat(rage.countdowns.get(110)).isEqualTo(-50);
    assertThat(rage.listed).hasSize(111);
  }
}
