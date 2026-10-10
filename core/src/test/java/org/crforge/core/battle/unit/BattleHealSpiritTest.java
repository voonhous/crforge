/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The area effect a Heal Spirit's projectile makes where it lands, where the reference runs leave
 * it: made at the impact point at the projectile's level, first updated on the next tick, and its
 * one hit buffing the own troops around it, air and deploying ones included, while buildings, crown
 * towers and enemies are left out.
 *
 * <p>The scenes play at the first level, whose stats are the rows' own, and write what they count
 * on: the spirit's projectile deals 110; its area, a circle of 2500 living 1000 ms, gives its buff
 * for 1000 ms, which heals 400 a second in hits every 250 ms, 100 a hit; a Knight has 1700 hit
 * points and a Minion 230.
 */
class BattleHealSpiritTest {

  /** The first level, whose stats are the rows' own. */
  private static final int LEVEL = 1;

  @TempDir static Path tablesFolder;

  /** The configured tables with the columns the scenes count on written. */
  private static GameTables tables;

  @BeforeAll
  static void writeTheSpirit() throws IOException {
    GameData.altered(
        tablesFolder,
        "projectiles",
        rows -> GameData.columns(rows, "HealSpiritProjectile").put("Damage", 110));
    GameData.alterLoaded(
        tablesFolder,
        "area_effect_objects",
        rows ->
            GameData.columns(rows, "HealSpirit")
                .put("Radius", 2500)
                .put("LifeDuration", 1000)
                .put("BuffTime", 1000));
    GameData.alterLoaded(
        tablesFolder,
        "character_buffs",
        rows ->
            GameData.columns(rows, "HealSpiritBuff")
                .put("HealPerSecond", 400)
                .put("HitFrequency", 250));
    GameData.alterLoaded(
        tablesFolder,
        "characters",
        rows -> {
          GameData.columns(rows, "Knight").put("Hitpoints", 1700);
          GameData.columns(rows, "Minion").put("Hitpoints", 230);
        });
    tables = GameTables.load(tablesFolder);
  }

  /** Where the enemy the spirit jumps at stands, 2200 from the own left princess tower. */
  private static final int X = 3500;

  private static final int Y = 8700;

  /** A battle with the towers passive and one Heal Spirit jumping at a Golem that never moves. */
  private static final class Scene {
    final Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    final List<ProjectileEntity> launched = new ArrayList<>();
    final List<String> impacts = new ArrayList<>();
    final List<String> created = new ArrayList<>();
    final List<Integer> updates = new ArrayList<>();
    final List<String> buffed = new ArrayList<>();
    final Map<String, Integer> buffedStates = new HashMap<>();
    final Map<String, List<Integer>> heals = new HashMap<>();
    AreaEffectEntity area;
    int createdTick = -1;
    int impactTick = -1;
    int tick;

    Scene() {
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void projectileLaunched(int t, ProjectileEntity projectile) {
                  launched.add(projectile);
                }

                @Override
                public void projectileImpacted(
                    int t,
                    ProjectileEntity projectile,
                    WorldEntity target,
                    int damage,
                    DamageResult result) {
                  impactTick = tick;
                  impacts.add(target.name() + " " + damage);
                }

                @Override
                public void areaEffectCreated(
                    int t, AreaEffectEntity a, String how, String source) {
                  area = a;
                  createdTick = tick;
                  created.add(a.getData().name() + " " + how + " " + source);
                }

                @Override
                public void areaEffectUpdated(
                    int t,
                    AreaEffectEntity a,
                    int before,
                    int after,
                    int hits,
                    int radius,
                    List<Integer> damages) {
                  updates.add(tick);
                }

                @Override
                public void areaBuff(
                    int t, AreaEffectEntity a, BuffData buff, int time, List<WorldEntity> targets) {
                  for (WorldEntity e : targets) {
                    buffed.add(e.name());
                    buffedStates.put(e.name(), e.getView().getState());
                  }
                }

                @Override
                public void buffHealed(
                    int t, WorldEntity target, BuffInstance buff, int amount, int before) {
                  heals
                      .computeIfAbsent(target.name(), k -> new ArrayList<>())
                      .add(target.getHitPoints().getHitPoints());
                }
              });
      still(0, 1, "Golem", X, Y, "enemy");
      match.deploy(
          0, match.getWorld().getRecords().unit("HealSpirit"), LEVEL, 0, X, Y + 2800, "Spirit");
    }

    /** A unit placed at a tick that never moves, under a name of its own. */
    CharacterEntity still(int at, int side, String row, int x, int y, String name) {
      CharacterEntity unit =
          match.deploy(at, match.getWorld().getRecords().unit(row), LEVEL, side, x, y, name);
      unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      return unit;
    }

    void step() {
      match.getBattle().step();
      tick++;
    }

    /** Steps until the impact made the area effect, at most a hundred ticks. */
    void untilTheAreaEffect() {
      while (area == null && tick < 100) {
        step();
      }
      assertThat(area).isNotNull();
    }

    WorldEntity named(String name) {
      for (BattleEntity entity : match.getBattle().getHolder().entities()) {
        if (entity instanceof WorldEntity w && w.name().equals(name)) {
          return w;
        }
      }
      throw new IllegalArgumentException(name);
    }
  }

  @Test
  @DisplayName(
      "the impact makes the area effect at its point, for its side, at the projectile's level, and"
          + " the area first updates on the next tick")
  void theImpactMakesTheAreaEffect() {
    Scene scene = new Scene();
    scene.untilTheAreaEffect();
    ProjectileEntity projectile = scene.launched.get(0);

    assertThat(scene.created).containsExactly("HealSpirit projectile " + projectile.name());
    assertThat(scene.createdTick).isEqualTo(scene.impactTick);
    // The area damage of the impact comes first, on the Golem only: it spares its own side.
    assertThat(scene.impacts).containsExactly("enemy 110");
    assertThat(scene.area.getX()).isEqualTo(projectile.getAimX());
    assertThat(scene.area.getY()).isEqualTo(projectile.getAimY());
    assertThat(scene.area.side()).isZero();
    assertThat(scene.area.getPackedLevel())
        .isEqualTo(PackedLevel.pack(projectile.getPackedLevel(), scene.area.getData().rarity()));
    assertThat(PackedLevel.level(scene.area.getPackedLevel())).isEqualTo(LEVEL);
    assertThat(scene.updates).isEmpty();
    scene.step();
    assertThat(scene.updates).containsExactly(scene.createdTick + 1);
  }

  @Test
  @DisplayName(
      "its one hit heals the own troops in its circle, air and deploying ones too, by 100 four"
          + " times; buildings, crown towers and enemies are left out")
  void itHealsTheOwnTroopsAround() {
    Scene scene = new Scene();
    CharacterEntity hurt = scene.still(0, 0, "Knight", X + 2000, Y, "hurt");
    CharacterEntity minion = scene.still(0, 0, "Minion", X, Y + 2000, "minion");
    CharacterEntity full = scene.still(0, 0, "Knight", X - 2000, Y, "full");
    scene.still(0, 0, "Cannon", X + 1500, Y - 1500, "cannon");
    scene.untilTheAreaEffect();
    // A Knight placed as the area effect first updates is still deploying.
    CharacterEntity late = scene.still(scene.tick, 0, "Knight", X, Y - 1500, "late");
    hurt.getHitPoints().setHitPoints(300);
    minion.getHitPoints().setHitPoints(100);
    late.getHitPoints().setHitPoints(300);
    WorldEntity tower = scene.named("PrincessTower_0_1");
    tower.getHitPoints().setHitPoints(2000);
    for (int i = 0; i < 25; i++) {
      scene.step();
    }

    assertThat(scene.buffedStates.get("late")).as("deploying at the hit").isEqualTo(4);
    assertThat(scene.buffed).containsExactlyInAnyOrder("hurt", "minion", "full", "late");
    assertThat(scene.heals.get("hurt")).containsExactly(400, 500, 600, 700);
    assertThat(scene.heals.get("late")).containsExactly(400, 500, 600, 700);
    // The Minion is healed up to its maximum and no further.
    int most = minion.getHitPoints().getMaximum();
    assertThat(scene.heals.get("minion")).containsExactly(200, most, most, most);
    assertThat(scene.heals.get("full")).containsOnly(full.getHitPoints().getMaximum()).hasSize(4);
    assertThat(tower.getHitPoints().getHitPoints()).isEqualTo(2000);
    assertThat(scene.heals).doesNotContainKeys("cannon", "enemy", "PrincessTower_0_1");
  }
}
