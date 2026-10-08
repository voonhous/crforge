package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.Shipped.flag;
import static org.crforge.core.battle.Shipped.number;
import static org.crforge.core.battle.Shipped.unitRow;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A row that loads before its first hit (LoadFirstHit, the Sparky) is born with its whole load
 * still to run: its targeting component starts the load countdown at LoadTime, and only the
 * targeting visits, which start after its deploy, run it down. A Sparky with a target in range as
 * soon as it has deployed therefore fires one hit speed after its deploy ends (the whole load, then
 * the rest of the hit), not one hit speed less its load time after it starts attacking.
 */
class BattleLoadFirstHitTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The ticks the Sparky's deploy ended on and its first shot was launched on. */
  private record Timing(int deployEnd, int firstShot, int attackStart) {}

  /**
   * Places a Sparky for the bottom side and a Knight for the top side within its range, both at
   * once, and steps until the Sparky's first shot.
   */
  private static Timing firstShot(GameTables tables, BattleRecords records) {
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity sparky =
        battle.deploy(0, records.unit("ZapMachine"), LEVEL, WorldEntity.SIDE_BOTTOM, 9000, 13000);
    battle.deploy(0, records.unit("Knight"), LEVEL, WorldEntity.SIDE_TOP, 9000, 16500);
    List<Integer> shots = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileLaunched(int tick, ProjectileEntity projectile) {
                if (projectile.getOwner() == sparky) {
                  shots.add(tick);
                }
              }
            });
    int deployEnd = -1;
    int attackStart = -1;
    for (int tick = 0; tick < 400 && shots.isEmpty(); tick++) {
      battle.getBattle().step();
      int state = sparky.getView().getState();
      if (deployEnd < 0 && state != GridEntityState.DEPLOYING) {
        deployEnd = tick;
      }
      if (attackStart < 0 && state == GridEntityState.ATTACKING) {
        attackStart = tick;
      }
    }
    assertThat(shots).as("the Sparky's shots").isNotEmpty();
    return new Timing(deployEnd, shots.get(0), attackStart);
  }

  private static void assertWholeLoad(Timing timing) {
    // It starts attacking well before a whole load could have run since its deploy ended.
    assertThat(timing.attackStart() - timing.deployEnd())
        .as("ticks from the deploy's end to the attack")
        .isLessThan(60);
    // The countdown starts at LoadTime on the first visit after the deploy's end and the hit lands
    // on the visit whose attack time, 50 a visit, reaches HitSpeed: HitSpeed in ticks, rounded up,
    // less one after the deploy's end (79 for LoadTime 3000 and HitSpeed 4000).
    GameRow sparky = unitRow("ZapMachine");
    assertThat(flag(sparky, "LoadFirstHit")).isTrue();
    int hitSpeed = number(sparky, "HitSpeed");
    assertThat(timing.firstShot() - timing.deployEnd())
        .as("ticks from the deploy's end to the first shot")
        .isEqualTo((hitSpeed + 49) / 50 - 1);
  }

  @Test
  @DisplayName("a Sparky that attacks as soon as it has deployed fires after its whole load")
  void itFiresAfterItsWholeLoad() {
    assertWholeLoad(firstShot(GameData.tables(), GameData.records()));
  }
}
