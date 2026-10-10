package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
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
}
