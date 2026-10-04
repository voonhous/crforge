package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dark Magic's laser ball on a unit that still waits its turn to deploy: its filter asks the hidden
 * test, which answers yes for a unit in that state, so the fire passes the waiting unit by and
 * picks the others.
 *
 * <p>The scene: the top side's Goblins are played on tick 32; the four of them start deploying one
 * after another, the last on tick 44. The bottom side's Dark Magic area effect is placed on them so
 * that its laser ball first fires on tick 40, while the last Goblin still waits.
 */
class BattleLaserBallWaitingTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The tick the laser ball first fires on. */
  private static final int FIRE_TICK = 40;

  /** What one fire found: its tick, its count and the units it picked. */
  private record Fire(int tick, int count, List<WorldEntity> targets) {}

  @Test
  @DisplayName("a laser ball's fire passes a unit waiting to deploy by")
  void laserBallPassesAWaitingUnitBy() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), 1, false);
    List<Fire> fires = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void laserFired(
                  int tick,
                  AreaEffectEntity areaEffect,
                  int count,
                  int index,
                  List<WorldEntity> targets,
                  String action,
                  int timerBefore,
                  int timerAfter) {
                fires.add(new Fire(tick, count, List.copyOf(targets)));
              }
            });
    match.play(32, GameData.card("Goblins"), 1, 1, 13500, 22500, "Goblins");
    // The laser ball starts ten ticks after the area effect, and fires twenty ticks later.
    match.placeAreaEffect(FIRE_TICK - 30, "DarkMagicAOE", LEVEL, 0, 13500, 22500, "dark");
    while (match.getBattle().getTick() <= FIRE_TICK) {
      match.getBattle().step();
    }
    List<CharacterEntity> goblins =
        match.getPlays().stream()
            .filter(play -> play.units().size() == 4)
            .findFirst()
            .orElseThrow()
            .units();
    assertThat(goblins.get(3).getView().getState())
        .as("the last Goblin still waits")
        .isEqualTo(GridEntityState.WAITING_TO_DEPLOY);
    assertThat(fires).as("the laser ball fired once").hasSize(1);
    Fire fire = fires.get(0);
    assertThat(fire.tick()).isEqualTo(FIRE_TICK);
    assertThat(fire.targets())
        .as("the fire picks the three Goblins out and passes the waiting one by")
        .contains(goblins.get(0), goblins.get(1), goblins.get(2))
        .doesNotContain(goblins.get(3));
  }
}
