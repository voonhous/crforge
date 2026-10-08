package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.Shipped.flag;
import static org.crforge.core.battle.Shipped.number;
import static org.crforge.core.battle.Shipped.row;
import static org.crforge.core.battle.Shipped.text;
import static org.crforge.core.battle.Shipped.unitRow;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Row columns whose effect no reference run shows: a unit's LoadFirstHit, which winds its load up
 * again after each hit, and a projectile's fixed spawn priority, which gives each child it makes
 * its own.
 */
class BattleRowColumnsTest {

  private static Standard1v1Battle passiveTowers() {
    return new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
  }

  @Test
  @DisplayName(
      "a Sparky, which loads before its first hit, ends the tick of each launch with its attack"
          + " timer reset and its whole load to wind up again")
  void theSparkyWindsUpAgainAfterEachHit() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity sparky =
        match.deploy(
            0, GameData.unit("ZapMachine"), Standard1v1Battle.DEFAULT_LEVEL, 0, 3500, 14000);
    List<Integer> launchTicks = new ArrayList<>();
    List<String> afterLaunch = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileLaunched(int tick, ProjectileEntity projectile) {
                if (projectile.getOwner() == sparky) {
                  launchTicks.add(tick);
                }
              }

              @Override
              public void afterPostHooks(
                  int tick, List<WorldEntity> present, List<ProjectileEntity> projectiles) {
                if (launchTicks.contains(tick)) {
                  afterLaunch.add(
                      "timer "
                          + sparky.getTargeting().getAttackTimerMs()
                          + " load "
                          + sparky.getTargeting().getLoadTimerMs());
                }
              }
            });
    for (int step = 0; step < 600 && launchTicks.size() < 2; step++) {
      match.getBattle().step();
    }

    // Without LoadFirstHit the timer would stand at its HitSpeed; with it the timer is reset and
    // the whole LoadTime is to wind up again.
    GameRow row = unitRow("ZapMachine");
    assertThat(flag(row, "LoadFirstHit")).isTrue();
    String reset = "timer 0 load " + number(row, "LoadTime");
    assertThat(afterLaunch).containsExactly(reset, reset);
  }

  @Test
  @DisplayName(
      "a Hog Rider passes over a building beside it, inside its sight but beyond its side clip")
  void theSideClipSkipsABuildingBeside() {
    Standard1v1Battle match = passiveTowers();
    // To the side, a quarter of the side clip beyond the reach (the cannon's radius plus the Hog
    // Rider's sight) less that clip: within the one, beyond the other.
    GameRow hogRow = unitRow("HogRider");
    int reach = number(unitRow("Cannon"), "CollisionRadius") + number(hogRow, "SightRange");
    int clip = number(hogRow, "SightClipSide");
    assertThat(clip).as("the Hog Rider clips its sight to the side").isPositive();
    int side = reach - clip + clip / 4;
    CharacterEntity cannon =
        match.deploy(
            0, GameData.unit("Cannon"), Standard1v1Battle.DEFAULT_LEVEL, 1, 9000 + side, 13500);
    CharacterEntity hog =
        match.deploy(0, GameData.unit("HogRider"), Standard1v1Battle.DEFAULT_LEVEL, 0, 9000, 13500);
    for (int step = 0; step < 100 && hog.getTargeting().getReference() == null; step++) {
      match.getBattle().step();
    }

    assertThat(hog.getTargeting().getReference()).isNotNull();
    assertThat(hog.getTargeting().getReference()).isNotSameAs(cannon.getTargetView());
  }

  @Test
  @DisplayName(
      "the Goblin Barrel's impact gives its k-th Goblin a priority of (20k)^2 off its squared"
          + " distance as a candidate")
  void theBarrelsGoblinsCarryTheirPriority() {
    Standard1v1Battle match = passiveTowers();
    List<Integer> priorities = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void characterSpawned(
                  int tick, SpawnHost source, CharacterEntity child, int createdX, int createdY) {
                priorities.add(child.getView().getSquaredDistanceReduction());
              }
            });
    // Played once the kings stand in the holder: the barrel is cast from its side's king.
    match.play(
        1,
        GameData.card("GoblinBarrel"),
        Standard1v1Battle.DEFAULT_LEVEL,
        0,
        3500,
        22000,
        "Barrel");
    for (int step = 0; step < 200 && priorities.isEmpty(); step++) {
      match.getBattle().step();
    }

    // The barrel's goblins, k from 0: (20k)^2 each.
    int goblins =
        number(
            row("projectiles", text(row("spells_other", "GoblinBarrel"), "Projectile")),
            "SpawnCharacterCount");
    List<Integer> expected = new ArrayList<>();
    for (int k = 0; k < goblins; k++) {
      expected.add(20 * k * 20 * k);
    }
    assertThat(priorities).containsExactlyElementsOf(expected);
  }
}
