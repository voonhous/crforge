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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.BattleTowers;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When the evolved Dart Goblin's poison damage lands: its run hands each hit to the game's damage
 * entry, which queues it for the holder's damage drain, after every post-hook. So a target the
 * poison kills is still alive through the post-hooks of that tick, and dies at the drain: a
 * spawner's child made in a post-hook of that tick still finds it with its hit points.
 *
 * <p>The scene: the evolved Dart Goblin of the bottom side throws at the top side's right princess
 * tower until a poison damage run is going on it, then leaves; the poison keeps going on the tower
 * (the controller's crown tower duration is written longer than the scene). A Witch of the bottom
 * side, at its own end of the arena and written to make one child in each post-hook, is played;
 * once every dart has landed the tower is left with one hit point, so the next poison hit kills it.
 */
class BattleBlowdartPoisonDrainTest {

  private static final String CONTROLLER = "blowdart_evo_darts_controller";

  private static final String POISON = "blowdart_aeo_evo_poison";

  private static final List<String> AREAS =
      List.of(
          "BlowDartPoisonAeO_baseDamage",
          "BlowDartPoisonAeO_midDamage",
          "BlowDartPoisonAeO_fullDamage");

  /** A Rare card at its first level. */
  private static final int LEVEL = 3;

  /** Side 1's right princess tower, at (14500, 25500). */
  private static final String TOWER = "PrincessTower_1_2";

  /** The Witch's time between its waves: one step, so it makes a child in each post-hook. */
  private static final int STEP_MS = 50;

  /** Steps after the Dart Goblin leaves by which every dart it threw has landed. */
  private static final int DARTS_LANDED = 100;

  /** The steps the scene may take to reach each of its points. */
  private static final int LIMIT = 400;

  @TempDir static Path folder;

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
              .put("MaxStacks", 3)
              .put("SpawnInterval", 1000)
              .put("CrownTowerDuration", 60000)
              .put("Duration", 999999);
          ArrayNode checks = controller.putArray("StackAmountChecks");
          checks.add(1).add(4).add(7);
          fields(rows, POISON)
              .put("HitSpeed", 1000)
              .put("Duration", 1000)
              .put("CrownTowerDuration", 1000);
        });
    GameData.alterLoaded(
        folder,
        "area_effect_objects",
        rows -> {
          for (String area : AREAS) {
            GameData.columns(rows, area)
                .put("HitSpeedOffset", 250)
                .put("HitSpeed", 250)
                .put("LifeDuration", 1000)
                .put("Radius", 1500);
          }
        });
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "BlowdartGoblin_EV1")
              .put("HitSpeed", 800)
              .put("LoadTime", 450)
              .put("DeployTime", 1000)
              .put("Range", 6500)
              .put("SightRange", 7000)
              .put("ProjectileStartRadius", 1200)
              .put("ProjectileStartZ", 1600);
          GameData.columns(rows, "Witch")
              .put("SpawnCharacter", "Skeleton")
              .put("SpawnNumber", 1)
              .put("SpawnPauseTime", STEP_MS)
              .put("SpawnStartTime", 0);
        });
    GameData.alterLoaded(
        folder,
        "projectiles",
        rows -> {
          GameData.columns(rows, "BlowdartGoblinEvoProjectile").put("Speed", 800);
          GameData.columns(rows, "BlowdartGoblinEvoProjectile_Special").put("Speed", 800);
        });
    GameData.alterLoaded(folder, "spawn_groups", GameData::placeTowers);
    tables = GameTables.load(folder);
    records = new BattleRecords(tables);
  }

  /** True while a poison damage run is going on the entity. */
  private static boolean poisoned(WorldEntity entity) {
    for (ActionInstance run : entity.actionHolder().running()) {
      if (run instanceof BlowdartDamageRun) {
        return true;
      }
    }
    return false;
  }

  @Test
  @DisplayName(
      "the poison's damage lands at the drain: on the step it kills the tower, a child made in a"
          + " post-hook of that step still finds the tower with its hit points")
  void thePoisonKillsAtTheDrain() {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    TowerEntity tower = BattleTowers.towerNamed(match.getBattle(), TOWER);
    int[] killed = {-1};
    // The tower's hit points as each step's Witch child is made, by step.
    Map<Integer, Integer> atSpawn = new HashMap<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void damageDealt(
                  int tick, WorldEntity target, int damage, DamageResult result) {
                if (target == tower && result.died()) {
                  killed[0] = tick;
                }
              }

              @Override
              public void characterSpawned(
                  int tick, SpawnHost source, CharacterEntity child, int x, int y) {
                if (source instanceof CharacterEntity spawner
                    && spawner.getData().name().equals("Witch")) {
                  atSpawn.put(tick, tower.getHitPoints().getHitPoints());
                }
              }
            });
    CharacterEntity goblin =
        match.deploy(0, records.unit("BlowdartGoblin_EV1"), LEVEL, 0, 14500, 18000);
    while (!poisoned(tower) && match.getBattle().getTick() < LIMIT) {
      match.getBattle().step();
    }
    assertThat(poisoned(tower)).as("a poison damage run goes on the tower").isTrue();
    match.getWorld().kill(goblin, null);
    match.deploy(match.getBattle().getTick() + 1, records.unit("Witch"), LEVEL, 0, 3500, 5000);
    for (int i = 0; i < DARTS_LANDED; i++) {
      match.getBattle().step();
    }
    assertThat(poisoned(tower)).as("the poison keeps going after the darts").isTrue();
    tower.getHitPoints().setHitPoints(1);
    int until = match.getBattle().getTick() + LIMIT;
    while (killed[0] < 0 && match.getBattle().getTick() < until) {
      match.getBattle().step();
    }

    assertThat(killed[0]).as("the poison kills the tower").isNotNegative();
    assertThat(atSpawn)
        .as("the Witch makes a child on the step of the kill")
        .containsKey(killed[0]);
    assertThat(atSpawn.get(killed[0]))
        .as("the tower still has its hit point in the post-hooks of the step the poison kills it")
        .isEqualTo(1);
  }
}
