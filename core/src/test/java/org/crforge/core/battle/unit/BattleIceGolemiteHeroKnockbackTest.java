package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The knockback circle the Ice Golemite hero form's ability spawns on itself: a circle of radius
 * 1500 that follows the Golemite, deals no damage and, every 1500 ms from its first update, asks
 * every enemy character it reaches for a pushback of 1000 away from its point. A tower, which has
 * no movement component, is left where it is, and a unit of the Golemite's side is not listed.
 */
class BattleIceGolemiteHeroKnockbackTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The steps the battle runs before the spawn: every unit has deployed. */
  private static final int SETTLE = 40;

  @Test
  @DisplayName(
      "the knockback circle pushes an enemy Knight 1000 away from the Golemite on its first update,"
          + " and neither a Knight of the Golemite's side nor a tower")
  void theKnockbackPushesAnEnemyAway() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity golemite =
        match.deploy(0, GameData.unit("IceGolemiteHero"), LEVEL, 0, 3500, 22000, "golemite");
    CharacterEntity enemy =
        match.deploy(0, GameData.unit("Knight"), LEVEL, 1, 4500, 22500, "enemy");
    CharacterEntity friend =
        match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 2500, 22000, "friend");
    for (int k = 0; k < SETTLE; k++) {
      match.getBattle().step();
    }
    List<Object[]> pushes = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void pushbackRequested(
                  int tick,
                  WorldEntity unit,
                  boolean started,
                  int fromX,
                  int fromY,
                  MovementState pushback) {
                pushes.add(
                    new Object[] {
                      unit,
                      started,
                      fromX,
                      fromY,
                      golemite.getView().getX(),
                      golemite.getView().getY(),
                      unit.getView().getX(),
                      unit.getView().getY(),
                      pushback.getTargetX(),
                      pushback.getTargetY()
                    });
              }
            });
    BattleAction spawn =
        GameData.actions()
            .build("IceGolemiteHero_Spawn_KnockBack_AEO", match.getWorld().binding(golemite));
    golemite.actionHolder().start(spawn);
    // Spawned between two steps, the circle is first updated in the next step's post-hooks.
    match.getBattle().step();
    assertThat(pushes).as("pushes on the first update").hasSize(1);
    Object[] push = pushes.get(0);
    assertThat(push[0]).isSameAs(enemy);
    assertThat(push[1]).as("started").isEqualTo(true);
    // Away from the circle's point, which is the Golemite's.
    assertThat(new int[] {(int) push[2], (int) push[3]})
        .containsExactly((int) push[4], (int) push[5]);
    // The setter aims 1000 along the line from the Golemite through the Knight, C division.
    int dx = (int) push[6] - (int) push[2];
    int dy = (int) push[7] - (int) push[3];
    int length = (int) Math.sqrt((double) dx * dx + (double) dy * dy);
    assertThat(length).isLessThan(1500 + enemy.getData().collisionRadius());
    assertThat(new int[] {(int) push[8], (int) push[9]})
        .containsExactly((int) push[6] + 1000 * dx / length, (int) push[7] + 1000 * dy / length);
    assertThat(enemy.getUnit().movement().getPushbackInFlight()).isEqualTo(1);
    assertThat(friend.getUnit().movement().getPushbackInFlight()).isZero();
  }
}
