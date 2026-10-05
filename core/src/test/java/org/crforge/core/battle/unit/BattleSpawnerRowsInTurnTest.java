package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.spawn.SpawnHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A spawner whose row sets a second, and a third, spawn character makes its waves of the rows in
 * turn: the first wave of SpawnCharacter, the next of SpawnCharacter2, then SpawnCharacter3 when
 * set, and back to the first. The turn moves on only as a wave is complete, together with the pause
 * before the next. Each scene alters the configured Witch, which makes waves of four Skeletons, to
 * set the further rows, with a pause of 1000 ms between its waves, and plays it alone on its own
 * side.
 */
class BattleSpawnerRowsInTurnTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The ticks the Witch is followed for: at least five of its waves. */
  private static final int TICKS = 300;

  /** One firing of the Witch's spawner: the row it made and how many. */
  private record Firing(String row, int count) {}

  /**
   * The Witch's row with a pause of 1000 ms between its waves, and altered as asked, in a copy of
   * the configured tables.
   */
  private static GameTables witch(Path folder, Consumer<ObjectNode> edit) throws IOException {
    return GameData.altered(
        folder,
        "characters",
        rows -> {
          ObjectNode columns = GameData.columns(rows, "Witch");
          columns.put("SpawnPauseTime", 1000);
          edit.accept(columns);
        });
  }

  /** Plays the Witch on side 0's half and records each firing and the row of each child. */
  private static List<Firing> firings(GameTables tables, List<String> children) {
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    List<Firing> out = new ArrayList<>();
    battle
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
                out.add(new Firing(row, count));
              }

              @Override
              public void characterSpawned(
                  int tick, SpawnHost source, CharacterEntity child, int createdX, int createdY) {
                if (source instanceof CharacterEntity c && c.name().equals("Witch_0")) {
                  children.add(child.getData().name());
                }
              }
            });
    battle.play(1, battle.getWorld().getRecords().card("Witch"), LEVEL, 0, 9000, 8000, "Witch");
    while (battle.getBattle().getTick() <= TICKS) {
      battle.getBattle().step();
    }
    return out;
  }

  @Test
  @DisplayName("with a second row the waves are of the first and the second row in turn")
  void twoRowsInTurn(@TempDir Path folder) throws IOException {
    GameTables tables = witch(folder, columns -> columns.put("SpawnCharacter2", "Bat"));
    List<String> children = new ArrayList<>();

    List<Firing> firings = firings(tables, children);

    assertThat(firings)
        .startsWith(
            new Firing("Skeleton", 4),
            new Firing("Bat", 4),
            new Firing("Skeleton", 4),
            new Firing("Bat", 4));
    assertThat(children)
        .startsWith(
            "Skeleton", "Skeleton", "Skeleton", "Skeleton", "Bat", "Bat", "Bat", "Bat", "Skeleton");
  }

  @Test
  @DisplayName("with a third row the waves go through all three and back to the first")
  void threeRowsInTurn(@TempDir Path folder) throws IOException {
    GameTables tables =
        witch(
            folder,
            columns -> columns.put("SpawnCharacter2", "Bat").put("SpawnCharacter3", "Goblin"));
    List<String> children = new ArrayList<>();

    List<Firing> firings = firings(tables, children);

    assertThat(firings)
        .startsWith(
            new Firing("Skeleton", 4),
            new Firing("Bat", 4),
            new Firing("Goblin", 4),
            new Firing("Skeleton", 4));
  }

  @Test
  @DisplayName("the timing of the waves is the one-row spawner's")
  void theTimingIsUnchanged(@TempDir Path folder) throws IOException {
    Files.createDirectories(folder.resolve("plain"));
    Files.createDirectories(folder.resolve("turn"));
    GameTables plain = witch(folder.resolve("plain"), columns -> {});
    GameTables tables =
        witch(folder.resolve("turn"), columns -> columns.put("SpawnCharacter2", "Bat"));
    List<Integer> plainTicks = new ArrayList<>();
    List<Integer> turnTicks = new ArrayList<>();

    fireTicks(plain, plainTicks);
    fireTicks(tables, turnTicks);

    assertThat(plainTicks).hasSizeGreaterThanOrEqualTo(4).isEqualTo(turnTicks);
  }

  /** The ticks the Witch's spawner fires on. */
  private static void fireTicks(GameTables tables, List<Integer> ticks) {
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    battle
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
                ticks.add(tick);
              }
            });
    battle.play(1, battle.getWorld().getRecords().card("Witch"), LEVEL, 0, 9000, 8000, "Witch");
    while (battle.getBattle().getTick() <= TICKS) {
      battle.getBattle().step();
    }
  }
}
