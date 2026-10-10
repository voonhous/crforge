/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.BattleTowers;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.target.ReferenceValidator;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingState;
import org.crforge.core.pathfinding.target.ValidatorQueries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The damage on its way to a target where no reference run shows it deciding: the pending-damage
 * slot's sum and duration, an arrow's damage from its start to its landing, the validator's
 * questions the battle answers, the rule's keep of a timed unit, and a morph's copy of it.
 */
class BattlePendingDamageTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static Standard1v1Battle passiveTowers() {
    return new Standard1v1Battle(GameData.tables(), LEVEL, false);
  }

  /** The arrow in flight at the unit, once the holder has admitted it; null before. */
  private static ProjectileEntity arrowAt(Standard1v1Battle match, WorldEntity unit) {
    for (BattleEntity entity : match.getBattle().getHolder().entities()) {
      if (entity instanceof ProjectileEntity p && p.getTarget() == unit) {
        return p;
      }
    }
    return null;
  }

  @Test
  @DisplayName(
      "the pending slot sums the damage and raises the duration to the flight rounded up to 50,"
          + " at most 1000; a hand-back leaves the duration, and a sum below zero is kept")
  void thePendingSlotSumsTheDamageAndRaisesTheDuration() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 3500, 10000);
    GridEntity view = knight.getView();

    knight.addPendingDamage(100, 170);
    assertThat(view.getPendingDamageAmount()).isEqualTo(100);
    assertThat(view.getPendingDamageDurationMs()).as("170 rounded up").isEqualTo(200);
    knight.addPendingDamage(50, 120);
    assertThat(view.getPendingDamageAmount()).isEqualTo(150);
    assertThat(view.getPendingDamageDurationMs()).as("raised, never lowered").isEqualTo(200);
    knight.addPendingDamage(10, 5000);
    assertThat(view.getPendingDamageDurationMs()).as("at most 1000").isEqualTo(1000);
    knight.addPendingDamage(-160, -1);
    assertThat(view.getPendingDamageAmount()).isZero();
    assertThat(view.getPendingDamageDurationMs()).as("a hand-back leaves it").isEqualTo(1000);
    knight.addPendingDamage(-5, -1);
    assertThat(view.getPendingDamageAmount()).as("a sum below zero is kept").isEqualTo(-5);
  }

  @Test
  @DisplayName(
      "a tower's arrow puts its damage on its target as the holder admits it, with its flight"
          + " time, which each visit counts down, and hands the damage back before it lands")
  void anArrowPutsItsDamageOnItsTargetUntilItLands() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, true);
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 3500, 19000);
    GridEntity view = knight.getView();
    List<String> landed = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileImpacted(
                  int tick,
                  ProjectileEntity projectile,
                  WorldEntity target,
                  int damage,
                  DamageResult result) {
                if (target == knight) {
                  landed.add(damage + " with " + view.getPendingDamageAmount() + " pending");
                }
              }
            });
    ProjectileEntity arrow = null;
    for (int step = 0; step < 200 && arrow == null; step++) {
      match.getBattle().step();
      arrow = arrowAt(match, knight);
    }

    assertThat(arrow).as("the princess tower fires at the Knight").isNotNull();
    assertThat(arrow.isPendingRegistered()).isTrue();
    int distance =
        FixedMath.guardedDistance(arrow.getX() - view.getX(), arrow.getY() - view.getY());
    int flightMs = distance * 50 / arrow.getData().speed();
    assertThat(view.getPendingDamageAmount()).isEqualTo(arrow.damage());
    assertThat(view.getPendingDamageDurationMs()).isEqualTo((flightMs + 49) / 50 * 50);
    int duration = view.getPendingDamageDurationMs();
    while (!arrow.isReleased()) {
      match.getBattle().step();
      duration = Math.max(duration, 50) - 50;
      assertThat(view.getPendingDamageDurationMs())
          .as("counted down each visit")
          .isEqualTo(duration);
    }
    assertThat(landed).containsExactly(arrow.damage() + " with 0 pending");
    assertThat(arrow.isPendingRegistered()).isFalse();
  }

  @Test
  @DisplayName(
      "the lethal test: nothing under a shield or on an untouchable unit, else the damage, at"
          + " least 1, against the hit points left")
  void theLethalTest() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 3500, 10000);
    CharacterEntity guard =
        match.deploy(0, GameData.unit("SkeletonWarrior"), LEVEL, 0, 9000, 10000);
    match.getBattle().step();
    ValidatorQueries queries = match.getWorld().getValidatorQueries();
    TargetView target = knight.getTargetView();

    int hitPoints = knight.getHitPoints().getHitPoints();
    assertThat(queries.pendingDamageAccepted(target, hitPoints)).isTrue();
    assertThat(queries.pendingDamageAccepted(target, hitPoints - 1)).isFalse();
    knight.getHitPoints().setHitPoints(1);
    assertThat(queries.pendingDamageAccepted(target, 0)).as("at least 1").isTrue();
    knight.getUnit().timers().setDashImmunityRemainingMs(100);
    assertThat(queries.pendingDamageAccepted(target, 1000)).as("untouchable").isFalse();

    int guardHitPoints = guard.getHitPoints().getHitPoints();
    assertThat(guard.getHitPoints().getShield()).isPositive();
    assertThat(queries.pendingDamageAccepted(guard.getTargetView(), guardHitPoints))
        .as("its shield up")
        .isFalse();
    guard.getHitPoints().setShield(0);
    assertThat(queries.pendingDamageAccepted(guard.getTargetView(), guardHitPoints)).isTrue();
  }

  @Test
  @DisplayName(
      "the dash test: a unit whose row is immune while it dashes, with its targeting on and a dash"
          + " wind-up that ends no later than the damage lands")
  void theDashTest() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity bandit = match.deploy(0, GameData.unit("Assassin"), LEVEL, 0, 3500, 10000);
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 9000, 10000);
    match.getBattle().step();
    ValidatorQueries queries = match.getWorld().getValidatorQueries();
    TargetingState t = bandit.getTargeting();
    bandit.setActive(CharacterEntity.TARGETING_SLOT, true);

    t.setDashWindupMs(300);
    assertThat(queries.pendingDamageIsRecent(bandit.getTargetView(), 300)).isTrue();
    assertThat(queries.pendingDamageIsRecent(bandit.getTargetView(), 250)).isFalse();
    bandit.setActive(CharacterEntity.TARGETING_SLOT, false);
    assertThat(queries.pendingDamageIsRecent(bandit.getTargetView(), 300))
        .as("its targeting off")
        .isFalse();
    bandit.setActive(CharacterEntity.TARGETING_SLOT, true);
    t.setDashWindupMs(0);
    assertThat(queries.pendingDamageIsRecent(bandit.getTargetView(), 300))
        .as("no wind-up")
        .isFalse();
    knight.getTargeting().setDashWindupMs(300);
    assertThat(queries.pendingDamageIsRecent(knight.getTargetView(), 300))
        .as("a row without the immunity")
        .isFalse();
  }

  @Test
  @DisplayName("the healing test asks the buffs a unit carries for a heal at their first level")
  void theHealingTest() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 3500, 10000);
    match.getBattle().step();
    ValidatorQueries queries = match.getWorld().getValidatorQueries();

    assertThat(queries.pendingDamageBuffHolds(knight.getTargetView())).isFalse();
    knight
        .getBuffs()
        .apply(
            GameData.records().buff("BattleHealerAll"), 1000, knight.getPackedLevel(), knight, 0);
    assertThat(queries.pendingDamageBuffHolds(knight.getTargetView())).isTrue();
  }

  @Test
  @DisplayName(
      "the level the rule hands over is the unit's own, and follows a level change; at it the"
          + " battle answers the row's full hit points")
  void theKeyIsTheUnitsLevel() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 3500, 10000);
    match.getBattle().step();
    ValidatorQueries queries = match.getWorld().getValidatorQueries();
    TargetView target = knight.getTargetView();

    assertThat(target.getPendingDamageKey()).isEqualTo(knight.getPackedLevel());
    assertThat(queries.committedDamage(target, target.getPendingDamageKey()))
        .isEqualTo(knight.getHitPoints().getMaximum());
    knight.changeLevel(knight.getPackedLevel() + 1);
    assertThat(target.getPendingDamageKey()).isEqualTo(knight.getPackedLevel());
    assertThat(queries.committedDamage(target, target.getPendingDamageKey()))
        .isEqualTo(knight.getHitPoints().getMaximum());
  }

  @Test
  @DisplayName(
      "a tower refuses a unit its damage on the way will kill, and keeps one that lives only for"
          + " a time")
  void theRuleKeepsATimedUnit() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, true);
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 3500, 20000);
    CharacterEntity timed =
        match.deploy(
            0,
            GameData.unit("Knight").toBuilder().lifeTimeMs(30000).build(),
            LEVEL,
            0,
            4500,
            20000,
            "Timed");
    match.getBattle().step();
    TowerEntity tower = BattleTowers.towerNamed(match.getBattle(), "PrincessTower_1_1");
    ValidatorQueries queries = match.getWorld().getValidatorQueries();
    for (CharacterEntity unit : List.of(knight, timed)) {
      unit.addPendingDamage(unit.getHitPoints().getHitPoints(), 100);
    }

    assertThat(
            ReferenceValidator.validate(
                tower.getTargeting(),
                knight.getTargetView(),
                ReferenceValidator.MODE_TAKE,
                queries))
        .isFalse();
    assertThat(
            ReferenceValidator.validate(
                tower.getTargeting(), timed.getTargetView(), ReferenceValidator.MODE_TAKE, queries))
        .isTrue();
  }

  @Test
  @DisplayName("a morph passes the damage on its way to the unit to the new building")
  void aMorphPassesThePendingDamageOn() {
    Standard1v1Battle match = passiveTowers();
    List<CharacterEntity> made = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void morphed(int tick, CharacterEntity old, CharacterEntity building) {
                made.add(building);
              }
            });
    match.play(0, GameData.card("GoblinDrill"), LEVEL, 0, 3500, 25500, "Drill");
    match.getBattle().step();
    CharacterEntity dig = match.getPlays().get(0).units().get(0);
    dig.addPendingDamage(40, 300);
    for (int step = 0; step < 100 && made.isEmpty(); step++) {
      match.getBattle().step();
    }

    assertThat(made).hasSize(1);
    assertThat(made.get(0).getView().getPendingDamageAmount()).isEqualTo(40);
  }
}
