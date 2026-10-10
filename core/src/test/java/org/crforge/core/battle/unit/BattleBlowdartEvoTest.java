/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.GameData.fields;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleTowers;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The evolved Dart Goblin: its starting action picks each dart, the special one when the poison
 * controller on the target is about to reach its next stack; the darts' hits run that controller on
 * the target, which drops a poison area effect on it every second while it lasts, and each area's
 * hits keep one poison damage run going on the target.
 *
 * <p>The scene writes the controller's stacks (three, at the first, fourth and seventh dart) and
 * its timing, the poison's timing and the poison areas' timing, the towers' places and the Dart
 * Goblin's throw, so the darts' order and the ticks are its own; the damages are read from the rows
 * and scaled in the test.
 */
class BattleBlowdartEvoTest {

  private static final String CONTROLLER = "blowdart_evo_darts_controller";

  private static final String POISON = "blowdart_aeo_evo_poison";

  /** The dart counts at which the controller reaches its stacks, as the scene writes them. */
  private static final List<Integer> STACK_CHECKS = List.of(1, 4, 7);

  /** The controller's area interval, and the poison's hit speed, as the scene writes them. */
  private static final int SPAWN_INTERVAL = 1000;

  private static final int POISON_HIT_SPEED = 1000;

  /** The poison areas' first-hit offset, as the scene writes it. */
  private static final int AREA_HIT_OFFSET = 250;

  private static final List<String> AREAS =
      List.of(
          "BlowDartPoisonAeO_baseDamage",
          "BlowDartPoisonAeO_midDamage",
          "BlowDartPoisonAeO_fullDamage");

  @TempDir static Path folder;

  /** The configured tables with the scene's columns written, and their records. */
  private static GameTables tables;

  private static BattleRecords records;

  @BeforeAll
  static void writeTheScene() throws IOException {
    GameData.altered(
        folder,
        "actions",
        rows -> {
          ObjectNode controller = fields(rows, CONTROLLER);
          controller
              .put("MaxStacks", STACK_CHECKS.size())
              .put("SpawnInterval", SPAWN_INTERVAL)
              .put("CrownTowerDuration", 3000)
              .put("Duration", 999999);
          ArrayNode checks = controller.putArray("StackAmountChecks");
          STACK_CHECKS.forEach(checks::add);
          fields(rows, POISON)
              .put("HitSpeed", POISON_HIT_SPEED)
              .put("Duration", 1000)
              .put("CrownTowerDuration", 1000);
        });
    GameData.alterLoaded(
        folder,
        "area_effect_objects",
        rows -> {
          for (String area : AREAS) {
            GameData.columns(rows, area)
                .put("HitSpeedOffset", AREA_HIT_OFFSET)
                .put("HitSpeed", 250)
                .put("LifeDuration", 1000)
                .put("Radius", 1500);
          }
        });
    GameData.alterLoaded(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "BlowdartGoblin_EV1")
                .put("HitSpeed", 800)
                .put("LoadTime", 450)
                .put("DeployTime", 1000)
                .put("Range", 6500)
                .put("SightRange", 7000)
                .put("ProjectileStartRadius", 1200)
                .put("ProjectileStartZ", 1600));
    GameData.alterLoaded(
        folder,
        "projectiles",
        rows -> {
          GameData.columns(rows, REGULAR).put("Speed", 800);
          GameData.columns(rows, SPECIAL).put("Speed", 800);
        });
    GameData.alterLoaded(folder, "spawn_groups", GameData::placeTowers);
    tables = GameTables.load(folder);
    records = new BattleRecords(tables);
  }

  /** A Rare card at its first level, as the evolved play in the reference case stands. */
  private static final int LEVEL = 3;

  /** Side 1's right princess tower, at (14500, 25500). */
  private static final String TOWER = "PrincessTower_1_2";

  private static final String SPECIAL = "BlowdartGoblinEvoProjectile_Special";
  private static final String REGULAR = "BlowdartGoblinEvoProjectile";

  /** What one run of the evolved Dart Goblin at side 1's right princess tower showed. */
  private record Shots(
      List<String> darts,
      List<Integer> dartTicks,
      List<String> areas,
      List<Integer> areaTicks,
      List<int[]> dartHits,
      List<int[]> poison,
      Standard1v1Battle match) {}

  /**
   * Runs an evolved Dart Goblin of side 0 in front of side 1's right princess tower, the towers
   * passive, so it throws dart after dart at the tower.
   */
  private static Shots throwAtTheTower(int ticks) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<String> darts = new ArrayList<>();
    List<Integer> dartTicks = new ArrayList<>();
    List<String> areas = new ArrayList<>();
    List<Integer> areaTicks = new ArrayList<>();
    List<int[]> towerDamage = new ArrayList<>();
    List<int[]> dartHits = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileLaunched(int tick, ProjectileEntity projectile) {
                darts.add(projectile.getData().name());
                dartTicks.add(tick);
              }

              @Override
              public void areaEffectCreated(
                  int tick, AreaEffectEntity areaEffect, String how, String source) {
                areas.add(areaEffect.getData().name());
                areaTicks.add(tick);
              }

              @Override
              public void projectileImpacted(
                  int tick,
                  ProjectileEntity projectile,
                  WorldEntity target,
                  int damage,
                  DamageResult result) {
                if (target.name().equals(TOWER)) {
                  dartHits.add(new int[] {tick, damage});
                }
              }

              @Override
              public void damageDealt(
                  int tick, WorldEntity target, int damage, DamageResult result) {
                if (target.name().equals(TOWER)) {
                  towerDamage.add(new int[] {tick, damage});
                }
              }
            });
    match.deploy(0, records.unit("BlowdartGoblin_EV1"), LEVEL, 0, 14500, 18000);
    for (int tick = 0; tick < ticks; tick++) {
      match.getBattle().step();
    }
    return new Shots(darts, dartTicks, areas, areaTicks, dartHits, towerDamage, match);
  }

  @Test
  @DisplayName(
      "the first dart at a target without a controller is the special one, then each dart that"
          + " brings the controller to its next stack: the first, fourth and seventh")
  void theSpecialDartComesWithEachStack() {
    Shots shots = throwAtTheTower(260);
    assertThat(shots.darts()).hasSizeGreaterThanOrEqualTo(10);
    List<String> first = shots.darts().subList(0, 10);
    List<String> expected = new ArrayList<>();
    for (int dart = 1; dart <= 10; dart++) {
      expected.add(STACK_CHECKS.contains(dart) ? SPECIAL : REGULAR);
    }
    assertThat(first).containsExactlyElementsOf(expected);
  }

  @Test
  @DisplayName(
      "a dart's hit drops a poison area on the tower at once and every second after while the"
          + " crown tower duration lasts, its row chosen by the stack")
  void thePoisonAreasFollowTheStacks() {
    Shots shots = throwAtTheTower(260);
    int firstHit = shots.dartHits().get(0)[0];
    GameRow dart = Shipped.row("projectiles", SPECIAL);
    assertThat(shots.dartHits().get(0)[1])
        .as("a dart's damage")
        .isEqualTo(Shipped.scaled(Shipped.number(dart, "Damage"), dart, LEVEL));
    assertThat(shots.areaTicks()).isNotEmpty();
    assertThat(shots.areaTicks().get(0)).as("the first area, on the first hit").isEqualTo(firstHit);
    for (int i = 1; i < shots.areaTicks().size(); i++) {
      assertThat(shots.areaTicks().get(i) - shots.areaTicks().get(i - 1))
          .as("one area a SpawnInterval")
          .isEqualTo(SPAWN_INTERVAL / 50);
    }
    assertThat(shots.areas().get(0)).isEqualTo(AREAS.get(0));
    assertThat(shots.areas()).contains(AREAS.get(1));
    assertThat(shots.areas().get(shots.areas().size() - 1)).isEqualTo(AREAS.get(2));
  }

  @Test
  @DisplayName(
      "the poison damages the tower once a second from 26 ticks after the first hit, its crown"
          + " tower share of the stack's scaled amount")
  void thePoisonDamagesTheTower() {
    Shots shots = throwAtTheTower(200);
    int firstHit = shots.dartHits().get(0)[0];
    List<int[]> poison = shots.poison();
    assertThat(poison).isNotEmpty();
    // The poison area's first hit falls on the update its HitSpeedOffset of 250 ms starts, the
    // sixth, and its hit action's damage waits its hit speed, a second, after that.
    assertThat(poison.get(0)[0])
        .as("the first poison hit")
        .isEqualTo(firstHit + AREA_HIT_OFFSET / 50 + 1 + POISON_HIT_SPEED / 50);
    // The first stack's amount at this level, and its crown tower share.
    GameRow area = Shipped.row("area_effect_objects", AREAS.get(0));
    int amount = Shipped.scaled(Shipped.numbers(POISON, "DamageList").get(0), area, LEVEL);
    int share = Shipped.number(POISON, "CrownDamageDamageMultiplier");
    assertThat(poison.get(0)[1])
        .as("the first stack's crown tower share")
        .isEqualTo(amount * share / 100);
    for (int i = 1; i < poison.size(); i++) {
      assertThat(poison.get(i)[0] - poison.get(i - 1)[0])
          .as("once a hit speed")
          .isEqualTo(POISON_HIT_SPEED / 50);
    }
    TowerEntity tower = BattleTowers.towerNamed(shots.match().getBattle(), TOWER);
    boolean controller = false;
    for (ActionInstance run : tower.actionHolder().running()) {
      controller |= run.getAction().name().equals(CONTROLLER);
    }
    assertThat(controller).as("the controller runs on the tower").isTrue();
  }

  @Test
  @DisplayName(
      "a dart that lands after its thrower has left re-triggers the controller the thrower started,"
          + " told by the id the dart keeps")
  void aDartOutlivingItsThrowerKeepsItsController() {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<Integer> launches = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileLaunched(int tick, ProjectileEntity projectile) {
                launches.add(tick);
              }
            });
    CharacterEntity goblin =
        match.deploy(0, records.unit("BlowdartGoblin_EV1"), LEVEL, 0, 14500, 18000);
    int tick = 0;
    while (launches.size() < 2 && tick++ < 200) {
      match.getBattle().step();
    }
    assertThat(launches).hasSize(2);
    // The second dart is in flight: its thrower leaves before it lands.
    match.getWorld().kill(goblin, null);
    for (int i = 0; i < 40; i++) {
      match.getBattle().step();
    }
    TowerEntity tower = BattleTowers.towerNamed(match.getBattle(), TOWER);
    int controllers = 0;
    for (ActionInstance run : tower.actionHolder().running()) {
      if (run instanceof BlowdartControllerRun controller) {
        controllers++;
        assertThat(controller.getDarts()).isEqualTo(2);
      }
    }
    assertThat(controllers).as("one controller, re-triggered by the late dart").isEqualTo(1);
  }
}
