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
import org.crforge.core.battle.data.GameVersions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When an area effect's life ends. The game of data version 16.402.18 keeps every area effect, a
 * row with hit switches as well as one with a filter, until its countdown is below 0: one whose
 * countdown reaches 0 exactly has one more update, and its life-end action waits for that update.
 * The game of 14.593.1 removes a row with hit switches at the cleanup that finds its countdown
 * below 1.
 *
 * <p>The scene: the bottom side's Rage Barbarian walks up the left lane to the top side's princess
 * tower and dies to it on tick 236. Its death area effect, RageBarbarianDummyForSpawn, lives 50 ms
 * and throws the bottle; the bottle's BarbarianRage lives 5500 ms. Both rows have hit switches in
 * the configured tables. The same battle runs on the configured tables and on those tables
 * relabelled as data version 16.402.18, which differ only in the version's rule.
 */
class BattleAreaEffectLifeTest {

  /** The battle stream's seed of the scene. */
  private static final int SEED = 1;

  /** The level the Rage Barbarian is played at, against towers at their first level. */
  private static final int LEVEL = 1;

  /** The tick the tower kills the Rage Barbarian, which makes its death area effect. */
  private static final int DEATH = 236;

  /** The death area effect's row. */
  private static final String DEATH_AREA = "RageBarbarianDummyForSpawn";

  /** The area effect the bottle makes. */
  private static final String RAGE = "BarbarianRage";

  /** The tick the scene runs to, after the rage has left on either version. */
  private static final int END = 400;

  @TempDir Path folder;

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
   * @return what it saw of each area effect, by its row's name
   */
  private static Map<String, Life> scene(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, 1, true);
    match.getWorld().seed(SEED);
    match.play(20, GameData.card("RageBarbarian"), LEVEL, 0, 3500, 20000, "R");
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
      "on data version 16.402.18 an area effect whose countdown reaches 0 has one more update and"
          + " leaves once its countdown is below 0")
  void anAreaEffectStaysUntilItsCountdownIsBelowZero() throws IOException {
    Map<String, Life> lives = scene(GameData.relabelled(folder, GameVersions.DATA_16_402_18));

    Life death = lives.get(DEATH_AREA);
    assertThat(death.created).isEqualTo(DEATH);
    assertThat(death.countdowns).as("its updates").containsExactly(0, -50);
    assertThat(death.listed).as("listed after").containsExactly(DEATH + 1, DEATH + 2);

    Life rage = lives.get(RAGE);
    assertThat(rage.countdowns).as("5500 ms of life: 111 updates").hasSize(111);
    assertThat(rage.countdowns.get(110)).isEqualTo(-50);
    assertThat(rage.listed).hasSize(111);
  }

  @Test
  @DisplayName(
      "on data version 14.593.1 an area effect with hit switches leaves at the cleanup after the"
          + " update that brings its countdown to 0")
  void anAreaEffectLeavesAtZeroOnTheOlderVersion() {
    assertThat(GameData.tables().version()).isEqualTo(GameVersions.DATA_14_593_1);
    Map<String, Life> lives = scene(GameData.tables());

    Life death = lives.get(DEATH_AREA);
    assertThat(death.created).isEqualTo(DEATH);
    assertThat(death.countdowns).as("its update").containsExactly(0);
    assertThat(death.listed).as("listed after").containsExactly(DEATH + 1);

    Life rage = lives.get(RAGE);
    assertThat(rage.countdowns).as("5500 ms of life: 110 updates").hasSize(110);
    assertThat(rage.countdowns.get(109)).isZero();
    assertThat(rage.listed).hasSize(110);
  }
}
