package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.GoblinHutLifeState;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.spawn.SpawnHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Goblin Hut's life state where its runs do not take it, each held to the spawns the standard
 * game makes: a target already in reach as the run starts, one east of the hut, a fan of three, the
 * edge of its reach, a stun that holds its timer, and a spawn due on the tick the hut dies; and,
 * worked from the record as no native case runs it, a rage that quickens the timer. The hut deploys
 * at (10500, 12500) for the bottom side; the units it finds stand still where they are put.
 */
class BattleGoblinHutTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String HUT = "GoblinHut_Rework";

  private static final String DUMMY = "SpearGoblin_Dummy";

  /** One battle with every spawn and the life state's words after each step logged. */
  private static final class Scene {
    final Standard1v1Battle match;
    final List<String> spawns = new ArrayList<>();
    final Map<Integer, GoblinHutLifeState.Memory> words = new HashMap<>();
    final CharacterEntity hut;
    int tick;

    Scene(GameTables tables) {
      this(tables, HUT);
    }

    Scene(GameTables tables, String hutRow) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void characterSpawned(
                    int t, SpawnHost source, CharacterEntity child, int x, int y) {
                  spawns.add(tick + " " + child.getData().name() + " " + x + " " + y);
                }

                @Override
                public void goblinHutLogged(
                    int t, CharacterEntity hut, GoblinHutLifeState.Event event) {
                  if (event instanceof GoblinHutLifeState.Stepped s) {
                    words.put(tick, s.after());
                  }
                }
              });
      hut = match.deploy(0, records().unit(hutRow), LEVEL, 0, 10500, 12500, "H");
    }

    BattleRecords records() {
      return match.getWorld().getRecords();
    }

    /** Places a unit on tick 0 that stands still where it is put. */
    CharacterEntity still(int side, String row, int x, int y, String name) {
      CharacterEntity unit = match.deploy(0, records().unit(row), LEVEL, side, x, y, name);
      unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      return unit;
    }

    /** Steps the battle up to and including the given tick. */
    void stepThrough(int last) {
      while (tick <= last) {
        match.getBattle().step();
        tick++;
      }
    }
  }

  @Test
  @DisplayName(
      "a target in reach as the run starts is spawned at once, then every 42 run passes, the"
          + " start's own counting, turned each way in turn")
  void aTargetAtTheStart() {
    Scene scene = new Scene(GameData.tables());
    scene.still(1, "Knight", 3500, 13000, "K");
    scene.stepThrough(110);
    assertThat(scene.spawns)
        .containsExactly(
            "20 " + DUMMY + " 9347 12171",
            "61 " + DUMMY + " 9405 12988",
            "103 " + DUMMY + " 9347 12171");
  }

  @Test
  @DisplayName(
      "of three in reach the nearest is taken, and of two equally near the one the index lists"
          + " first")
  void theNearestIsTaken() {
    Scene scene = new Scene(GameData.tables());
    scene.still(1, "Knight", 4000, 12500, "K");
    scene.still(1, "Knight", 10500, 6500, "K2");
    scene.still(1, "Knight", 10500, 18500, "K3");
    scene.stepThrough(24);
    // Toward the south Knight, which the bucket walk lists before the north one.
    assertThat(scene.spawns).containsExactly("20 " + DUMMY + " 10910 11373");
  }

  @Test
  @DisplayName("a flying unit in reach is found")
  void aFlyerIsFound() {
    Scene scene = new Scene(GameData.tables());
    scene.still(1, "Minion", 4500, 13500, "M");
    scene.stepThrough(24);
    assertThat(scene.spawns).containsExactly("20 " + DUMMY + " 9322 12281");
  }

  @Test
  @DisplayName(
      "a target that leaves the keep reach and comes back is taken again without a spawn, and the"
          + " next spawn comes when it would have")
  void outAndBack() {
    Scene scene = new Scene(GameData.tables());
    CharacterEntity knight = scene.still(1, "Knight", 3500, 13000, "K");
    scene.stepThrough(29);
    knight.getView().setX(3000);
    scene.stepThrough(49);
    assertThat(scene.words.get(49).lost()).isTrue();
    knight.getView().setX(3500);
    scene.stepThrough(50);
    assertThat(scene.words.get(50).lost()).as("found again").isFalse();
    assertThat(scene.words.get(50).target()).isEqualTo(knight.getId());
    scene.stepThrough(110);
    assertThat(scene.spawns)
        .containsExactly(
            "20 " + DUMMY + " 9347 12171",
            "61 " + DUMMY + " 9405 12988",
            "103 " + DUMMY + " 9347 12171");
  }

  @Test
  @DisplayName(
      "a target killed between ticks marks the run lost as it leaves; a unit moved into reach is"
          + " taken without a spawn, and the spawns come on time")
  void aTargetThatLeaves() {
    Scene scene = new Scene(GameData.tables());
    CharacterEntity knight = scene.still(1, "Knight", 3500, 13000, "K");
    CharacterEntity other = scene.still(1, "Knight", 3500, 20000, "K2");
    scene.stepThrough(40);
    scene.match.getWorld().kill(knight, null);
    scene.stepThrough(41);
    assertThat(scene.words.get(41).lost()).isTrue();
    assertThat(scene.words.get(41).target()).as("its id kept").isEqualTo(knight.getId());
    scene.stepThrough(59);
    other.getView().setX(3600);
    other.getView().setY(12000);
    scene.stepThrough(110);
    assertThat(scene.spawns)
        .filteredOn(line -> line.contains(DUMMY))
        .containsExactly(
            "20 " + DUMMY + " 9347 12171",
            "61 " + DUMMY + " 9348 12827",
            "103 " + DUMMY + " 9406 12011");
  }

  @Test
  @DisplayName(
      "a rage on the hut steps its timer by 65, what the buff's spawn speed makes of 50, and a spawn"
          + " carries what is left over the interval")
  void aRageQuickensTheSpawns() {
    Scene scene = new Scene(GameData.tables());
    scene.still(1, "Knight", 3500, 13000, "K");
    scene.stepThrough(0);
    scene.hut.spawnBuff("rage", "Rage", 100_000, scene.hut);
    scene.stepThrough(90);
    // Worked from the record: 33 steps of 65 reach the interval of 2100 with 45 over, and 32 more
    // reach it again.
    assertThat(scene.spawns)
        .extracting(line -> line.split(" ")[0])
        .containsExactly("20", "52", "84");
  }

  @Test
  @DisplayName("a hut whose life state spawns a unit with a starting action of its own is refused")
  void aChildWithAStartingActionIsRefused() {
    Scene scene = new Scene(GameData.tables(), "GoblinHut_crazy_1");
    scene.still(1, "Knight", 3500, 13000, "K");
    assertThatThrownBy(() -> scene.stepThrough(30))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("GoblinDemolisher");
  }

  @Test
  @DisplayName("a target east of the hut turns the first child the other way")
  void aTargetToTheEast() {
    Scene scene = new Scene(GameData.tables());
    scene.still(1, "Knight", 16500, 12500, "K");
    scene.stepThrough(110);
    assertThat(scene.spawns)
        .containsExactly(
            "20 " + DUMMY + " 11627 12090",
            "61 " + DUMMY + " 11627 12910",
            "103 " + DUMMY + " 11627 12090");
  }

  @Test
  @DisplayName("a row that spawns three fans them at 45, 0 and -45 degrees, the same every round")
  void aFanOfThree(@TempDir Path folder) throws IOException {
    GameTables three =
        GameData.altered(
            folder,
            "actions",
            rows ->
                ((ObjectNode) rows.get("goblin_hut_life_time_controller").get("fields"))
                    .put("SpawnNumber", 3));
    Scene scene = new Scene(three);
    scene.still(1, "Knight", 3500, 13000, "K");
    scene.stepThrough(64);
    List<String> round = List.of("9594 11714", "9303 12585", "9714 13406");
    List<String> expected = new ArrayList<>();
    for (int tick : new int[] {20, 61}) {
      round.forEach(point -> expected.add(tick + " " + DUMMY + " " + point));
    }
    assertThat(scene.spawns).containsExactlyElementsOf(expected);
  }

  @Test
  @DisplayName(
      "the finder takes a troop strictly within the hut's reach and its own radius, and the keep"
          + " test holds one exactly there and lets one go beyond")
  void theEdgeOfTheReach() {
    Scene scene = new Scene(GameData.tables());
    // 7500 from the hut: its collision radius and range, 7000, and the Knight's 500.
    CharacterEntity knight = scene.still(1, "Knight", 3000, 12500, "K");
    scene.stepThrough(24);
    assertThat(scene.spawns).isEmpty();
    knight.getView().setX(3001);
    scene.stepThrough(29);
    assertThat(scene.spawns).containsExactly("25 " + DUMMY + " 9373 12090");
    knight.getView().setX(3000);
    scene.stepThrough(34);
    assertThat(scene.words.get(34).target()).as("kept at the edge").isEqualTo(knight.getId());
    knight.getView().setX(2999);
    scene.stepThrough(35);
    assertThat(scene.words.get(35).lost()).as("let go beyond it").isTrue();
    assertThat(scene.words.get(35).target()).isEqualTo(GoblinHutLifeState.NO_TARGET);
  }

  @Test
  @DisplayName(
      "a stun holds the timer while it lets the target go, so the next spawn comes as much later"
          + " as the stun lasted")
  void aStunHoldsTheTimer() {
    Scene scene = new Scene(GameData.tables());
    scene.still(1, "Cannon", 10500, 19500, "C");
    scene.match.placeAreaEffect(40, "Zap", LEVEL, 1, 10500, 12500, "Z");
    scene.stepThrough(80);
    assertThat(scene.spawns)
        .filteredOn(line -> line.contains(DUMMY))
        .containsExactly("20 " + DUMMY + " 10910 13627", "71 " + DUMMY + " 10090 13627");
    assertThat(scene.words.get(50).timerMs()).as("held through the stun").isEqualTo(1050);
  }

  @Test
  @DisplayName(
      "a spawn due on the tick the hut's decay kills it still comes, after its death spawn")
  void aSpawnDueAsTheHutDies() {
    Scene scene = new Scene(GameData.tables());
    scene.still(1, "Knight", 3500, 13000, "K");
    scene.stepThrough(60);
    scene.hut.getHitPoints().setHitPoints(2);
    scene.stepThrough(66);
    assertThat(scene.spawns)
        .containsExactly(
            "20 " + DUMMY + " 9347 12171",
            "61 SpearGoblin 10500 12500",
            "61 " + DUMMY + " 9405 12988");
  }
}
