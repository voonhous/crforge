package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A pingpong projectile that comes back to a launcher whose targeting component is off. The return
 * tells only a component that is on; one that is off keeps the hold the launch set, and the
 * projectile's removal at the next cleanup clears it, through the notice every remaining entity
 * hears of an entity that leaves, which reaches a component that is off as well.
 *
 * <p>The scene: the top side's Executioner stands in reach of the bottom side's left princess tower
 * and throws its axe at it. The bottom side's Zap lands on it shortly before the axe comes back, so
 * it is stunned, its targeting component off, on the tick of the return.
 */
class BattlePingpongReturnStunnedTest {

  /** The level of the towers, the Executioner and the Zap. */
  private static final int LEVEL = 1;

  /** Where the Executioner stands, in reach of the bottom side's left princess tower. */
  private static final int X = 3500;

  private static final int Y = 11000;

  /** Long enough for the deploy, the first throw, the stun and the throw after it. */
  private static final int TICKS = 400;

  /** How much of the sweep is left when the Zap is played, so its stun covers the return. */
  private static final int ZAP_BEFORE_RETURN_MS = 200;

  /** The Executioner's live pingpong projectiles. */
  private static List<ProjectileEntity> axes(Standard1v1Battle match, WorldEntity owner) {
    List<ProjectileEntity> axes = new ArrayList<>();
    for (var entity : match.getWorld().getHolder().entities()) {
      if (entity instanceof ProjectileEntity p
          && p.getOwner() == owner
          && p.getData().pingpongVisualTimeMs() >= 1) {
        axes.add(p);
      }
    }
    return axes;
  }

  @Test
  @DisplayName(
      "an axe that comes back to a stunned Executioner keeps its hold until the axe leaves the"
          + " battle; the Executioner throws again once the stun is over")
  void theHoldClearsAtTheRemoval() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, true);
    List<Integer> launches = new ArrayList<>();
    List<String> holdsLeft = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileLaunched(int tick, ProjectileEntity projectile) {
                if (projectile.getData().pingpongVisualTimeMs() >= 1) {
                  launches.add(tick);
                }
              }

              @Override
              public void holdLeft(int tick, WorldEntity unit, ProjectileEntity projectile) {
                holdsLeft.add(tick + " " + unit.name() + " " + projectile.name());
              }
            });
    CharacterEntity executioner =
        match.deploy(0, match.getWorld().getRecords().unit("AxeMan"), LEVEL, 1, X, Y, "Axe");

    ProjectileEntity first = null;
    boolean zapped = false;
    int returnTick = -1;
    for (int step = 0; step < TICKS && returnTick < 0; step++) {
      int tick = match.getBattle().getTick();
      if (first == null && !axes(match, executioner).isEmpty()) {
        first = axes(match, executioner).get(0);
      }
      if (first != null
          && !zapped
          && first.getData().pingpongVisualTimeMs() - first.getPingpongTimeMs()
              <= ZAP_BEFORE_RETURN_MS) {
        match.play(tick, GameData.card("Zap"), LEVEL, 0, executioner.x(), executioner.y(), "Zap");
        zapped = true;
      }
      match.getBattle().step();
      if (first != null && first.isReleased()) {
        returnTick = tick;
      }
    }
    assertThat(returnTick).as("the axe came back").isPositive();
    assertThat(executioner.isActive(0))
        .as("the Executioner's targeting is off on the return")
        .isFalse();
    assertThat(executioner.getBuffs().carries("ZapFreeze")).as("stunned by the Zap").isTrue();
    // The axe leaves at the cleanup that closes the tick of its return, and its removal, not the
    // return, clears the hold.
    assertThat(match.getWorld().getHolder().entities()).doesNotContain(first);
    assertThat(holdsLeft).containsExactly(returnTick + " Axe " + first.name());
    assertThat(executioner.getTargeting().isVisitSuspended())
        .as("the axe's removal cleared the hold")
        .isFalse();

    // Once the stun is over, the Executioner throws again.
    for (int step = 0; step < TICKS && launches.size() < 2; step++) {
      match.getBattle().step();
    }
    assertThat(launches).hasSize(2);
    assertThat(launches.get(1)).isGreaterThan(returnTick);
  }
}
