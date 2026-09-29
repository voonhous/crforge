package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
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

    // Without LoadFirstHit the timer would stand at its HitSpeed, 4000.
    assertThat(afterLaunch).containsExactly("timer 0 load 3000", "timer 0 load 3000");
  }

  @Test
  @DisplayName(
      "a Hog Rider passes over a building beside it, inside its sight but beyond its side clip")
  void theSideClipSkipsABuildingBeside() {
    Standard1v1Battle match = passiveTowers();
    // 7000 to the side: within the reach of 600 + 9500, beyond it less the side clip of 4000.
    CharacterEntity cannon =
        match.deploy(0, GameData.unit("Cannon"), Standard1v1Battle.DEFAULT_LEVEL, 1, 16000, 13500);
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

    assertThat(priorities).containsExactly(0, 400, 1600);
  }
}
