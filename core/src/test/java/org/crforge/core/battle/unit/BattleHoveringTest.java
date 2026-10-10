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
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a unit's invisibility, its buff while it is not attacking and a buff's heal over time do
 * where no reference run shows it: who may take an invisible unit, an area's damage on one, a row
 * that starts without its buff, the asker a spawned child's immunity refuses, a crown tower's heal,
 * the share of its maximum a heal may reach, and a stacking buff on damage.
 */
class BattleHoveringTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The time the Ghost's row waits before its buff while it is not attacking, set on the row. */
  private static final int NOT_ATTACKING_MS = 2000;

  /** The Ghost's sight range, set on its row. */
  private static final int GHOST_SIGHT_RANGE = 5500;

  /** The Knight's collision radius, set on its row. */
  private static final int KNIGHT_RADIUS = 500;

  /** The heal buff the heal tests apply. */
  private static final GameRow HEALER = Shipped.row("character_buffs", "BattleHealerAll");

  private static Standard1v1Battle passiveTowers() {
    return new Standard1v1Battle(GameData.tables(), LEVEL, false);
  }

  /** An asker of the given type that is no entity of the battle: a projectile or area effect. */
  private static GridEntity asker(int type) {
    GridEntity asker = new GridEntity();
    asker.setType(type);
    return asker;
  }

  @Test
  @DisplayName(
      "an invisible Ghost refuses a character or a building with hit points, and lets in a"
          + " building without them, an area's damage, a projectile and no asker")
  void anInvisibleGhostAnswersByItsAsker() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity ghost = match.deploy(0, GameData.unit("Ghost"), LEVEL, 0, 9000, 10000);
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 3500, 20000);
    CharacterEntity cannon = match.deploy(0, GameData.unit("Cannon"), LEVEL, 1, 14500, 22000);
    CharacterEntity bomb =
        match.deploy(0, GameData.unit("GiantSkeletonBomb"), LEVEL, 1, 14500, 20000);
    match.getBattle().step();

    assertThat(ghost.invisible()).as("made with its buff").isTrue();
    assertThat(ghost.filterSubject().invisibleCounter()).isEqualTo(1);
    TargetView ghostView = ghost.getTargetView();
    assertThat(ghostView.acceptsAttacker(knight.getView(), false)).isFalse();
    assertThat(ghostView.acceptsAttacker(cannon.getView(), false)).isFalse();
    assertThat(ghostView.acceptsAttacker(bomb.getView(), false)).isTrue();
    assertThat(ghostView.acceptsAttacker(knight.getView(), true))
        .as("an area's damage, which its row lets reach it")
        .isTrue();
    assertThat(ghostView.acceptsAttacker(asker(4), false)).as("a projectile").isTrue();
    assertThat(ghostView.acceptsAttacker(null, false)).isTrue();

    CharacterEntity shut =
        match.deploy(
            1,
            GameData.unit("Ghost").toBuilder().allowAreaDamageWhenInvisible(false).build(),
            LEVEL,
            0,
            3500,
            10000,
            "Shut");
    match.getBattle().step();
    assertThat(shut.getTargetView().acceptsAttacker(knight.getView(), true))
        .as("an area's damage, which its row keeps out")
        .isFalse();
  }

  @Test
  @DisplayName(
      "a Valkyrie's swing reaches an invisible Ghost beside its target when the Ghost's row lets"
          + " an area's damage reach it, and passes it by when it does not")
  void anAreaReachesAnInvisibleGhostByItsRow() {
    assertThat(ghostHitPointsLostBesideAKnight(true)).isPositive();
    assertThat(ghostHitPointsLostBesideAKnight(false)).isZero();
  }

  /**
   * The hit points a Ghost that stands still and sees nothing, so attacks nothing and stays
   * invisible, loses beside a Knight to the Valkyrie's first swing at it.
   */
  private static int ghostHitPointsLostBesideAKnight(boolean allowAreaDamage) {
    Standard1v1Battle match = passiveTowers();
    UnitData still =
        GameData.unit("Ghost").toBuilder()
            // A speed of 1 keeps the movement component a push needs, which a row without a
            // speed is not given, and moves it next to nothing.
            .speed(1)
            .attacksGround(false)
            .attacksAir(false)
            // Seeing nothing, it takes no target, which a Valkyrie within its attack range would
            // otherwise be, and never starts an attack that takes its buff off.
            .sightRange(0)
            .allowAreaDamageWhenInvisible(allowAreaDamage)
            .build();
    CharacterEntity ghost = match.deploy(0, still, LEVEL, 0, 9000, 10200, "Ghost");
    CharacterEntity knight =
        match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 9000, 11000, "Knight");
    match.deploy(0, GameData.unit("Valkyrie"), LEVEL, 1, 9000, 12500, "Valkyrie");
    // Until the Valkyrie's first swing lands on the Knight; the loop guard is a battle's whole
    // length.
    int guard = Shipped.battleTicks();
    for (int step = 0;
        knight.getHitPoints().getHitPoints() == knight.getHitPoints().getMaximum();
        step++) {
      assertThat(step).isLessThan(guard);
      match.getBattle().step();
      assertThat(ghost.invisible()).isTrue();
    }
    return ghost.getHitPoints().getMaximum() - ghost.getHitPoints().getHitPoints();
  }

  @Test
  @DisplayName(
      "a row that starts without its buff counts its time down from its creation and takes the"
          + " buff for 180,000 ms at 0")
  void aRowThatStartsWithoutItsBuffCountsDown() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity ghost =
        match.deploy(
            0,
            GameData.unit("Ghost").toBuilder()
                .startWithBuffWhenNotAttacking(false)
                .buffWhenNotAttackingTimeMs(NOT_ATTACKING_MS)
                .build(),
            LEVEL,
            0,
            9000,
            10000);

    assertThat(ghost.invisible()).isFalse();
    assertThat(ghost.getNotAttackingTimerMs()).isEqualTo(NOT_ATTACKING_MS);
    int visits = 0;
    while (!ghost.invisible() && visits < 100) {
      match.getBattle().step();
      visits++;
    }
    // Its first visit counts too: 2000 ms is 40 steps of 50.
    assertThat(visits).isEqualTo(NOT_ATTACKING_MS / 50);
    assertThat(ghost.getBuffs().items())
        .singleElement()
        .extracting(i -> i.getTotal())
        .isEqualTo(180_000);
  }

  @Test
  @DisplayName(
      "a row without the range gate holds its countdown while the unit touches its reference within"
          + " the reference's radius plus half its own sight range, and counts down beyond it")
  void aRowWithoutTheRangeGateIsHeldByTheTouchTest() {
    // The Ghost's sight range is 5500 and the Knight's radius 500: the touch bound is 3250.
    int bound = KNIGHT_RADIUS + GHOST_SIGHT_RANGE / 2;
    assertThat(visitsUntilInvisible(false, bound - 250)).as("touching").isEqualTo(-1);
    assertThat(visitsUntilInvisible(false, bound)).as("at the bound").isEqualTo(-1);
    assertThat(visitsUntilInvisible(false, bound + 250))
        .as("beyond the touch")
        .isEqualTo(NOT_ATTACKING_MS / 50);
    // Its attack range of 1200 and the two radii, 600 and 500, reach 2300.
    assertThat(visitsUntilInvisible(true, bound - 250))
        .as("with the range gate, out of its attack range")
        .isEqualTo(NOT_ATTACKING_MS / 50);
  }

  /**
   * The visits a still Ghost that starts without its buff takes to become invisible with a still
   * enemy Knight as its reference the given distance ahead, or -1 when it is still visible after
   * 200.
   */
  private static int visitsUntilInvisible(boolean useAttackRange, int distance) {
    Standard1v1Battle match = passiveTowers();
    UnitData ghostRow =
        GameData.unit("Ghost").toBuilder()
            .speed(0)
            .sightRange(GHOST_SIGHT_RANGE)
            .range(1200)
            .collisionRadius(600)
            .startWithBuffWhenNotAttacking(false)
            .buffWhenNotAttackingTimeMs(NOT_ATTACKING_MS)
            .buffWhenNotAttackingUseAttackRange(useAttackRange)
            .build();
    UnitData knightRow =
        GameData.unit("Knight").toBuilder()
            .speed(0)
            .collisionRadius(KNIGHT_RADIUS)
            .attacksGround(false)
            .attacksAir(false)
            .build();
    CharacterEntity ghost = match.deploy(0, ghostRow, LEVEL, 0, 9000, 10000, "Ghost");
    CharacterEntity knight = match.deploy(0, knightRow, LEVEL, 1, 9000, 10000 + distance, "Knight");
    for (int visits = 1; visits <= 200; visits++) {
      match.getBattle().step();
      if (ghost.invisible()) {
        return visits;
      }
    }
    assertThat(ghost.getTargeting().getReference()).isSameAs(knight.getTargetView());
    return -1;
  }

  @Test
  @DisplayName(
      "a Fireball's push leaves a hovering Ghost on the river, where it moves a unit that does not"
          + " hover off the water")
  void aPushLeavesAHoveringUnitOnTheWater() {
    assertThat(onWaterAfterAFireball(true)).isTrue();
    assertThat(onWaterAfterAFireball(false)).isFalse();
  }

  /**
   * Whether a Ghost standing on the river, attacking nothing, is still on water once a Fireball
   * landing beside it has pushed it along the river.
   */
  private static boolean onWaterAfterAFireball(boolean hovering) {
    Standard1v1Battle match = passiveTowers();
    UnitData still =
        GameData.unit("Ghost").toBuilder()
            // A speed of 1 keeps the movement component a push needs, which a row without a
            // speed is not given, and moves it next to nothing.
            .speed(1)
            .attacksGround(false)
            .attacksAir(false)
            .hovering(hovering)
            .build();
    CharacterEntity ghost = match.deploy(0, still, LEVEL, 0, 9000, 16000, "Ghost");
    match.play(40, GameData.card("Fireball"), LEVEL, 1, 8500, 16000, "Fireball");
    for (int step = 0; step < 120; step++) {
      match.getBattle().step();
    }
    assertThat(ghost.getHitPoints().getHitPoints())
        .as("the Fireball reached it")
        .isLessThan(ghost.getHitPoints().getMaximum());
    GridEntity view = ghost.getView();
    return (match.getWorld().getGrid().water(view.getX() / 500, view.getY() / 500) & 1) != 0;
  }

  @Test
  @DisplayName(
      "a row that starts without its buff and has no time for it takes the buff on its first"
          + " visit, while its elapsed time is still 0")
  void aRowWithNoTimeTakesItsBuffAtOnce() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity ghost =
        match.deploy(
            0,
            GameData.unit("Ghost").toBuilder()
                .startWithBuffWhenNotAttacking(false)
                .buffWhenNotAttackingTimeMs(0)
                .build(),
            LEVEL,
            0,
            9000,
            10000);
    match.getBattle().step();

    assertThat(ghost.invisible()).isTrue();
  }

  @Test
  @DisplayName("a projectile's hop takes a spawned child that is still immune to characters")
  void aHopTakesAnImmuneChild() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity child = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 1000, 1000);
    match.getBattle().step();
    child.startSpawnImmunity();
    // An unlaunched shot of the other side, where it stands: the arena's corner, near the child.
    ProjectileEntity shot =
        new ProjectileEntity(
            match.getWorld(), GameData.records().projectile("ElectroDragonProjectile"), 1);

    assertThat(match.getWorld().chainTarget(shot)).isSameAs(child);
  }

  @Test
  @DisplayName(
      "a spawned child's immunity refuses a character and no asker, and lets a projectile or"
          + " area effect ask on")
  void aSpawnedChildsImmunityRefusesCharactersOnly() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 9000, 10000);
    CharacterEntity enemy = match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 9000, 22000);
    match.getBattle().step();
    knight.startSpawnImmunity();

    TargetView view = knight.getTargetView();
    assertThat(view.acceptsAttacker(enemy.getView(), false)).isFalse();
    assertThat(view.acceptsAttacker(null, false)).isFalse();
    assertThat(view.acceptsAttacker(asker(4), false)).as("a projectile").isTrue();
    assertThat(view.acceptsAttacker(asker(3), false)).as("an area effect").isTrue();
  }

  @Test
  @DisplayName(
      "a crown tower takes a heal buff's heal per second at its level, raised by the crown-tower"
          + " percent, at each hit, not the hit's share of it")
  void aCrownTowerTakesTheWholeHealPerHit() {
    Standard1v1Battle match = passiveTowers();
    match.getBattle().step();
    TowerEntity king = match.getWorld().kingTower(0);
    king.getHitPoints().setHitPoints(king.getHitPoints().getMaximum() - 1000);
    List<Integer> heals = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void buffHealed(
                  int tick, WorldEntity target, BuffInstance buff, int amount, int before) {
                heals.add(amount);
              }
            });
    BuffData buff = GameData.records().buff("BattleHealerAll");
    king.getBuffs().apply(buff, 1000, king.getPackedLevel(), king, 0);
    for (int visit = 0; visit < visitsToTheFirstHit(); visit++) {
      match.getBattle().step();
    }

    // The row's heal per second at the king's level, raised by its crown-tower percent and rounded
    // up; a troop takes only the hit's share of it, HitFrequency ms of the second, at each hit.
    int percent = Math.max(Shipped.number(HEALER, "CrownTowerDamagePercent"), -100) + 100;
    int healPerSecond =
        Shipped.scaledAtPackedLevel(
            Shipped.number(HEALER, "HealPerSecond"), HEALER, king.getPackedLevel());
    int perSecond = (percent * healPerSecond + 99) / 100;
    assertThat(heals).containsExactly(perSecond);
  }

  /**
   * The visits of 50 ms a heal buff's instance counts to its first hit: its row's HitFrequency in
   * whole visits, the hit landing on the visit that reaches them.
   */
  private static int visitsToTheFirstHit() {
    return Shipped.number(HEALER, "HitFrequency") / 50;
  }

  @Test
  @DisplayName("a heal buff's over-heal share lets its heal raise the hit points past the maximum")
  void aHealMayGoPastTheMaximumByItsShare() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 9000, 10000);
    match.getBattle().step();
    BuffData healer = GameData.records().buff("BattleHealerAll");
    BuffData overHeal =
        BuffData.builder()
            .name("OverHeal")
            .rarity(healer.rarity())
            .healPerSecond(healer.healPerSecond())
            .hitFrequency(healer.hitFrequency())
            .allowedOverHealPercent(150)
            .build();
    knight.getBuffs().apply(overHeal, 1000, knight.getPackedLevel(), knight, 0);
    for (int visit = 0; visit < visitsToTheFirstHit(); visit++) {
      match.getBattle().step();
    }

    // The hit's share of the heal per second at the Knight's level, truncated, on its full hit
    // points, held at 150 percent of its maximum.
    int maximum = knight.getHitPoints().getMaximum();
    int heal =
        Shipped.scaledAtPackedLevel(
                Shipped.number(HEALER, "HealPerSecond"), HEALER, knight.getPackedLevel())
            * Shipped.number(HEALER, "HitFrequency")
            / 1000;
    int expected = Math.min(maximum * 150 / 100, maximum + heal);
    assertThat(expected).as("past the maximum").isGreaterThan(maximum);
    assertThat(knight.getHitPoints().getHitPoints()).isEqualTo(expected);
  }

  @Test
  @DisplayName(
      "a buff on damage that stacks, which its instance would keep with the attacker as its"
          + " parent, is refused at the hit")
  void aStackingBuffOnDamageIsRefused() {
    Standard1v1Battle match = passiveTowers();
    UnitData stacking =
        GameData.unit("Knight").toBuilder()
            .buffOnDamage("BattleHealerAll")
            .buffOnDamageTimeMs(1000)
            .build();
    match.deploy(0, stacking, LEVEL, 0, 9000, 13000, "Stacker");
    match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 9000, 14500, "Target");

    assertThatThrownBy(
            () -> {
              for (int step = 0; step < 200; step++) {
                match.getBattle().step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("stacking BuffOnDamage");
  }
}
