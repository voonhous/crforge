package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.unit.BattleAreaEffectFilterFormTest.filterForm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which objects the filter form of a newer data version's area effect hits and what it launches: a
 * row with MaximumTargets hits that many of its list a hit, an object an earlier hit reached and
 * passed by not counted; a row with HitBiggestTargets takes its list with the most hit points and
 * shield first, objects as big kept nearest first; and a row with a projectile launches one onto
 * each object it hits, from that object's point at the row's start height, or, with
 * TargetProjectiles off, one a hit onto its own point with no target, whoever it lists. Each scene
 * places a spell's area effect of the configured tables rewritten in that form on still units.
 */
class BattleAreaEffectFilterTargetsTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** A tick after every unit placed on the first one has deployed. */
  private static final int CAST_TICK = 25;

  /** The area effect's point, on the bottom side's left, away from every tower. */
  private static final int X = 3500;

  private static final int Y = 11000;

  /** A battle on the given tables with the towers passive that logs launches and typed hits. */
  private static final class Scene {
    final Standard1v1Battle match;
    final List<ProjectileEntity> launched = new ArrayList<>();
    final List<Integer> launchTicks = new ArrayList<>();

    /** The area effect each projectile was launched by, read as it was launched. */
    final List<AreaEffectEntity> launchers = new ArrayList<>();

    final List<String> hits = new ArrayList<>();

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void projectileLaunched(int tick, ProjectileEntity projectile) {
                  launched.add(projectile);
                  launchTicks.add(tick);
                  launchers.add(projectile.getAreaLauncher());
                }

                @Override
                public void typedHitDealt(
                    int tick,
                    WorldEntity source,
                    WorldEntity target,
                    int amount,
                    int damageId,
                    DamageResult result) {
                  hits.add(target.name());
                }
              });
    }

    /** A unit of side 0 placed on the first tick that never moves, under a name of its own. */
    CharacterEntity still(String row, int x, int y, String name) {
      CharacterEntity unit =
          match.deploy(0, match.getWorld().getRecords().unit(row), LEVEL, 0, x, y, name);
      unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      return unit;
    }

    /** Places the area effect of side 1 at the point and steps through its life and more. */
    void place(String row, int ticks) {
      match.placeAreaEffect(CAST_TICK, row, LEVEL, 1, X, Y, "area");
      while (match.getBattle().getTick() < CAST_TICK + ticks) {
        match.getBattle().step();
      }
    }
  }

  /**
   * The configured Lightning in the filter form as the newer data writes it: a hit every 500 ms
   * from 500 ms, one target a hit, each once, the biggest first.
   */
  private static GameTables lightning(Path folder, Consumer<ObjectNode> edit) throws IOException {
    return filterForm(
        folder,
        "Lightning",
        columns -> {
          columns.put("HitSpeed", 500);
          columns.put("HitSpeedOffset", 500);
          columns.put("MaximumTargets", 1);
          columns.put("OneHitPerTarget", true);
          edit.accept(columns);
        });
  }

  @Test
  @DisplayName(
      "a lightning in the filter form strikes, one a hit, the biggest enemy it has not struck, the"
          + " nearest of equals first, each bolt dropped from its target's point")
  void eachHitStrikesTheBiggestNotStruckBefore(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(lightning(folder, columns -> {}));
    scene.still("Knight", X, Y - 500, "near");
    scene.still("Musketeer", X - 1000, Y, "Musketeer");
    scene.still("Knight", X, Y + 1500, "far");
    // The Recruit's shield lifts it above the Musketeer: 547 and 240 against 721.
    scene.still("DeliveryRecruit", X + 2000, Y, "Recruit");
    scene.place("Lightning", 40);

    // Nearest first alone would strike near, Musketeer, far.
    assertThat(scene.launched)
        .extracting(p -> p.getTarget().name())
        .containsExactly("near", "far", "Recruit");
    assertThat(scene.launchers).extracting(AreaEffectEntity::name).containsOnly("area");
    for (ProjectileEntity bolt : scene.launched) {
      assertThat(bolt.getStartX()).isEqualTo(bolt.getTarget().getView().getX());
      assertThat(bolt.getStartY()).isEqualTo(bolt.getTarget().getView().getY());
      assertThat(bolt.getStartZ()).isEqualTo(10);
    }
    // A hit every 500 ms: ten ticks apart.
    assertThat(scene.launchTicks.get(1) - scene.launchTicks.get(0)).isEqualTo(10);
    assertThat(scene.launchTicks.get(2) - scene.launchTicks.get(1)).isEqualTo(10);
  }

  @Test
  @DisplayName(
      "without HitBiggestTargets the filter form's lightning strikes the nearest it has not struck")
  void withoutTheBiggestFirstTheNearest(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(lightning(folder, columns -> columns.remove("HitBiggestTargets")));
    scene.still("Knight", X, Y - 500, "near");
    scene.still("Musketeer", X - 1000, Y, "Musketeer");
    scene.still("Knight", X, Y + 1500, "far");
    scene.still("DeliveryRecruit", X + 2000, Y, "Recruit");
    scene.place("Lightning", 40);

    assertThat(scene.launched)
        .extracting(p -> p.getTarget().name())
        .containsExactly("near", "Musketeer", "far");
  }

  @Test
  @DisplayName("a zap in the filter form with MaximumTargets 2 hits only the two nearest")
  void theMaximumTargetsNearestAreHit(@TempDir Path folder) throws IOException {
    Scene scene =
        new Scene(
            filterForm(
                folder,
                "Zap",
                columns -> {
                  columns.putObject("Damage").put("BaseDamage", 75);
                  columns.put("MaximumTargets", 2);
                }));
    scene.still("Knight", X, Y + 900, "third");
    scene.still("Knight", X, Y - 300, "first");
    scene.still("Knight", X + 600, Y, "second");
    scene.place("Zap", 10);

    assertThat(scene.hits).containsExactly("first", "second");
  }

  /**
   * The configured Royal Delivery's area in the filter form as the newer data writes it: one hit at
   * 2000 ms, its projectile launched by that hit.
   */
  private static GameTables delivery(Path folder, Consumer<ObjectNode> edit) throws IOException {
    return filterForm(
        folder,
        "RoyalDeliveryArea",
        columns -> {
          columns.remove("HitSpeed");
          columns.put("HitSpeedOffset", 2000);
          edit.accept(columns);
        });
  }

  @Test
  @DisplayName(
      "with TargetProjectiles off the filter form launches one projectile a hit onto its own"
          + " point, with no target, whoever it lists")
  void oneProjectileOntoItsPoint(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(delivery(folder, columns -> columns.put("TargetProjectiles", false)));
    scene.still("Knight", X, Y - 500, "near");
    scene.still("Knight", X, Y + 1000, "far");
    scene.place("RoyalDeliveryArea", 45);

    assertThat(scene.launched).hasSize(1);
    ProjectileEntity box = scene.launched.get(0);
    assertThat(box.getTarget()).isNull();
    assertThat(box.getStartX()).isEqualTo(X);
    assertThat(box.getStartY()).isEqualTo(Y);
    assertThat(box.getAimX()).isEqualTo(X);
    assertThat(box.getAimY()).isEqualTo(Y);
    assertThat(scene.launchers).extracting(AreaEffectEntity::name).containsExactly("area");
  }

  @Test
  @DisplayName(
      "with TargetProjectiles left on the filter form launches one projectile onto each object it"
          + " hits, nearest first, from that object's point")
  void oneProjectileOntoEachTarget(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(delivery(folder, columns -> {}));
    scene.still("Knight", X, Y + 1000, "far");
    scene.still("Knight", X, Y - 500, "near");
    scene.place("RoyalDeliveryArea", 45);

    assertThat(scene.launched).extracting(p -> p.getTarget().name()).containsExactly("near", "far");
    for (ProjectileEntity box : scene.launched) {
      assertThat(box.getStartX()).isEqualTo(box.getTarget().getView().getX());
      assertThat(box.getStartY()).isEqualTo(box.getTarget().getView().getY());
    }
    assertThat(scene.launchTicks.get(0)).isEqualTo(scene.launchTicks.get(1));
  }
}
