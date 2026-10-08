package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The evolved Bomber's bouncing bomb: BombSkeletonProjectile_EV1 lands, and its spawn chain of two
 * launches BombSkeletonProjectile_2_EV1 on its ProjectileRange beyond each impact. The three bombs
 * of one throw share one group id, so an entity one bomb of the throw has damaged takes nothing
 * from the others.
 *
 * <p>The scene writes every column the bombs' points and ticks are read from: the Bomber's timing,
 * reach and throw, the bombs' flight, radius, range and chain, the Musketeer's size and the towers'
 * places and size, so they are its own and not a version's.
 */
class BattleBomberEvoTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The Bomber's hit speed, as the scene writes it: a throw every 36 steps. */
  private static final int HIT_SPEED = 1800;

  @TempDir static Path folder;

  /** The configured tables with the scene's columns written, and their records. */
  private static GameTables tables;

  private static BattleRecords records;

  @BeforeAll
  static void writeTheScene() throws IOException {
    GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "Bomber_EV1")
              .put("HitSpeed", HIT_SPEED)
              .put("LoadTime", 1600)
              .put("DeployTime", 1000)
              .put("Range", 4500)
              .put("SightRange", 5500)
              .put("Speed", 60)
              .put("CollisionRadius", 500)
              .put("ProjectileStartRadius", 350)
              .put("ProjectileStartZ", 4800);
          GameData.columns(rows, "Musketeer").put("CollisionRadius", 500).put("DeployTime", 1000);
        });
    GameData.alterLoaded(
        folder,
        "projectiles",
        rows -> {
          GameData.columns(rows, "BombSkeletonProjectile_EV1")
              .put("Speed", 400)
              .put("Gravity", 200)
              .put("Radius", 1500)
              .put("SpawnChain", 2);
          GameData.columns(rows, "BombSkeletonProjectile_2_EV1")
              .put("Speed", 400)
              .put("Gravity", 200)
              .put("Radius", 1500)
              .put("ProjectileRange", 3000)
              .put("SpawnChain", 2);
        });
    GameData.alterLoaded(
        folder,
        "buildings",
        rows -> GameData.columns(rows, "PrincessTower").put("CollisionRadius", 1000));
    GameData.alterLoaded(
        folder,
        "spawn_groups",
        rows -> {
          ArrayNode towers = GameData.columns(rows, "King_PrincessTowers").putArray("Objects");
          towers.addObject().put("Data", "KingTower").put("x", 18).put("y", 6);
          towers.addObject().put("Data", "PrincessTower").put("x", 7).put("y", 13);
          towers.addObject().put("Data", "PrincessTower").put("x", 29).put("y", 13);
        });
    tables = GameTables.load(folder);
    records = new BattleRecords(tables);
  }

  /** Side 1's right princess tower, at (14500, 25500). */
  private static final String TOWER = "PrincessTower_1_2";

  /** One impact on an entity: the bomb's row, its id, the tick, and whether the damage landed. */
  private record Impact(int tick, String row, int projectileId, String target, boolean landed) {}

  /**
   * Runs an evolved Bomber of side 0 at a Musketeer of side 1 standing 4000 in front of side 1's
   * right princess tower, the towers passive: the bomb lands on the Musketeer, its first bounce
   * beside the tower and its second bounce on the tower.
   */
  private static List<Impact> bombTheMusketeer(int ticks) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<Impact> impacts = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileImpacted(
                  int tick,
                  ProjectileEntity projectile,
                  WorldEntity target,
                  int damage,
                  DamageResult result) {
                impacts.add(
                    new Impact(
                        tick,
                        projectile.getData().name(),
                        projectile.getId(),
                        target.name(),
                        result.landed()));
              }
            });
    match.deploy(0, records.unit("Musketeer"), LEVEL, 1, 14500, 21500);
    match.deploy(0, records.unit("Bomber_EV1"), LEVEL, 0, 14500, 16000);
    for (int tick = 0; tick < ticks; tick++) {
      match.getBattle().step();
    }
    return impacts;
  }

  /** The impacts of the first throw: from its bomb's landing to the end of its last bounce. */
  private static List<Impact> firstThrow(List<Impact> impacts) {
    Impact bomb =
        impacts.stream()
            .filter(impact -> impact.row().equals("BombSkeletonProjectile_EV1"))
            .findFirst()
            .orElseThrow();
    return impacts.stream()
        .filter(impact -> impact.tick() >= bomb.tick() && impact.tick() < bomb.tick() + 20)
        .toList();
  }

  @Test
  @DisplayName(
      "of one throw, the bounce that lands beside the tower hits it and the next bounce, landing on"
          + " it, deals it nothing")
  void theLastBounceSparesTheTowerItsThrowHasHit() {
    List<Impact> impacts = firstThrow(bombTheMusketeer(400));
    List<Impact> onTower =
        impacts.stream().filter(impact -> impact.target().equals(TOWER)).toList();
    assertThat(onTower).as(impacts.toString()).hasSize(2);
    assertThat(onTower).extracting(Impact::row).containsOnly("BombSkeletonProjectile_2_EV1");
    assertThat(onTower).extracting(Impact::landed).containsExactly(true, false);
  }

  @Test
  @DisplayName("the bomb lands on the Musketeer, which the bounces never reach")
  void theBombLandsOnTheMusketeer() {
    List<Impact> impacts = firstThrow(bombTheMusketeer(400));
    assertThat(impacts.get(0).row()).as(impacts.toString()).isEqualTo("BombSkeletonProjectile_EV1");
    assertThat(impacts.get(0).target()).startsWith("Musketeer");
    assertThat(impacts.get(0).landed()).isTrue();
  }

  @Test
  @DisplayName("each throw is a group of its own: the next throw's bounce hits the tower again")
  void theNextThrowHitsTheTowerAgain() {
    List<Impact> landed =
        bombTheMusketeer(400).stream()
            .filter(impact -> impact.target().equals(TOWER))
            .filter(Impact::landed)
            .toList();
    assertThat(landed).hasSizeGreaterThanOrEqualTo(2);
    // One hit per throw, the throws HitSpeed apart: never two within the same throw.
    for (int k = 1; k < landed.size(); k++) {
      assertThat(landed.get(k).tick() - landed.get(k - 1).tick()).isEqualTo(HIT_SPEED / 50);
    }
  }
}
