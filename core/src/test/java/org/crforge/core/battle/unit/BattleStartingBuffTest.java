package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.spawn.SpawnHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A unit whose row sets a StartingBuff takes it as it is created, from itself, at its level and for
 * its side, for its row's StartingBuffTime: the level setter's tail, ahead of a buff while it is
 * not attacking. The evolved Witch's healing skeleton of data version 16.402.18 is the one shipped
 * row that sets it. Each scene alters the configured Skeleton, which the Witch's spawner makes, to
 * set one.
 */
class BattleStartingBuffTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The skeletons the Witch's row makes a wave of, written into it. */
  private static final int WAVE = 4;

  /**
   * The configured tables with the Skeleton's row setting a StartingBuff for a time, and the
   * Witch's spawner written: waves of four Skeletons, the first 1000 ms after a deploy of 1000 ms,
   * then one every 7000 ms, so two waves in the first 200 ticks.
   */
  private static GameTables skeletonStartingWith(Path folder, String buff, int timeMs)
      throws IOException {
    return GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "Skeleton")
              .put("StartingBuff", buff)
              .put("StartingBuffTime", timeMs);
          GameData.columns(rows, "Witch")
              .put("SpawnCharacter", "Skeleton")
              .put("SpawnNumber", WAVE)
              .put("DeployTime", 1000)
              .put("SpawnStartTime", 1000)
              .put("SpawnPauseTime", 7000);
        });
  }

  /** The skeletons a Witch for the bottom side makes, as each is made. */
  private static List<CharacterEntity> skeletonsOf(Standard1v1Battle match, CharacterEntity witch) {
    List<CharacterEntity> made = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void characterSpawned(
                  int tick, SpawnHost source, CharacterEntity child, int x, int y) {
                if (source == witch) {
                  made.add(child);
                }
              }
            });
    return made;
  }

  @Test
  @DisplayName(
      "a spawned skeleton whose row sets a StartingBuff carries it as it is made, from itself, for"
          + " its StartingBuffTime, and loses it as that runs out")
  void aSpawnedUnitTakesItsStartingBuff(@TempDir Path folder) throws IOException {
    GameTables tables = skeletonStartingWith(folder, "Rage", 2000);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    UnitData witchRow = match.getWorld().getRecords().unit("Witch");
    CharacterEntity witch = match.deploy(0, witchRow, LEVEL, 0, 3500, 10000, "Witch");
    List<CharacterEntity> skeletons = skeletonsOf(match, witch);
    List<String> atCreation = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void characterSpawned(
                  int tick, SpawnHost source, CharacterEntity child, int x, int y) {
                for (BuffInstance instance : child.getBuffs().items()) {
                  atCreation.add(
                      "%s %d/%d from %s side %d parent %s"
                          .formatted(
                              instance.getBuff().name(),
                              instance.getRemaining(),
                              instance.getTotal(),
                              instance.getSource() == child ? "itself" : instance.getSource(),
                              instance.getSide(),
                              instance.getParent()));
                }
              }
            });
    List<Integer> carried = new ArrayList<>();
    for (int tick = 0; tick < 200; tick++) {
      match.getBattle().step();
      if (!skeletons.isEmpty() && skeletons.get(0).getBuffs().carries("Rage")) {
        carried.add(tick);
      }
    }

    assertThat(skeletons).as("the Witch makes her skeletons, four at a time").hasSize(2 * WAVE);
    assertThat(atCreation)
        .as(
            "each skeleton lists its StartingBuff, alone, as it is made; its registration visit has"
                + " counted the first 50 ms by the time the spawn is told")
        .hasSize(2 * WAVE)
        .containsOnly("Rage 1950/2000 from itself side 0 parent null");
    assertThat(carried).as("the first skeleton carries it, and then no longer").isNotEmpty();
    assertThat(carried.get(carried.size() - 1) - carried.get(0) + 1)
        .as(
            "2000 ms is 40 visits, the registration visit the first; the 40th removes it, so it"
                + " is carried after 39 steps")
        .isEqualTo(2000 / 50 - 1);
  }

  @Test
  @DisplayName(
      "a unit whose row sets a StartingBuff dies, and its death takes off every instance of that"
          + " buff whose parent it is, and no other")
  void itsDeathRemovesTheInstancesItIsTheParentOf(@TempDir Path folder) throws IOException {
    // A buff that stacks keeps the parent it is applied with.
    GameTables tables = skeletonStartingWith(folder, "RageModeRage", 2000);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    BattleWorld world = match.getWorld();
    CharacterEntity skeleton =
        match.deploy(0, world.getRecords().unit("Skeleton"), LEVEL, 0, 3500, 10000, "Skeleton");
    CharacterEntity knight =
        match.deploy(0, world.getRecords().unit("Knight"), LEVEL, 0, 14500, 10000, "Knight");
    CharacterEntity other =
        match.deploy(0, world.getRecords().unit("Knight"), LEVEL, 1, 14500, 22000, "Other");
    for (int tick = 0; tick < 5; tick++) {
      match.getBattle().step();
    }
    // The skeleton is the parent of one instance on each Knight; the second Knight also lists one
    // of its own, with no parent.
    world.spawnBuff(knight, "test", "RageModeRage", 100_000, skeleton, skeleton);
    world.spawnBuff(other, "test", "RageModeRage", 100_000, skeleton, skeleton);
    world.spawnBuff(other, "test", "RageModeRage", 100_000, other, null);
    assertThat(knight.getBuffs().items()).hasSize(1);
    assertThat(other.getBuffs().items()).hasSize(2);

    world.kill(skeleton, null);
    // The kill lands at the next step's damage drain.
    match.getBattle().step();

    assertThat(knight.getBuffs().items())
        .as("the instance the skeleton is the parent of goes at its death")
        .isEmpty();
    assertThat(other.getBuffs().items())
        .as("on every carrier, and an instance without a parent stays")
        .extracting(instance -> instance.getBuff().name() + " parent " + instance.getParent())
        .containsExactly("RageModeRage parent null");
  }

  @Test
  @DisplayName(
      "a card play of a unit whose row sets a StartingBuff, which it applies again, is refused")
  void aPlayedUnitWithAStartingBuffIsRefused(@TempDir Path folder) throws IOException {
    GameTables tables =
        GameData.altered(
            folder,
            "characters",
            rows ->
                GameData.columns(rows, "Knight")
                    .put("StartingBuff", "Rage")
                    .put("StartingBuffTime", 1000));
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);

    assertThatThrownBy(
            () -> {
              match.play(
                  1, match.getWorld().getRecords().card("Knight"), LEVEL, 0, 3500, 10000, "Blue");
              for (int tick = 0; tick < 5; tick++) {
                match.getBattle().step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(
            "Knight is played with a StartingBuff, which the play applies again, not modelled");
  }
}
