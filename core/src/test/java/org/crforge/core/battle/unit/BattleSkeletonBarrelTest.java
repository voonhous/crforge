package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Skeleton Barrel and its container. The references hold the barrel's direct flight, its drain
 * of 53 a visit and the container's ring for the bottom side in the left lane; what they do not
 * reach is held here: the ring for the top side in the right lane, each child's fixed priority, a
 * drain that rounds to nothing, and the refusals of a drain with a shield and of a spawner that
 * asks for a fixed priority.
 */
class BattleSkeletonBarrelTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** One battle with every spawn, every lane a ring asked for and every drain logged. */
  private static final class Scene {
    final Standard1v1Battle match;
    final List<CharacterEntity> spawned = new ArrayList<>();
    final List<String> points = new ArrayList<>();
    final List<Integer> lanes = new ArrayList<>();
    final List<Integer> drains = new ArrayList<>();
    final List<Boolean> ends = new ArrayList<>();

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void characterSpawned(
                    int t, SpawnHost source, CharacterEntity child, int x, int y) {
                  spawned.add(child);
                  points.add(x + " " + y);
                }

                @Override
                public void ringLaneAsked(int t, SpawnHost source, int x, int y, int lane) {
                  lanes.add(lane);
                }

                @Override
                public void kamikazeHitEnded(int t, WorldEntity unit, boolean kills) {
                  ends.add(kills);
                }

                @Override
                public void kamikazeDrained(
                    int t, WorldEntity unit, int damage, int before, DamageResult result) {
                  drains.add(before - unit.getHitPoints().getHitPoints());
                }
              });
    }

    void step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        match.getBattle().step();
      }
    }
  }

  @Test
  @DisplayName(
      "the top side's container in the right lane turns its ring over along the length only, and"
          + " its i-th child is taken as (80i)^2 nearer")
  void theRingTurnsByLaneAndTeam(@TempDir Path folder) throws IOException {
    // Without its push the children are made on their ring points, which shows them.
    GameTables unpushed =
        GameData.altered(
            folder,
            "buildings",
            rows ->
                GameData.columns(rows, "SkeletonContainerNew").put("DeathSpawnPushback", false));
    Scene scene = new Scene(unpushed);
    scene.match.deploy(
        0, scene.match.getWorld().getRecords().unit("SkeletonContainerNew"), LEVEL, 1, 14500, 8270);
    scene.step(20);
    // The native ring of a top-side container at this point, child 0 first.
    assertThat(scene.points)
        .containsExactly(
            "15410 9436",
            "14168 9712",
            "13159 8895",
            "13171 7622",
            "14193 6822",
            "15430 7120",
            "15980 8270");
    assertThat(scene.lanes).hasSize(7).containsOnly(2);
    assertThat(scene.spawned)
        .extracting(child -> child.getView().getSquaredDistanceReduction())
        .containsExactly(0, 6400, 25600, 57600, 102400, 160000, 230400);
  }

  @Test
  @DisplayName(
      "a Kamikaze time longer than the hit points in visits drains one a visit, and the hit does"
          + " not kill")
  void aDrainRoundsUpToOne(@TempDir Path folder) throws IOException {
    GameTables slow =
        GameData.altered(
            folder,
            "characters",
            rows -> GameData.columns(rows, "SkeletonBalloon").put("KamikazeTime", 100_000));
    Scene scene = new Scene(slow);
    // Out of its deploy beside the top side's left princess tower, which it hits at once.
    CharacterEntity barrel =
        scene.match.deploy(
            0, scene.match.getWorld().getRecords().unit("SkeletonBalloon"), LEVEL, 0, 3500, 23730);
    scene.step(60);
    assertThat(scene.ends).isNotEmpty().containsOnly(false);
    assertThat(scene.drains).isNotEmpty().containsOnly(1);
    assertThat(barrel.getHitPoints().getHitPoints())
        .isEqualTo(barrel.getHitPoints().getMaximum() - scene.drains.size());
  }

  @Test
  @DisplayName("a Kamikaze row with a time and a shield of its own is refused as it drains")
  void aShieldedDrainIsRefused(@TempDir Path folder) throws IOException {
    GameTables shielded =
        GameData.altered(
            folder,
            "characters",
            rows -> GameData.columns(rows, "SkeletonBalloon").put("ShieldHitpoints", 100));
    Scene scene = new Scene(shielded);
    scene.match.deploy(
        0, scene.match.getWorld().getRecords().unit("SkeletonBalloon"), LEVEL, 0, 3500, 23730);
    assertThatThrownBy(() -> scene.step(60))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("drains as a Kamikaze row");
  }

  @Test
  @DisplayName("a live spawner that asks for a fixed priority is refused; a death spawn is not")
  void aSpawnersFixedPriorityIsRefused(@TempDir Path folder) throws IOException {
    GameTables fixed =
        GameData.altered(
            Files.createDirectories(folder.resolve("fixed")),
            "buildings",
            rows -> GameData.columns(rows, "Tombstone").put("SpawnConstPriority", true));
    BattleRecords records = new BattleRecords(fixed);
    assertThat(records.unit("Tombstone").unmodelledColumns()).contains("SpawnConstPriority");
    assertThat(records.unit("SkeletonContainerNew").unmodelledColumns()).isEmpty();
  }
}
