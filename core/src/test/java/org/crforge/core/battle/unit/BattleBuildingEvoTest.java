package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Tesla, Furnace and Cannon where the reference runs do not take them: whom the Tesla's
 * placement ring reaches and on which update, a building by its square, a crown tower stunned and
 * hit for its own damage; the Furnace's spirit behind a top-side Furnace; and the barrage of a
 * top-side Cannon and a bomb off the arena.
 */
class BattleBuildingEvoTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

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
    CharacterEntity unit = match.deploy(0, GameData.unit(row), LEVEL, side, x, y, name);
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
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    Map<String, Integer> first = reached(match);
    match.deploy(0, GameData.unit("Tesla_EV1"), LEVEL, 0, 9000, 16000, "T");
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
      "a princess tower the ring reaches takes its crown-tower damage, 53, on the buff's first"
          + " visit, and drops the Tesla while it is stunned")
  void aCrownTowerTakesItsOwnDamage() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, true);
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
    match.deploy(0, GameData.unit("Tesla_EV1"), LEVEL, 0, 3500, 20500, "T");
    TowerEntity tower = null;
    for (BattleEntity entity : match.getBattle().getHolder().entities()) {
      if (entity instanceof TowerEntity t && t.side() == 1 && t.getView().getX() == 3500) {
        tower = t;
      }
    }
    assertThat(tower).isNotNull();

    Map<Integer, Boolean> targetsTesla = new HashMap<>();
    for (int tick = 0; tick <= 31; tick++) {
      match.getBattle().step();
      targetsTesla.put(tick, tower.getTargeting().getReference() != null);
    }

    assertThat(first).containsExactly(Map.entry(tower.name(), 20));
    assertThat(damage).containsExactly(21, 53);
    assertThat(targetsTesla)
        .containsEntry(20, true)
        .containsEntry(21, false)
        .containsEntry(30, false)
        .containsEntry(31, true);
  }

  @Test
  @DisplayName(
      "a top-side Furnace launches its left spirit from 6000 high at its point, 1500 to its left"
          + " and 1000 behind it, both read from its own side")
  void aTopSideFurnaceAimsBehindItself() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
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
        match.deploy(0, GameData.unit("Furnace_EV1"), LEVEL, 1, 3500, 22000, "F");
    match.getBattle().step();
    int x = furnace.getView().getX();
    int y = furnace.getView().getY();
    BattleWorld world = match.getWorld();
    furnace
        .actionHolder()
        .start(world.getActions().build("Furnace_EV1_Spawn_Behind_Left", world.binding(furnace)));

    assertThat(launches)
        .containsExactly(
            "Furnace_EV1_Spawn_Spirit_Projectile %d %d 6000 %d %d"
                .formatted(x, y, x - 1500, y + 1000));
  }

  @Test
  @DisplayName(
      "a top-side Cannon on the left drops its bombs at the same points across the arena, ahead of"
          + " it toward the bottom: 1500 and 8500 below it")
  void aTopSideBarrageFallsAheadOfIt() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
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
    match.deploy(0, GameData.unit("Cannon_EV1"), LEVEL, 1, 3500, 21500, "C");
    match.getBattle().step();

    assertThat(bombs)
        .containsExactly(
            "1500 20000 1",
            "6500 20000 1",
            "11500 20000 1",
            "16500 20000 1",
            "1000 13000 1",
            "5000 13000 1",
            "9000 13000 1",
            "13000 13000 1",
            "17000 13000 1");
  }

  @Test
  @DisplayName("a bomb that would fall off the arena, which ends the barrage, is refused")
  void aBombOffTheArenaIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    match.deploy(0, GameData.unit("Cannon_EV1"), LEVEL, 1, 14500, 5000, "C");

    assertThatThrownBy(() -> match.getBattle().step())
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("off the arena");
  }
}
