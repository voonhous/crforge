package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Skeletons: each hit lists the buff SkeletonDuplication_EV1 on the attacker for half a
 * second, whose spawner makes one more evolved Skeleton in front of it on the instance's first
 * visit, linked into the chain of a Skeleton in a group right after it; a firing with the chain at
 * the row's group size is refused.
 */
class BattleSkeletonsEvoTest {

  /** A Common card at its first level, as the evolved play in the reference case stands. */
  private static final int LEVEL = 1;

  private static final String SKELETON = "Skeleton_EV1";

  /** A battle with the towers passive, and every spawn and every hit on the target. */
  private static final class Scene {
    final Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
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
      CharacterEntity unit = match.deploy(0, GameData.unit(row), LEVEL, side, x, y, name);
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
          + " toward the enemy, in the buff visit of the tick of the hit")
  void eachHitMakesOneSkeletonInFront() {
    Scene scene = new Scene();
    scene.still(0, SKELETON, 9000, 12000, "skeleton");
    scene.target = scene.still(1, "Giant", 9600, 13200, "giant");

    scene.step(120);

    assertThat(scene.hitTicks).hasSizeGreaterThanOrEqualTo(2);
    assertThat(scene.spawns).isNotEmpty();
    assertThat(scene.spawns.get(0)).isEqualTo("skeleton Skeleton_EV1 (9000, 13000)");
    assertThat(scene.spawnTicks.get(0)).isEqualTo(scene.hitTicks.get(0));
    // One child per instance: the next comes with the next hit.
    assertThat(scene.spawns.get(1)).doesNotStartWith("skeleton ");
    assertThat(scene.spawnTicks.get(1)).isGreaterThan(scene.spawnTicks.get(0));
  }

  @Test
  @DisplayName(
      "a Skeleton whose chain holds eight units is refused as it fires: the held spawn is not"
          + " modelled")
  void aFullChainIsRefused() {
    Scene scene = new Scene();
    CharacterEntity first = scene.still(0, SKELETON, 9000, 12000, "skeleton");
    CharacterEntity previous = first;
    first.linkAfter(null);
    for (int i = 1; i < 8; i++) {
      CharacterEntity member = scene.still(0, SKELETON, 3000 + 600 * i, 9000, "member" + i);
      member.linkAfter(previous);
      previous = member;
    }
    scene.target = scene.still(1, "Giant", 9600, 13200, "giant");

    assertThatThrownBy(() -> scene.step(60))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "skeleton's SkeletonDuplication_EV1 fires with its group chain at the limit of 8,"
                + " which holds the spawn and is not modelled");
    assertThat(scene.hitTicks).hasSize(1);
    assertThat(scene.spawns).isEmpty();
  }

  @Test
  @DisplayName(
      "a Skeleton whose chain holds seven units makes one, linked into the chain right after it")
  void theChildJoinsTheChainAfterItsSpawner() {
    Scene scene = new Scene();
    CharacterEntity first = scene.still(0, SKELETON, 9000, 12000, "skeleton");
    CharacterEntity previous = first;
    first.linkAfter(null);
    for (int i = 1; i < 7; i++) {
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
    assertThat(first.chainSize()).isEqualTo(8);
  }
}
