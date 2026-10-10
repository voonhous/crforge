/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.crforge.core.battle.Shipped.number;
import static org.crforge.core.battle.Shipped.row;
import static org.crforge.core.battle.Shipped.text;
import static org.crforge.core.battle.Shipped.unitRow;

import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a hit dealt after the battle's end still does. The game refuses such a hit in its
 * subtraction, so it takes no shield and no hit points, and nothing the subtraction runs follows -
 * the reflect, the attacker's hook, the on-damage action; but the bookkeeping around the
 * subtraction still accepts the hit, and the damage drain applies what it applies for an accepted
 * hit: a projectile's target buff lands on what it hit.
 *
 * <p>The scenes: the top side's Electro Dragon fires at the bottom side's left princess tower, and
 * the battle ends while its bolt is in flight; and a direct hit from an Electro Wizard, whose row
 * sets a buff on damage, queued after the end.
 */
class BattleEndHitAcceptedTest {

  /** The level of the towers and of the units. */
  private static final int LEVEL = 1;

  /** Long enough for every deploy and the first bolt's flight. */
  private static final int TICKS = 300;

  /** The projectile a unit's row fires. */
  private static GameRow projectileOf(String unit) {
    return row("projectiles", text(unitRow(unit), "Projectile"));
  }

  /** Side 0's left princess tower. */
  private static TowerEntity leftTower(Standard1v1Battle match) {
    return match.getWorld().getHolder().entities().stream()
        .filter(TowerEntity.class::isInstance)
        .map(TowerEntity.class::cast)
        .filter(t -> t.side() == 0 && !t.getData().king() && t.x() < 9000)
        .findFirst()
        .orElseThrow();
  }

  /** The time left of an entity's instance of a buff, or -1 when it carries none. */
  private static int buffLeft(WorldEntity entity, String buff) {
    for (BuffInstance instance : entity.getBuffs().items()) {
      if (instance.getBuff().name().equals(buff)) {
        return instance.getRemaining();
      }
    }
    return -1;
  }

  /** What the bolt scene saw: the impact's answer and the tower as the impact's tick ended. */
  private static final class Impact {
    ProjectileEntity bolt;
    DamageResult result;
    int hitPoints = -1;
    int buffLeft = -1;
  }

  @Test
  @DisplayName(
      "a bolt landing after the end takes nothing, but the drain accepts it"
          + " and the tower carries the bolt's ZapFreeze for its whole BuffTime")
  void aBoltAfterTheEndStillLandsItsBuff() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, true);
    TowerEntity tower = leftTower(match);
    CharacterEntity dragon =
        match.deploy(0, GameData.unit("ElectroDragon"), LEVEL, 1, 3500, 10500, "Dragon");
    String buff = text(projectileOf("ElectroDragon"), "TargetBuff");
    Impact impact = new Impact();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileLaunched(int tick, ProjectileEntity projectile) {
                if (impact.bolt == null && projectile.getOwner() == dragon) {
                  impact.bolt = projectile;
                }
              }

              @Override
              public void projectileImpacted(
                  int tick,
                  ProjectileEntity projectile,
                  WorldEntity target,
                  int damage,
                  DamageResult result) {
                if (projectile == impact.bolt && target == tower && impact.result == null) {
                  impact.result = result;
                }
              }
            });
    int hitPointsAtEnd = -1;
    for (int step = 0; step < TICKS && impact.result == null; step++) {
      match.getBattle().step();
      if (impact.bolt != null && hitPointsAtEnd < 0) {
        // The bolt is in flight: the battle ends before it lands.
        assertThat(impact.result).as("the bolt is still in flight").isNull();
        match.getWorld().setMatchEnded(true);
        hitPointsAtEnd = tower.getHitPoints().getHitPoints();
      }
    }
    assertThat(impact.result).as("the bolt landed on the tower").isNotNull();
    impact.hitPoints = tower.getHitPoints().getHitPoints();
    impact.buffLeft = buffLeft(tower, buff);

    assertThat(impact.result.landed()).as("the subtraction refused it").isFalse();
    assertThat(impact.result.accepted()).as("the bookkeeping accepted it").isTrue();
    assertThat(impact.result.applied()).isZero();
    assertThat(impact.hitPoints).as("the tower took nothing").isEqualTo(hitPointsAtEnd);
    // Applied at the drain, after the tick's buff pass: whole at the end of the impact's tick.
    assertThat(impact.buffLeft).isEqualTo(number(projectileOf("ElectroDragon"), "BuffTime"));
  }

  @Test
  @DisplayName(
      "a direct hit after the end from a row with a buff on damage is refused:"
          + " the drain would apply the buff, which no recording holds")
  void aBuffOnDamageAfterTheEndIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    TowerEntity tower = leftTower(match);
    CharacterEntity wizard =
        match.deploy(0, GameData.unit("ElectroWizard"), LEVEL, 1, 3500, 11000, "Wizard");
    match.getBattle().step();
    int hitPoints = tower.getHitPoints().getHitPoints();
    match.getWorld().setMatchEnded(true);
    match.getWorld().dealDirectHit(wizard, tower.getTargetView(), 100, 0, -1);

    assertThatThrownBy(() -> match.getBattle().step())
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("BuffOnDamage");
    assertThat(tower.getHitPoints().getHitPoints()).isEqualTo(hitPoints);
  }
}
