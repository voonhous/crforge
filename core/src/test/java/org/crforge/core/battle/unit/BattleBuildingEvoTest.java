/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.BattleAction;
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
 * The evolved Tesla, Furnace and Cannon where the reference runs do not take them: whom the Tesla's
 * placement ring reaches and on which update, a building by its square, a crown tower stunned and
 * hit for its own damage; the Furnace's spirit behind a top-side Furnace; and the barrage of a
 * top-side Cannon and a bomb off the arena.
 *
 * <p>The scenes write the placement ring's growth (from 1 to 6000 over 1500 ms, a visit a step),
 * the sizes of the units it reaches (the Knight's and the Minion's 500, the Cannon's 600, a
 * princess tower's 1000) and the towers' places, so the ring's ticks are their own. The tower's
 * damage, the stun's time, the Furnace's spirit and the Cannon's bombs are read from the rows.
 */
class BattleBuildingEvoTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  @TempDir static Path folder;

  /** The configured tables with the scenes' columns written, and their records. */
  private static GameTables tables;

  private static BattleRecords records;

  @BeforeAll
  static void writeTheScenes() throws IOException {
    GameData.altered(
        folder,
        "area_effect_objects",
        rows ->
            GameData.columns(rows, "Tesla_EV1_AppearStun")
                .put("Radius", 1)
                .put("MaxRadius", 6000)
                .put("LifeDuration", 1500)
                .put("HitSpeed", 50));
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "Knight").put("CollisionRadius", 500);
          GameData.columns(rows, "Minion").put("CollisionRadius", 500);
        });
    GameData.alterLoaded(
        folder,
        "buildings",
        rows -> {
          GameData.columns(rows, "Cannon").put("CollisionRadius", 600);
          GameData.columns(rows, "PrincessTower").put("CollisionRadius", 1000);
        });
    GameData.alterLoaded(folder, "spawn_groups", GameData::placeTowers);
    tables = GameTables.load(folder);
    records = new BattleRecords(tables);
  }

  /** The one number an expression of an action's field multiplies by, read from its text. */
  private static int factor(String action, String field, String pattern) {
    String expression = Shipped.text(action, field);
    Matcher factor = Pattern.compile(pattern).matcher(expression);
    assertThat(factor.find()).as(expression).isTrue();
    return Integer.parseInt(factor.group(1));
  }

  /** Whom the ring's hit action was scheduled on, and on which tick. */
  private static Map<String, Integer> reached(Standard1v1Battle match) {
    Map<String, Integer> first = new HashMap<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void onHitActionScheduled(
                  int tick, AreaEffectEntity areaEffect, WorldEntity target, BattleAction action) {
                assertThat(first).as("one hit per target").doesNotContainKey(target.name());
                first.put(target.name(), tick);
              }
            });
    return first;
  }

  /** A unit placed on tick 0 that neither walks nor targets, so it stands where it is put. */
  private static CharacterEntity standing(
      Standard1v1Battle match, int side, String row, int x, int y, String name) {
    CharacterEntity unit = match.deploy(0, records.unit(row), LEVEL, side, x, y, name);
    unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    unit.setActive(CharacterEntity.TARGETING_SLOT, false);
    return unit;
  }

  @Test
  @DisplayName(
      "the placement ring reaches each enemy once, on the first update whose radius passes it: a"
          + " Knight 2000 away on 8, a Minion 3500 away on 15, a Cannon 4500 away on 20 and one on"
          + " the diagonal on 17 by its square, a Knight 4501 away on 21 and one 6000 away on 28;"
          + " never a Knight 6600 away or a friend")
  void theRingReachesByDistance() {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    Map<String, Integer> first = reached(match);
    match.deploy(0, records.unit("Tesla_EV1"), LEVEL, 0, 9000, 16000, "T");
    standing(match, 1, "Knight", 9000, 18000, "E1");
    standing(match, 1, "Minion", 12500, 16000, "M");
    standing(match, 1, "Cannon", 9000, 11500, "C");
    standing(match, 1, "Knight", 15000, 16000, "E2");
    standing(match, 1, "Knight", 9000, 22600, "E3");
    standing(match, 0, "Knight", 9000, 14500, "F");
    standing(match, 1, "Knight", 4499, 16000, "E4");
    standing(match, 1, "Cannon", 12000, 19000, "C2");
    for (int i = 0; i < 45; i++) {
      match.getBattle().step();
    }

    assertThat(first)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("E1", 8, "M", 15, "C", 20, "C2", 17, "E4", 21, "E2", 28));
  }

  @Test
  @DisplayName(
      "a princess tower the ring reaches takes its buff's crown-tower damage on the buff's first"
          + " visit, and drops the Tesla while it is stunned")
  void aCrownTowerTakesItsOwnDamage() {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, true);
    Map<String, Integer> first = reached(match);
    List<Integer> damage = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void buffDamaged(
                  int tick,
                  WorldEntity target,
                  BuffInstance buff,
                  int amount,
                  int hitPointsBefore,
                  DamageResult result) {
                damage.add(tick);
                damage.add(amount);
              }
            });
    match.deploy(0, records.unit("Tesla_EV1"), LEVEL, 0, 3500, 20500, "T");
    TowerEntity tower = null;
    for (BattleEntity entity : match.getBattle().getHolder().entities()) {
      if (entity instanceof TowerEntity t && t.side() == 1 && t.getView().getX() == 3500) {
        tower = t;
      }
    }
    assertThat(tower).isNotNull();

    Map<Integer, Boolean> targetsTesla = new HashMap<>();
    // Stunned for the buff's SpawnTime from the visit, a part step counting as one (500 ms is ten
    // steps).
    int stunned = (Shipped.number("Tesla_EV1_SpawnBuff", "SpawnTime") + 49) / 50;
    for (int tick = 0; tick <= 21 + stunned; tick++) {
      match.getBattle().step();
      targetsTesla.put(tick, tower.getTargeting().getReference() != null);
    }

    assertThat(first).containsExactly(Map.entry(tower.name(), 20));
    // The buff's CrownTowerDamagePerHit at the level, dealt on its first visit.
    GameRow buff = Shipped.row("character_buffs", "Tesla_EV1_WithDamage");
    assertThat(damage)
        .containsExactly(
            21, Shipped.scaled(Shipped.number(buff, "CrownTowerDamagePerHit"), buff, LEVEL));
    assertThat(targetsTesla)
        .containsEntry(20, true)
        .containsEntry(21, false)
        .containsEntry(20 + stunned, false)
        .containsEntry(21 + stunned, true);
  }

  @Test
  @DisplayName(
      "a top-side Furnace launches its left spirit from its height offset at its point, to its"
          + " left and behind it by its expressions' amounts, both read from its own side")
  void aTopSideFurnaceAimsBehindItself() {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<String> launches = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void actionProjectileLaunched(
                  int tick,
                  WorldEntity owner,
                  String action,
                  int phase,
                  ProjectileEntity projectile) {
                launches.add(
                    "%s %d %d %d %d %d"
                        .formatted(
                            projectile.getData().name(),
                            projectile.getStartX(),
                            projectile.getStartY(),
                            projectile.getStartZ(),
                            projectile.getAimX(),
                            projectile.getAimY()));
              }
            });
    CharacterEntity furnace =
        match.deploy(0, records.unit("Furnace_EV1"), LEVEL, 1, 3500, 22000, "F");
    match.getBattle().step();
    int x = furnace.getView().getX();
    int y = furnace.getView().getY();
    BattleWorld world = match.getWorld();
    furnace
        .actionHolder()
        .start(world.getActions().build("Furnace_EV1_Spawn_Behind_Left", world.binding(furnace)));

    // The height, and the amounts its target expressions move it by (1500 across, 1000 along).
    String left = "Furnace_EV1_Spawn_Behind_Left";
    int z = Shipped.number(left, "StartPositionZOffset");
    int across = factor(left, "TargetExprX", "\\* (\\d+)\\)");
    int along = factor(left, "TargetExprY", "\\* (\\d+) \\+ y");
    assertThat(launches)
        .containsExactly(
            "Furnace_EV1_Spawn_Spirit_Projectile %d %d %d %d %d"
                .formatted(x, y, z, x - across, y + along));
  }

  @Test
  @DisplayName(
      "a top-side Cannon on the left drops its bombs at the same points across the arena, ahead of"
          + " it toward the bottom: its vertical offsets below it")
  void aTopSideBarrageFallsAheadOfIt() {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<String> bombs = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void barrageStepped(
                  int tick, CharacterEntity owner, String action, List<AreaEffectEntity> made) {
                for (AreaEffectEntity a : made) {
                  bombs.add(a.getX() + " " + a.getY() + " " + a.side());
                }
              }
            });
    match.deploy(0, records.unit("Cannon_EV1"), LEVEL, 1, 3500, 21500, "C");
    match.getBattle().step();

    // Each bomb at its absolute offset across the arena and its vertical offset ahead of the
    // Cannon, half tiles both; ahead is down the length for the top side.
    List<Integer> across = Shipped.numbers("Cannon_EV1_barrage", "BombAbsoluteHorizontalOffsets");
    List<Integer> ahead = Shipped.numbers("Cannon_EV1_barrage", "BombVerticalOffsets");
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < ahead.size(); i++) {
      expected.add((across.get(i) * 500) + " " + (21500 - ahead.get(i) * 500) + " 1");
    }
    assertThat(bombs).containsExactlyElementsOf(expected);
  }

  @Test
  @DisplayName("a bomb that would fall off the arena, which ends the barrage, is refused")
  void aBombOffTheArenaIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    match.deploy(0, records.unit("Cannon_EV1"), LEVEL, 1, 14500, 5000, "C");

    assertThatThrownBy(() -> match.getBattle().step())
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("off the arena");
  }
}
