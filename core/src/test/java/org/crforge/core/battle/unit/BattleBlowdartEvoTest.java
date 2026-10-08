package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Dart Goblin: its starting action picks each dart, the special one when the poison
 * controller on the target is about to reach its next stack; the darts' hits run that controller on
 * the target, which drops a poison area effect on it every second while it lasts, and each area's
 * hits keep one poison damage run going on the target.
 */
class BattleBlowdartEvoTest {

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
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
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
    match.deploy(0, GameData.unit("BlowdartGoblin_EV1"), LEVEL, 0, 14500, 18000);
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
    assertThat(first)
        .containsExactly(
            SPECIAL, REGULAR, REGULAR, SPECIAL, REGULAR, REGULAR, SPECIAL, REGULAR, REGULAR,
            REGULAR);
  }

  @Test
  @DisplayName(
      "a dart's hit drops a poison area on the tower at once and every second after while the"
          + " crown tower duration lasts, its row chosen by the stack")
  void thePoisonAreasFollowTheStacks() {
    Shots shots = throwAtTheTower(260);
    int firstHit = shots.dartHits().get(0)[0];
    assertThat(shots.dartHits().get(0)[1]).as("a dart's damage").isEqualTo(71);
    assertThat(shots.areaTicks()).isNotEmpty();
    assertThat(shots.areaTicks().get(0)).as("the first area, on the first hit").isEqualTo(firstHit);
    for (int i = 1; i < shots.areaTicks().size(); i++) {
      assertThat(shots.areaTicks().get(i) - shots.areaTicks().get(i - 1))
          .as("one area a second")
          .isEqualTo(20);
    }
    assertThat(shots.areas().get(0)).isEqualTo("BlowDartPoisonAeO_baseDamage");
    assertThat(shots.areas()).contains("BlowDartPoisonAeO_midDamage");
    assertThat(shots.areas().get(shots.areas().size() - 1))
        .isEqualTo("BlowDartPoisonAeO_fullDamage");
  }

  @Test
  @DisplayName(
      "the poison damages the tower once a second from 26 ticks after the first hit, a quarter of"
          + " the stack's scaled amount on a crown tower")
  void thePoisonDamagesTheTower() {
    Shots shots = throwAtTheTower(200);
    int firstHit = shots.dartHits().get(0)[0];
    List<int[]> poison = shots.poison();
    assertThat(poison).isNotEmpty();
    // The poison area's first hit falls on the update its HitSpeedOffset of 250 ms starts, the
    // sixth, and its hit action's damage waits a second after that.
    assertThat(poison.get(0)[0]).as("the first poison hit").isEqualTo(firstHit + 26);
    assertThat(poison.get(0)[1]).as("25 at this level is 30, a quarter of it 7").isEqualTo(7);
    for (int i = 1; i < poison.size(); i++) {
      assertThat(poison.get(i)[0] - poison.get(i - 1)[0]).as("once a second").isEqualTo(20);
    }
    TowerEntity tower = BattleMusketeerRunTest.towerNamed(shots.match().getBattle(), TOWER);
    boolean controller = false;
    for (ActionInstance run : tower.actionHolder().running()) {
      controller |= run.getAction().name().equals("blowdart_evo_darts_controller");
    }
    assertThat(controller).as("the controller runs on the tower").isTrue();
  }

  @Test
  @DisplayName(
      "a dart that lands after its thrower has left re-triggers the controller the thrower started,"
          + " told by the id the dart keeps")
  void aDartOutlivingItsThrowerKeepsItsController() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
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
        match.deploy(0, GameData.unit("BlowdartGoblin_EV1"), LEVEL, 0, 14500, 18000);
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
    TowerEntity tower = BattleMusketeerRunTest.towerNamed(match.getBattle(), TOWER);
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
