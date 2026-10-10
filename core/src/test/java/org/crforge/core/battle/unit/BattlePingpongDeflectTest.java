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
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A pingpong projectile turned around by a deflecting area effect on its way out. The pass over its
 * body's cells finds the deflecting area effect of the other team and the deflection turns it
 * around instead of letting it hit anything there. A pingpong projectile not deflected before lets
 * its launcher's targeting go on at once: the hold its launch set is cleared. It is launched again
 * at its source's position, neither stretched to its range nor to its least distance, for the
 * deflector's side, and the new launcher's targeting holds nothing. From then on it no longer
 * sweeps: it flies at its row's speed like any projectile that flies to a point, hits what its body
 * passes, and lands at its aim without coming back.
 *
 * <p>The deflection's hit on the Monk goes through the runs on the axe - the evolved Executioner's
 * controller hands its own amount - and is queued for the damage drain; a Monk the axe has hit
 * already takes nothing from it. Deflected, the axe may be turned around again by the other side's
 * Monk, and the deflection past the most a projectile takes still deals its damage and then ends
 * the flight.
 *
 * <p>The scene: the bottom side's Monk stands in the Executioner's reach, both held in place, and
 * the Monk's Deflect is up when the Executioner throws its axe at it.
 */
class BattlePingpongDeflectTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Where the Monk stands, along the left lane. */
  private static final int MONK_X = 3500;

  private static final int MONK_Y = 14000;

  /** Where the Executioner stands: in its reach of the Monk, but not of the Monk's. */
  private static final int EXECUTIONER_Y = 19000;

  /** Where a second Monk stands, for the Executioner's side, behind the Executioner. */
  private static final int TOP_MONK_Y = 20000;

  /** Long enough for both to deploy, the Monk to cast and the axe to be deflected and land. */
  private static final int TICKS = 300;

  @Test
  @DisplayName(
      "a Monk's Deflect turns an Executioner's axe around on its way out: the Executioner's hold"
          + " is cleared, and the axe flies straight back at it at its row's speed and lands")
  void deflectionTurnsTheAxeIntoAStraightShot() {
    BattleRecords records = new BattleRecords(GameData.tables());
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    List<ProjectileEntity> deflected = new ArrayList<>();
    List<Integer> deflectedPingpongTimes = new ArrayList<>();
    List<String> holdsLeft = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileDeflected(
                  int tick,
                  AreaEffectEntity deflector,
                  ProjectileEntity projectile,
                  WorldEntity parent,
                  WorldEntity source) {
                deflected.add(projectile);
                deflectedPingpongTimes.add(projectile.getPingpongTimeMs());
              }

              @Override
              public void holdLeft(int tick, WorldEntity unit, ProjectileEntity projectile) {
                holdsLeft.add(unit.name());
              }
            });
    CharacterEntity monk = match.deploy(0, records.unit("Monk"), LEVEL, 0, MONK_X, MONK_Y, "Monk");
    CharacterEntity executioner =
        match.deploy(0, records.unit("AxeMan"), LEVEL, 1, MONK_X, EXECUTIONER_Y, "Axe");
    int tick = 0;
    while (monk.getView().getState() == GridEntityState.DEPLOYING
        || monk.getView().getState() == GridEntityState.WAITING_TO_DEPLOY
        || monk.getId() == 0) {
      match.getBattle().step();
      tick++;
      assertThat(tick).as("the Monk deploys").isLessThan(TICKS);
    }
    monk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    executioner.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    monk.requestAbility();
    while (deflected.isEmpty()) {
      match.getBattle().step();
      tick++;
      assertThat(tick).as("the axe is deflected").isLessThan(TICKS);
    }

    ProjectileEntity axe = deflected.get(0);
    assertThat(axe.getData().pingpongVisualTimeMs()).as("a pingpong projectile").isPositive();
    assertThat(axe.getData().projectileRange())
        .as("a projectile with a range, which a launch stretches its aim to")
        .isPositive();
    assertThat(deflectedPingpongTimes.get(0)).as("its sweep starts over").isZero();
    assertThat(axe.getDeflections()).isEqualTo(1);
    assertThat(axe.side()).as("it flies for the Monk's side").isEqualTo(monk.side());
    assertThat(axe.getOwner()).isSameAs(monk);
    // The Executioner's hold is cleared by the deflection, and the Monk's targeting holds nothing.
    assertThat(executioner.getTargeting().isVisitSuspended()).isFalse();
    assertThat(monk.getTargeting().isVisitSuspended()).isFalse();
    // Its aim is where the Executioner stands, not stretched to the row's range.
    GridEntity at = executioner.getView();
    assertThat(axe.getAimX()).isEqualTo(at.getX());
    assertThat(axe.getAimY()).isEqualTo(at.getY());

    int full = executioner.getHitPoints().getHitPoints();
    int damage = axe.damage();
    int speed = axe.getData().speed();
    int lastX = axe.getX();
    int lastY = axe.getY();
    int steps = 0;
    while (!axe.isReleased()) {
      match.getBattle().step();
      steps++;
      assertThat(steps).as("the axe lands").isLessThan(TICKS);
      if (!axe.isReleased()) {
        // A straight step of the row's speed toward the aim, no sweep.
        int moved = (int) Math.round(Math.hypot(axe.getX() - lastX, axe.getY() - lastY));
        assertThat(moved).as("step %d", steps).isBetween(speed - 2, speed + 2);
        lastX = axe.getX();
        lastY = axe.getY();
      }
    }
    assertThat(axe.getX()).as("it lands at its aim").isEqualTo(at.getX());
    assertThat(axe.getY()).isEqualTo(at.getY());
    assertThat(executioner.getHitPoints().getHitPoints())
        .as("the Executioner takes its own axe once")
        .isEqualTo(full - damage);
    assertThat(holdsLeft).as("no targeting held the deflected axe").isEmpty();
    assertThat(monk.getTargeting().isVisitSuspended()).isFalse();
  }

  @Test
  @DisplayName(
      "an axe deflected on its way back after hitting the Monk turns around but deals the Monk"
          + " nothing, the Monk being listed among what it has hit")
  void axeThatHasHitTheMonkDealsItNothingAsItIsDeflected() {
    // The Deflect is cast later and later after the first axe's launch, until it comes up while
    // the axe is on its way back and has hit the Monk again.
    for (int wait = 0; wait < TICKS; wait++) {
      if (deflectedOnTheWayBackAfterHittingTheMonk(wait)) {
        return;
      }
    }
    throw new AssertionError("no cast time deflects the axe on its way back through the Monk");
  }

  /**
   * One battle: the Monk casts its Deflect the given ticks after the Executioner's first axe is
   * launched. When the axe is deflected having hit the Monk on its way back, checks that the Monk
   * takes nothing and answers true; otherwise answers false.
   */
  private static boolean deflectedOnTheWayBackAfterHittingTheMonk(int wait) {
    BattleRecords records = new BattleRecords(GameData.tables());
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    List<ProjectileEntity> deflected = new ArrayList<>();
    List<WorldEntity> hitAfterDeflection = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileDeflected(
                  int tick,
                  AreaEffectEntity deflector,
                  ProjectileEntity projectile,
                  WorldEntity parent,
                  WorldEntity source) {
                deflected.add(projectile);
              }

              @Override
              public void projectileImpacted(
                  int tick,
                  ProjectileEntity projectile,
                  WorldEntity target,
                  int damage,
                  DamageResult result) {
                if (projectile.getDeflections() >= 1) {
                  hitAfterDeflection.add(target);
                }
              }
            });
    CharacterEntity monk = match.deploy(0, records.unit("Monk"), LEVEL, 0, MONK_X, MONK_Y, "Monk");
    CharacterEntity executioner =
        match.deploy(0, records.unit("AxeMan"), LEVEL, 1, MONK_X, EXECUTIONER_Y, "Axe");
    int tick = 0;
    while (monk.getView().getState() == GridEntityState.DEPLOYING
        || monk.getView().getState() == GridEntityState.WAITING_TO_DEPLOY
        || monk.getId() == 0) {
      match.getBattle().step();
      tick++;
      assertThat(tick).as("the Monk deploys").isLessThan(TICKS);
    }
    monk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    executioner.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    ProjectileEntity axe = null;
    while (axe == null) {
      match.getBattle().step();
      tick++;
      assertThat(tick).as("the Executioner throws").isLessThan(TICKS);
      for (BattleEntity entity : match.getWorld().getHolder().entities()) {
        if (entity instanceof ProjectileEntity projectile) {
          axe = projectile;
        }
      }
    }
    for (int i = 0; i < wait; i++) {
      match.getBattle().step();
    }
    if (axe.isReleased()) {
      return false;
    }
    monk.requestAbility();
    int half = axe.getData().pingpongVisualTimeMs() / 2;
    boolean backThroughTheMonk = false;
    int full = 0;
    while (deflected.isEmpty() && !axe.isReleased()) {
      backThroughTheMonk =
          axe.getPingpongTimeMs() >= half && axe.getHitIds().contains(monk.getId());
      full = monk.getHitPoints().getHitPoints();
      match.getBattle().step();
    }
    if (deflected.isEmpty() || !backThroughTheMonk) {
      return false;
    }
    assertThat(deflected).containsExactly(axe);
    assertThat(axe.getDeflections()).isEqualTo(1);
    assertThat(hitAfterDeflection).as("the deflection deals the Monk nothing").doesNotContain(monk);
    assertThat(monk.getHitPoints().getHitPoints()).isEqualTo(full);
    return true;
  }

  @Test
  @DisplayName(
      "the evolved Executioner's axe, deflected, deals the Monk its controller's amount at the"
          + " damage drain, after the turn-around, and flies back on its controller's damage")
  void evolvedAxeDeflectionTakesTheControllerDamageAtTheDrain() {
    BattleRecords records = new BattleRecords(GameData.tables());
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    List<ProjectileEntity> deflected = new ArrayList<>();
    List<Integer> deflectedTicks = new ArrayList<>();
    // The damage the axe's controller handed each target, by the hit's id.
    List<int[]> askedOfMonk = new ArrayList<>();
    List<Integer> askedOfExecutioner = new ArrayList<>();
    // The deflections the axe had as each of its hits on the Monk was dealt.
    List<Integer> deflectionsAtMonkHit = new ArrayList<>();
    List<Integer> monkHitDamage = new ArrayList<>();
    CharacterEntity[] units = new CharacterEntity[2];
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileDeflected(
                  int tick,
                  AreaEffectEntity deflector,
                  ProjectileEntity projectile,
                  WorldEntity parent,
                  WorldEntity source) {
                deflected.add(projectile);
                deflectedTicks.add(tick);
              }

              @Override
              public void axeDamage(
                  int tick,
                  ProjectileEntity axe,
                  WorldEntity target,
                  int hitId,
                  int before,
                  int after,
                  Integer edge,
                  boolean strong) {
                if (target == units[0]) {
                  askedOfMonk.add(new int[] {tick, hitId, after});
                } else if (target == units[1] && axe.getDeflections() >= 1) {
                  askedOfExecutioner.add(after);
                }
              }

              @Override
              public void projectileImpacted(
                  int tick,
                  ProjectileEntity projectile,
                  WorldEntity target,
                  int damage,
                  DamageResult result) {
                if (target == units[0]) {
                  deflectionsAtMonkHit.add(projectile.getDeflections());
                  monkHitDamage.add(damage);
                }
              }
            });
    CharacterEntity monk = match.deploy(0, records.unit("Monk"), LEVEL, 0, MONK_X, MONK_Y, "Monk");
    units[0] = monk;
    int tick = 0;
    while (monk.getView().getState() == GridEntityState.DEPLOYING
        || monk.getView().getState() == GridEntityState.WAITING_TO_DEPLOY
        || monk.getId() == 0) {
      match.getBattle().step();
      tick++;
      assertThat(tick).as("the Monk deploys").isLessThan(TICKS);
    }
    monk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    monk.requestAbility();
    // The Executioner comes once the Deflect is up, so its first axe meets the Deflect before it
    // reaches the Monk.
    while (match.getWorld().getHolder().entities().stream()
        .noneMatch(
            e -> e instanceof AreaEffectEntity area && area.getData().deflectsProjectiles())) {
      match.getBattle().step();
      tick++;
      assertThat(tick).as("the Deflect comes up").isLessThan(TICKS);
    }
    CharacterEntity executioner =
        match.deploy(tick, records.unit("AxeMan_EV1"), LEVEL, 1, MONK_X, EXECUTIONER_Y, "Axe");
    units[1] = executioner;
    while (executioner.getId() == 0
        || executioner.getView().getState() == GridEntityState.DEPLOYING
        || executioner.getView().getState() == GridEntityState.WAITING_TO_DEPLOY) {
      match.getBattle().step();
      tick++;
      assertThat(tick).as("the Executioner deploys").isLessThan(TICKS);
    }
    executioner.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    while (deflected.isEmpty()) {
      match.getBattle().step();
      tick++;
      assertThat(tick).as("the axe is deflected").isLessThan(TICKS);
    }

    ProjectileEntity axe = deflected.get(0);
    assertThat(axe.getData().damage()).as("the evolved axe's row deals nothing itself").isZero();
    assertThat(axe.carriesRuns()).as("its controller runs on it").isTrue();
    // The deflection asked the controller for the Monk's damage, on the deflection's tick.
    assertThat(askedOfMonk).hasSize(1);
    assertThat(askedOfMonk.get(0)[0]).isEqualTo(deflectedTicks.get(0));
    int controllerAmount = askedOfMonk.get(0)[2];
    assertThat(controllerAmount).as("the controller's amount, not the row's").isPositive();
    // The hit was queued for the damage drain, which dealt it after the turn-around.
    assertThat(monkHitDamage).containsExactly(controllerAmount);
    assertThat(deflectionsAtMonkHit).containsExactly(1);

    int full = executioner.getHitPoints().getHitPoints();
    int steps = 0;
    while (!axe.isReleased()) {
      match.getBattle().step();
      steps++;
      assertThat(steps).as("the axe lands").isLessThan(TICKS);
    }
    // The Executioner takes its own axe once, for the controller's amount the hit asked for.
    assertThat(askedOfExecutioner).isNotEmpty();
    assertThat(askedOfExecutioner.get(0)).isPositive();
    assertThat(executioner.getHitPoints().getHitPoints())
        .isEqualTo(full - askedOfExecutioner.get(0));
  }

  @Test
  @DisplayName(
      "an axe deflected by a Monk on each side is turned around again, and the deflection past the"
          + " most a projectile takes still deals its parent the axe's damage and ends its flight")
  void axeBetweenTwoMonksIsDeflectedUntilItsFlightEnds() {
    BattleRecords records = new BattleRecords(GameData.tables());
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    int most = records.globalNumber("MAX_DEFLECTION_TIMES");
    List<WorldEntity> deflectors = new ArrayList<>();
    List<Boolean> releasedAtDeflection = new ArrayList<>();
    List<WorldEntity> hitAfterDeflection = new ArrayList<>();
    ProjectileEntity[] axe = new ProjectileEntity[1];
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileDeflected(
                  int tick,
                  AreaEffectEntity deflector,
                  ProjectileEntity projectile,
                  WorldEntity parent,
                  WorldEntity source) {
                axe[0] = projectile;
                deflectors.add(parent);
                releasedAtDeflection.add(projectile.isReleased());
              }

              @Override
              public void projectileImpacted(
                  int tick,
                  ProjectileEntity projectile,
                  WorldEntity target,
                  int damage,
                  DamageResult result) {
                if (projectile == axe[0]) {
                  hitAfterDeflection.add(target);
                }
              }
            });
    CharacterEntity bottom =
        match.deploy(0, records.unit("Monk"), LEVEL, 0, MONK_X, MONK_Y, "MonkBottom");
    CharacterEntity top =
        match.deploy(0, records.unit("Monk"), LEVEL, 1, MONK_X, TOP_MONK_Y, "MonkTop");
    int tick = 0;
    while (bottom.getId() == 0
        || top.getId() == 0
        || bottom.getView().getState() == GridEntityState.DEPLOYING
        || bottom.getView().getState() == GridEntityState.WAITING_TO_DEPLOY
        || top.getView().getState() == GridEntityState.DEPLOYING
        || top.getView().getState() == GridEntityState.WAITING_TO_DEPLOY) {
      match.getBattle().step();
      tick++;
      assertThat(tick).as("the Monks deploy").isLessThan(TICKS);
    }
    bottom.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    top.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    bottom.requestAbility();
    top.requestAbility();
    // The Executioner comes once both Deflects are up.
    while (match.getWorld().getHolder().entities().stream()
            .filter(e -> e instanceof AreaEffectEntity area && area.getData().deflectsProjectiles())
            .count()
        < 2) {
      match.getBattle().step();
      tick++;
      assertThat(tick).as("both Deflects come up").isLessThan(TICKS);
    }
    CharacterEntity executioner =
        match.deploy(tick, records.unit("AxeMan"), LEVEL, 1, MONK_X, EXECUTIONER_Y, "Axe");
    while (executioner.getId() == 0
        || executioner.getView().getState() == GridEntityState.DEPLOYING
        || executioner.getView().getState() == GridEntityState.WAITING_TO_DEPLOY) {
      match.getBattle().step();
      tick++;
      assertThat(tick).as("the Executioner deploys").isLessThan(TICKS);
    }
    executioner.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    int bottomBefore = 0;
    while (deflectors.size() <= most) {
      if (deflectors.size() == most) {
        bottomBefore = bottom.getHitPoints().getHitPoints();
      }
      match.getBattle().step();
      tick++;
      assertThat(tick).as("the axe is deflected past the most").isLessThan(TICKS);
    }
    // Turned around by each side's Monk in turn, the bottom one first.
    assertThat(deflectors).hasSize(most + 1);
    for (int i = 0; i < deflectors.size(); i++) {
      assertThat(deflectors.get(i)).isSameAs((i & 1) == 0 ? bottom : top);
    }
    assertThat(axe[0].getDeflections()).isEqualTo(most + 1);
    // Every deflection up to the most leaves it flying; the one past it ends its flight.
    for (int i = 0; i < most; i++) {
      assertThat(releasedAtDeflection.get(i)).as("deflection %d", i + 1).isFalse();
    }
    assertThat(releasedAtDeflection.get(most)).isTrue();
    assertThat(axe[0].isReleased()).isTrue();
    // The last deflection's parent still takes the axe's damage, at the drain.
    assertThat(hitAfterDeflection).contains(deflectors.get(most));
    assertThat(bottom.getHitPoints().getHitPoints()).isLessThan(bottomBefore);
    assertThat(match.getWorld().getHolder().entities()).doesNotContain(axe[0]);
  }
}
