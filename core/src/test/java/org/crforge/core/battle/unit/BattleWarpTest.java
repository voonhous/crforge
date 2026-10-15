/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.BattleTowers;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The Boss Bandit's warp where the reference runs do not take it: a landing on water, past the
 * arena's edge, on the other side and inside a tower's footprint, each where the game lands it; and
 * a warp with a projectile aimed at the unit, which loses the unit as its target and lands on
 * nothing, unless its row keeps its target, when it follows the unit and lands on it.
 */
class BattleWarpTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The warp row the Boss Bandit's ability runs. */
  private static final String WARP = "BossBandit_ability_warp";

  /**
   * The warp's distance along the length, toward the unit's own side: the row's WarpY, negative
   * toward the bottom for side 0 (-6000).
   */
  private static final int WARP_Y = Shipped.number(WARP, "WarpY");

  /** A homing projectile, the princess tower's arrow. */
  private static final String ARROW = "TowerPrincessProjectile";

  /** Side 1's left princess tower. */
  private static final String TOWER = "PrincessTower_1_1";

  /** Long enough for a Musketeer to deploy and fire. */
  private static final int TICKS = 120;

  /**
   * Each case names the point the warp aims at; the unit is placed one warp away from it, so that
   * the warp's distance aims it there (with 6000: from 12000, 14000, 22000, 4000 and 19500).
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({
    // Back toward its own side, on either side.
    "side 0, 0, 3500, 6000, 3500, 6000",
    "side 1, 1, 6000, 20000, 6000, 20000",
    // The river cell and the three after it are water: the first dry row, on the far bank.
    "water, 0, 6000, 16000, 6000, 17250",
    // Clamped to the edge, a corner cell that is blocked: two rows in.
    "edge, 0, 3500, -2000, 3500, 1250",
    // A tower's cells carry a lane, so they are not blocked: it lands inside the footprint.
    "into a tower, 1, 3500, 25500, 3500, 25500"
  })
  @DisplayName("a warp lands where the game lands it")
  void theWarpLandsWhereTheGameDoes(
      String name, int side, int x, int aimY, int landingX, int landingY) {
    // Side 0 warps by WarpY along the length, side 1 the other way.
    int y = side == 0 ? aimY - WARP_Y : aimY + WARP_Y;
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity bandit =
        match.deploy(0, GameData.unit("BossBandit"), LEVEL, side, x, y, "BossBandit");
    match.getBattle().step();
    assertThat(new int[] {bandit.getView().getX(), bandit.getView().getY()})
        .as("where it stands as it warps")
        .containsExactly(x, y);

    bandit.actionHolder().start(warp(match, bandit));

    assertThat(new int[] {bandit.getView().getX(), bandit.getView().getY()})
        .containsExactly(landingX, landingY);
  }

  @Test
  @DisplayName(
      "a warp drops the projectile aimed at the unit: it flies on to where the unit stood and"
          + " lands on nothing")
  void aWarpDropsTheProjectileAimedAtTheUnit() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity bandit =
        match.deploy(0, GameData.unit("BossBandit"), LEVEL, 0, 3500, 12000, "BossBandit");
    bandit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    CharacterEntity musketeer =
        match.deploy(0, GameData.unit("Musketeer"), LEVEL, 1, 3500, 17000, "Musketeer");
    musketeer.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    for (int tick = 0; tick < TICKS && aimedAt(match, bandit) == null; tick++) {
      match.getBattle().step();
    }
    ProjectileEntity shot = aimedAt(match, bandit);
    assertThat(shot).as("a projectile in flight at the Boss Bandit").isNotNull();
    assertThat(bandit.getView().getPendingDamageAmount()).as("its damage on its way").isPositive();
    int standX = bandit.getView().getX();
    int standY = bandit.getView().getY();
    int hitPoints = bandit.getHitPoints().getHitPoints();

    bandit.actionHolder().start(warp(match, bandit));

    assertThat(shot.getTarget()).as("the target the reset drops").isNull();
    assertThat(bandit.getView().getPendingDamageAmount()).isZero();
    assertThat(new int[] {shot.getAimX(), shot.getAimY()})
        .as("it still aims where the Boss Bandit stood")
        .containsExactly(standX, standY);
    int lastX = shot.getX();
    int lastY = shot.getY();
    for (int tick = 0;
        tick < TICKS && match.getBattle().getHolder().entities().contains(shot);
        tick++) {
      lastX = shot.getX();
      lastY = shot.getY();
      match.getBattle().step();
    }
    assertThat(match.getBattle().getHolder().entities()).as("it has landed").doesNotContain(shot);
    assertThat(new int[] {shot.getX(), shot.getY()})
        .as("on the point it aimed at, from " + lastX + ", " + lastY)
        .containsExactly(standX, standY);
    assertThat(bandit.getHitPoints().getHitPoints()).as("on nothing").isEqualTo(hitPoints);
  }

  @Test
  @DisplayName(
      "a warp leaves a projectile whose row keeps its target aimed at the unit: with no damage on"
          + " its way it follows the unit to where the warp put it and lands on it")
  void aWarpKeepsTheTargetOfAProjectileWhoseRowKeepsIt() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity bandit =
        match.deploy(0, GameData.unit("BossBandit"), LEVEL, 0, 3500, 12000, "BossBandit");
    bandit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    match.getBattle().step();
    // A homing projectile of a row that keeps its target through the reset and deals nothing, as
    // the rows that clear AllowResetTarget do.
    ProjectileData keeper =
        GameData.records().projectile(ARROW).toBuilder().allowResetTarget(false).damage(0).build();
    ProjectileEntity shot = launchAt(match, keeper, bandit);
    match.getBattle().step();
    assertThat(shot.getTarget()).as("in flight at the Boss Bandit").isSameAs(bandit);
    assertThat(shot.isPendingRegistered()).as("its damage of 0 on its way").isTrue();
    int hitPoints = bandit.getHitPoints().getHitPoints();
    int standY = bandit.getView().getY();

    bandit.actionHolder().start(warp(match, bandit));

    assertThat(bandit.getView().getY()).as("the warp has moved it").isNotEqualTo(standY);
    assertThat(shot.getTarget()).as("the target the reset keeps").isSameAs(bandit);
    assertThat(bandit.getView().getPendingDamageAmount()).isZero();
    for (int tick = 0; tick < TICKS && !shot.isReleased(); tick++) {
      match.getBattle().step();
      if (!shot.isReleased()) {
        assertThat(new int[] {shot.getAimX(), shot.getAimY()})
            .as("it re-aims at where the Boss Bandit stands now")
            .containsExactly(bandit.getView().getX(), bandit.getView().getY());
      }
    }
    assertThat(shot.isReleased()).as("it has reached the Boss Bandit").isTrue();
    assertThat(new int[] {shot.getX(), shot.getY()})
        .containsExactly(bandit.getView().getX(), bandit.getView().getY());
    assertThat(bandit.getView().getPendingDamageAmount()).as("handed back from 0: 0").isZero();
    assertThat(bandit.getHitPoints().getHitPoints()).isEqualTo(hitPoints);
  }

  @Test
  @DisplayName(
      "a warp with a projectile aimed at the unit whose row keeps its target and has damage on its"
          + " way is refused")
  void aWarpWithATargetKeepingProjectileCarryingDamageIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity bandit =
        match.deploy(0, GameData.unit("BossBandit"), LEVEL, 0, 3500, 12000, "BossBandit");
    bandit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    match.getBattle().step();
    ProjectileData keeper =
        GameData.records().projectile(ARROW).toBuilder().allowResetTarget(false).build();
    ProjectileEntity shot = launchAt(match, keeper, bandit);
    match.getBattle().step();
    assertThat(bandit.getView().getPendingDamageAmount()).as("its damage on its way").isPositive();

    assertThatThrownBy(() -> bandit.actionHolder().start(warp(match, bandit)))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(shot.name())
        .hasMessageContaining("keeps its target through the reset with damage on its way");
  }

  /**
   * A projectile of the row launched by side 1's left princess tower at the unit, handed to the
   * holder.
   */
  private static ProjectileEntity launchAt(
      Standard1v1Battle match, ProjectileData data, CharacterEntity unit) {
    TowerEntity tower = BattleTowers.towerNamed(match.getBattle(), TOWER);
    List<ProjectileEntity> launched = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileLaunched(int t, ProjectileEntity projectile) {
                launched.add(projectile);
              }
            });
    match.getWorld().launchAt(tower, data, unit);
    assertThat(launched).as("the launched projectile").hasSize(1);
    return launched.get(0);
  }

  /** The warp row, built for the unit. */
  private static BattleAction warp(Standard1v1Battle match, CharacterEntity unit) {
    return GameData.actions().build(WARP, match.getWorld().binding(unit));
  }

  /** The first live projectile that has the unit as its target, or null. */
  private static ProjectileEntity aimedAt(Standard1v1Battle match, CharacterEntity unit) {
    for (BattleEntity entity : match.getBattle().getHolder().entities()) {
      if (entity instanceof ProjectileEntity p && p.getTarget() == unit) {
        return p;
      }
    }
    return null;
  }
}
