/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

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
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The evolved Skeletons: each hit lists the buff SkeletonDuplication_EV1 on the attacker for half a
 * second, whose spawner makes one more evolved Skeleton in front of it on the instance's first
 * visit, linked into the chain of a Skeleton in a group right after it; a firing with the chain at
 * the row's group size makes nothing and is spent all the same.
 *
 * <p>The scene writes the hit count that lists the buff (one) and its time (500 ms), and the buff's
 * spawner (one Skeleton on its first visit, needing its carrier alive), so each hit makes one
 * Skeleton; the group size and the radius are read from the row.
 */
class BattleSkeletonsEvoTest {

  /** A Common card at its first level, as the evolved play in the reference case stands. */
  private static final int LEVEL = 1;

  private static final String SKELETON = "Skeleton_EV1";

  /** The row's GroupMaxSize: the chain length at which a firing makes nothing. */
  private static final int GROUP_MAX_SIZE =
      Shipped.number(Shipped.unitRow(SKELETON), "GroupMaxSize");

  @TempDir static Path folder;

  /** The configured tables with the scene's hit count written. */
  private static GameTables tables;

  /** The spawner's timer as an instance is listed, in the scenes that clone it before it fires. */
  private static final int LATE_START_MS = 300;

  /**
   * The duplication's time in the scenes that clone it before it fires: past the Clone buff, under
   * which neither the Skeleton's spawner nor its copy steps, by more than the late start.
   */
  private static final int LATE_BUFF_TIME_MS = 2000;

  @BeforeAll
  static void writeTheScene() throws IOException {
    tables = sceneTables(folder.resolve("scene"), 500, duplication -> {});
  }

  /**
   * The scene's tables with the duplication's row written as cloned or not (NotCloned), listed for
   * {@link #LATE_BUFF_TIME_MS} and its spawner firing {@link #LATE_START_MS} after it is listed.
   * Loaded by the test that reads them, so that the class holds no more than one set of tables.
   */
  private static GameTables lateTables(Path folder, boolean notCloned) throws IOException {
    return sceneTables(
        folder,
        LATE_BUFF_TIME_MS,
        duplication ->
            duplication.put("NotCloned", notCloned).put("SpawnStartTime", LATE_START_MS));
  }

  /**
   * The configured tables with the scene's hit count, the duplication's time and its spawner
   * written, then the duplication's row edited further.
   */
  private static GameTables sceneTables(
      Path folder, int buffTimeMs, Consumer<ObjectNode> duplication) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(
        folder,
        "characters",
        rows -> {
          ObjectNode skeleton = GameData.columns(rows, SKELETON);
          skeleton.putArray("BuffAfterHitsCount").add(1);
          skeleton.putArray("BuffAfterHitsTime").add(buffTimeMs);
        });
    GameData.alterLoaded(
        folder,
        "character_buffs",
        rows ->
            duplication.accept(
                GameData.columns(rows, DUPLICATION)
                    .put("SpawnInterval", 50)
                    .put("SpawnLimit", 1)
                    .put("SpawnNumber", 1)
                    .put("SpawnerAliveRequired", true)));
    return GameTables.load(folder);
  }

  /** A battle with the towers passive, and every spawn and every hit on the target. */
  private static final class Scene {
    final Standard1v1Battle match;
    final List<String> spawns = new ArrayList<>();
    final List<Integer> spawnTicks = new ArrayList<>();
    final List<Integer> hitTicks = new ArrayList<>();
    final List<CharacterEntity> children = new ArrayList<>();
    final List<SpawnHost> sources = new ArrayList<>();

    /** Each copy of the duplication a clone took, with what its original's instance held then. */
    final List<String> copies = new ArrayList<>();

    final List<CharacterEntity> clones = new ArrayList<>();
    WorldEntity target;
    int tick;

    /** Runs as a child is made, inside the buff visit that makes it. */
    Runnable onSpawn = () -> {};

    Scene() {
      this(tables);
    }

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void characterSpawned(
                    int tick, SpawnHost source, CharacterEntity child, int x, int y) {
                  spawns.add(
                      "%s %s (%d, %d)".formatted(source.name(), child.getData().name(), x, y));
                  spawnTicks.add(tick);
                  children.add(child);
                  sources.add(source);
                  onSpawn.run();
                }

                @Override
                public void cloned(
                    int tick,
                    CharacterEntity original,
                    CharacterEntity clone,
                    SpawnHost instigator,
                    List<Integer> visits) {
                  clones.add(clone);
                }

                @Override
                public void buffCopied(
                    int tick, WorldEntity original, WorldEntity clone, BuffInstance copy) {
                  if (!copy.getBuff().name().equals(DUPLICATION)) {
                    return;
                  }
                  for (BuffInstance held : ((CharacterEntity) original).getBuffs().items()) {
                    if (held.getBuff().name().equals(DUPLICATION)) {
                      copies.add(spawner(held) + " / " + spawner(copy));
                    }
                  }
                }

                @Override
                public void damageDealt(
                    int tick, WorldEntity hit, int damage, DamageResult result) {
                  if (hit == target) {
                    hitTicks.add(tick);
                  }
                }
              });
    }

    /** A unit placed on tick 0 that never moves. */
    CharacterEntity still(int side, String row, int x, int y, String name) {
      CharacterEntity unit =
          match.deploy(0, match.getWorld().getRecords().unit(row), LEVEL, side, x, y, name);
      unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      return unit;
    }

    void step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        match.getBattle().step();
        tick++;
      }
    }
  }

  /** The duplication buff the evolved Skeleton's hits list. */
  private static final String DUPLICATION = "SkeletonDuplication_EV1";

  /** An instance's spawner: its timer, the firings of its wave and the firings it has left. */
  private static String spawner(BuffInstance instance) {
    return instance.getSpawnTimer()
        + " "
        + instance.getSpawnWaveMade()
        + " "
        + instance.getSpawnsLeft();
  }

  /**
   * An evolved Skeleton hitting a Giant, cloned by side 0's Clone on the tick after its first hit,
   * when the duplication its hit listed has not fired yet; stepped until two children are made, or
   * for 40 ticks. The Clone buff both carry holds both spawners still until it runs out.
   */
  private static Scene clonedBeforeTheFiring(GameTables tables) {
    Scene scene = new Scene(tables);
    scene.still(0, SKELETON, 9000, 12000, "skeleton");
    scene.target = scene.still(1, "Giant", 9600, 13200, "giant");
    for (int i = 0; i < 60 && scene.hitTicks.isEmpty(); i++) {
      scene.step(1);
    }
    assertThat(scene.hitTicks).hasSize(1);
    assertThat(scene.children).isEmpty();
    scene.match.placeAreaEffect(scene.tick, "Clone", LEVEL, 0, 9000, 12000, "C");
    scene.step(1);
    assertThat(scene.clones).isNotEmpty();
    for (int i = 0; i < 40 && scene.children.size() < 2; i++) {
      scene.step(1);
    }
    return scene;
  }

  @Test
  @DisplayName(
      "a Clone's copy of the duplication carries the spawner as its original left it - the timer,"
          + " the wave's firings and the firings left - so the clone fires on the original's tick,"
          + " and the child of a clone is a clone of 1 hit point")
  void aClonesCopyCarriesTheSpawner(@TempDir Path cloned) throws IOException {
    Scene scene = clonedBeforeTheFiring(lateTables(cloned, false));

    // The copy took the original's spawner as it stood, part way down its timer.
    assertThat(scene.copies).hasSize(1);
    String[] pair = scene.copies.get(0).split(" / ");
    assertThat(pair[1]).isEqualTo(pair[0]);
    assertThat(pair[0]).doesNotStartWith(LATE_START_MS + " ");
    // One child from the Skeleton and one from its clone, on the same tick.
    assertThat(scene.children).hasSize(2);
    assertThat(scene.spawnTicks.get(1)).isEqualTo(scene.spawnTicks.get(0));
    CharacterEntity clone = scene.clones.get(0);
    for (int i = 0; i < 2; i++) {
      CharacterEntity child = scene.children.get(i);
      boolean ofTheClone = scene.sources.get(i) == clone;
      assertThat(child.isClone()).as(child.name()).isEqualTo(ofTheClone);
      if (ofTheClone) {
        assertThat(child.getHitPoints().getHitPoints()).isEqualTo(1);
        assertThat(child.getHitPoints().getMaximum()).isEqualTo(1);
      }
    }
    assertThat(scene.sources).contains(clone);
  }

  @Test
  @DisplayName(
      "with the duplication's row not cloned, the clone's copy is taken off as the clone is made,"
          + " before any visit: only the Skeleton makes children")
  void aNotClonedDuplicationIsTakenOffTheClone(@TempDir Path notCloned) throws IOException {
    Scene scene = clonedBeforeTheFiring(lateTables(notCloned, true));

    assertThat(scene.copies).hasSize(1);
    CharacterEntity clone = scene.clones.get(0);
    assertThat(clone.getBuffs().carries(DUPLICATION)).isFalse();
    // The Skeleton's own children alone, the first from the instance the clone was made over.
    assertThat(scene.children).isNotEmpty();
    assertThat(scene.sources).doesNotContain(clone);
    assertThat(scene.children).noneMatch(CharacterEntity::isClone);
  }

  @Test
  @DisplayName(
      "each hit makes one evolved Skeleton in front of the attacker, the two collision radii away"
          + " toward the enemy, in the buff visit of the tick after the hit")
  void eachHitMakesOneSkeletonInFront() {
    Scene scene = new Scene();
    scene.still(0, SKELETON, 9000, 12000, "skeleton");
    scene.target = scene.still(1, "Giant", 9600, 13200, "giant");

    scene.step(120);

    assertThat(scene.hitTicks).hasSizeGreaterThanOrEqualTo(2);
    assertThat(scene.spawns).isNotEmpty();
    // Straight ahead of the attacker at (9000, 12000), its own and the child's radius away.
    int radius = Shipped.number(Shipped.unitRow(SKELETON), "CollisionRadius");
    assertThat(scene.spawns.get(0))
        .isEqualTo("skeleton Skeleton_EV1 (9000, %d)".formatted(12000 + 2 * radius));
    // The hit lands at the damage drain, which counts it and lists the buff after that tick's
    // buff visit.
    assertThat(scene.spawnTicks.get(0)).isEqualTo(scene.hitTicks.get(0) + 1);
    // One child per instance: the next comes with the next hit.
    assertThat(scene.spawns.get(1)).doesNotStartWith("skeleton ");
    assertThat(scene.spawnTicks.get(1)).isGreaterThan(scene.spawnTicks.get(0));
  }

  @Test
  @DisplayName(
      "a Skeleton whose chain holds its row's GroupMaxSize of units makes nothing as it fires, and"
          + " the firing is spent: a member dying before the next hit brings no child")
  void aFullChainMakesNothingAndSpendsTheFiring() {
    Scene scene = new Scene();
    CharacterEntity first = scene.still(0, SKELETON, 9000, 12000, "skeleton");
    CharacterEntity previous = first;
    first.linkAfter(null);
    CharacterEntity last = first;
    for (int i = 1; i < GROUP_MAX_SIZE; i++) {
      CharacterEntity member = scene.still(0, SKELETON, 3000 + 600 * i, 9000, "member" + i);
      member.linkAfter(previous);
      previous = member;
      last = member;
    }
    scene.target = scene.still(1, "Giant", 9600, 13200, "giant");

    // Up to the visit after the first hit, where the buff's one firing finds the chain full.
    for (int i = 0; i < 60 && scene.hitTicks.isEmpty(); i++) {
      scene.step(1);
    }
    scene.step(1);
    assertThat(scene.hitTicks).hasSize(1);
    assertThat(scene.spawns).isEmpty();
    assertThat(first.chainSize()).isEqualTo(GROUP_MAX_SIZE);

    // A member dies and leaves the chain; the instance has no firing left, so nothing is made
    // until the next hit lists a new one.
    scene.match.getWorld().getHits().kill(last, null);
    for (int i = 0; i < 60 && scene.hitTicks.size() < 2; i++) {
      scene.step(1);
    }
    assertThat(first.chainSize()).isEqualTo(GROUP_MAX_SIZE - 1);
    assertThat(scene.spawns).isEmpty();
    scene.step(1);

    assertThat(scene.spawnTicks).containsExactly(scene.hitTicks.get(1) + 1);
    assertThat(first.chainNext()).isSameAs(scene.children.get(0));
    assertThat(first.chainSize()).isEqualTo(GROUP_MAX_SIZE);
  }

  @Test
  @DisplayName(
      "a Skeleton whose chain holds one unit fewer than its row's GroupMaxSize makes one, linked into"
          + " the chain right after it")
  void theChildJoinsTheChainAfterItsSpawner() {
    Scene scene = new Scene();
    CharacterEntity first = scene.still(0, SKELETON, 9000, 12000, "skeleton");
    CharacterEntity previous = first;
    first.linkAfter(null);
    for (int i = 1; i < GROUP_MAX_SIZE - 1; i++) {
      CharacterEntity member = scene.still(0, SKELETON, 3000 + 600 * i, 9000, "member" + i);
      member.linkAfter(previous);
      previous = member;
    }
    CharacterEntity second = first.chainNext();
    scene.target = scene.still(1, "Giant", 9600, 13200, "giant");

    // Up to the first child: its own hit would find the chain at the limit.
    for (int i = 0; i < 60 && scene.children.isEmpty(); i++) {
      scene.step(1);
    }

    assertThat(scene.children).hasSize(1);
    CharacterEntity child = scene.children.get(0);
    assertThat(first.chainNext()).isSameAs(child);
    assertThat(child.chainNext()).isSameAs(second);
    assertThat(first.chainSize()).isEqualTo(GROUP_MAX_SIZE);
  }

  @Test
  @DisplayName(
      "a Skeleton killed in the tick its duplication makes a child loses the child: the fold"
          + " releases it, so it never joins the battle and leaves the chain it was linked into")
  void aChildWhoseCarrierDiesInItsTickIsReleased() {
    Scene scene = new Scene();
    CharacterEntity first = scene.still(0, SKELETON, 3000, 9000, "first");
    first.linkAfter(null);
    CharacterEntity carrier = scene.still(0, SKELETON, 9000, 12000, "skeleton");
    carrier.linkAfter(first);
    scene.target = scene.still(1, "Giant", 9600, 13200, "giant");
    // The carrier is killed in the buff visit that makes its first child, so it dies at that
    // tick's damage drain and leaves in the closing cleanup, ahead of the fold.
    scene.onSpawn =
        () -> {
          scene.match.getWorld().getHits().kill(carrier, null);
          scene.onSpawn = () -> {};
        };

    for (int i = 0; i < 60 && scene.children.isEmpty(); i++) {
      scene.step(1);
    }

    assertThat(scene.children).hasSize(1);
    CharacterEntity child = scene.children.get(0);
    // The carrier left the chain as it was removed, and the child as the fold released it.
    assertThat(first.chainNext()).isNull();
    assertThat(first.chainSize()).isEqualTo(1);
    // The next ticks' snapshots hold neither.
    for (int i = 0; i < 5; i++) {
      scene.step(1);
      assertThat(scene.match.getWorld().present()).contains(first).doesNotContain(carrier, child);
    }
  }
}
