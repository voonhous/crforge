package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * the row's group size is refused.
 *
 * <p>The scene writes the hit count that lists the buff (one) and its time (500 ms), and the buff's
 * spawner (one Skeleton on its first visit), so each hit makes one Skeleton; the group size and the
 * radius are read from the row.
 */
class BattleSkeletonsEvoTest {

  /** A Common card at its first level, as the evolved play in the reference case stands. */
  private static final int LEVEL = 1;

  private static final String SKELETON = "Skeleton_EV1";

  /** The row's GroupMaxSize: the chain length at which a firing is refused. */
  private static final int GROUP_MAX_SIZE =
      Shipped.number(Shipped.unitRow(SKELETON), "GroupMaxSize");

  @TempDir static Path folder;

  /** The configured tables with the scene's hit count written. */
  private static GameTables tables;

  @BeforeAll
  static void writeTheScene() throws IOException {
    GameData.altered(
        folder,
        "characters",
        rows -> {
          ObjectNode skeleton = GameData.columns(rows, SKELETON);
          skeleton.putArray("BuffAfterHitsCount").add(1);
          skeleton.putArray("BuffAfterHitsTime").add(500);
        });
    GameData.alterLoaded(
        folder,
        "character_buffs",
        rows ->
            GameData.columns(rows, "SkeletonDuplication_EV1")
                .put("SpawnInterval", 50)
                .put("SpawnLimit", 1)
                .put("SpawnNumber", 1));
    tables = GameTables.load(folder);
  }

  /** A battle with the towers passive, and every spawn and every hit on the target. */
  private static final class Scene {
    final Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    final List<String> spawns = new ArrayList<>();
    final List<Integer> spawnTicks = new ArrayList<>();
    final List<Integer> hitTicks = new ArrayList<>();
    final List<CharacterEntity> children = new ArrayList<>();
    WorldEntity target;

    Scene() {
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
      }
    }
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
      "a Skeleton whose chain holds its row's GroupMaxSize of units is refused as it fires: the held"
          + " spawn is not modelled")
  void aFullChainIsRefused() {
    Scene scene = new Scene();
    CharacterEntity first = scene.still(0, SKELETON, 9000, 12000, "skeleton");
    CharacterEntity previous = first;
    first.linkAfter(null);
    for (int i = 1; i < GROUP_MAX_SIZE; i++) {
      CharacterEntity member = scene.still(0, SKELETON, 3000 + 600 * i, 9000, "member" + i);
      member.linkAfter(previous);
      previous = member;
    }
    scene.target = scene.still(1, "Giant", 9600, 13200, "giant");

    assertThatThrownBy(() -> scene.step(60))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "skeleton's SkeletonDuplication_EV1 fires with its group chain at the limit of %d,"
                    .formatted(GROUP_MAX_SIZE)
                + " which holds the spawn and is not modelled");
    assertThat(scene.hitTicks).hasSize(1);
    assertThat(scene.spawns).isEmpty();
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
}
