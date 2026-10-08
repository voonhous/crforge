package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A walking unit's own spawner: when its timer steps, and what holds it. */
class UnitSpawnerTest {

  private static final int LEVEL_11 = 10;

  @TempDir static Path tablesFolder;

  /**
   * The configured tables with the Witch's deploy and spawner written (a deploy of 1000 ms, a first
   * wave 1000 ms after it, waves of four 7000 ms apart) and ZapFreeze's spawn speed of -100.
   */
  private static GameTables tables;

  @BeforeAll
  static void writeTheRows() throws IOException {
    GameData.altered(
        tablesFolder,
        "characters",
        rows ->
            GameData.columns(rows, "Witch")
                .put("DeployTime", 1000)
                .put("SpawnStartTime", 1000)
                .put("SpawnNumber", 4)
                .put("SpawnPauseTime", 7000));
    GameData.alterLoaded(
        tablesFolder,
        "character_buffs",
        rows -> GameData.columns(rows, "ZapFreeze").put("SpawnSpeedMultiplier", -100));
    tables = GameTables.load(tablesFolder);
  }

  /**
   * A Witch placed for the bottom side with the towers passive, and the ticks its spawner fired.
   */
  private static final class Setup {
    final Standard1v1Battle match =
        new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL, false);
    final CharacterEntity witch =
        match.deploy(0, match.getWorld().getRecords().unit("Witch"), 11, 0, 3500, 10000);
    final List<Integer> fired = new ArrayList<>();

    Setup() {
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void spawnerFired(
                    int tick,
                    CharacterEntity spawner,
                    String row,
                    int count,
                    int radius,
                    int timerAfter,
                    int waveMade) {
                  fired.add(tick);
                }
              });
    }
  }

  @Test
  @DisplayName("a Witch's timer waits for its deploy to end, then fires its first wave on 38")
  void theFirstWaveComesAfterTheDeploy() {
    Setup s = new Setup();
    for (int tick = 0; tick <= 40; tick++) {
      s.match.getBattle().step();
    }
    // The deploy ends on 19; its 1000 ms start time is 20 visits of 50 ms after that.
    assertThat(s.fired).containsExactly(38);
  }

  @Test
  @DisplayName("a stun holds the timer: the wave comes as many ticks late as visits it held")
  void aStunHoldsTheTimer() {
    Setup s = new Setup();
    int held = 0;
    for (int tick = 0; tick <= 60; tick++) {
      if (tick == 25) {
        // ZapFreeze's spawn speed of -100 makes the spawn rate 0 for as long as it lasts.
        s.witch
            .getBuffs()
            .apply(s.match.getWorld().getRecords().buff("ZapFreeze"), 500, LEVEL_11, null, 1);
      }
      s.match.getBattle().step();
      // The state visit runs after the buffs' own pass, so what the rate is after the step is what
      // that step's spawner block took.
      if (tick > 19 && s.witch.getBuffs().spawnRate() == 0) {
        held++;
      }
    }
    assertThat(held).isPositive();
    assertThat(s.fired).containsExactly(38 + held);
  }
}
