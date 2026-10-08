package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The end of Goblinstein's death area (dead_goblinstein) as the doctor's area effect leaves with
 * the doctor. The ability's run ends the death area it holds by setting its countdown to 0. The
 * game keeps an area effect until its countdown is below 0, so the death area has one more update
 * and leaves in the cleanup of the step after the doctor's.
 */
class GoblinsteinDeathAreaEndTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  @Test
  @DisplayName(
      "the death area is still listed on the step the doctor and its area effect leave, and leaves"
          + " on the next")
  void theDeathAreaLeavesTheStepAfterTheDoctor() {
    GameTables tables = GameData.tables();
    int[] steps = leaveSteps(tables, new BattleRecords(tables));

    assertThat(steps[1]).as("the death area's last listed step").isEqualTo(steps[0]);
    assertThat(steps[2]).as("the death area's countdown on its last listed step").isZero();
  }

  /**
   * Plays Goblinstein, kills the monster so the ability's run makes the death area, then kills the
   * doctor.
   *
   * @return the first step without the doctor, the last step the death area is listed, and the
   *     death area's countdown then
   */
  private static int[] leaveSteps(GameTables tables, BattleRecords records) {
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    List<AreaEffectEntity> made = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void goblinsteinDeathAreaMade(
                  int tick,
                  AreaEffectEntity owner,
                  WorldEntity left,
                  AreaEffectEntity deathArea,
                  int x,
                  int y) {
                made.add(deathArea);
              }
            });
    battle.play(0, records.card("Goblinstein"), LEVEL, 0, 3500, 12000, "g");
    run(battle, 30);
    CharacterEntity monster = battle.getPlays().get(0).units().get(0);
    CharacterEntity doctor = battle.getPlays().get(0).units().get(1);
    monster.killBy(null);
    run(battle, 40);
    assertThat(made).as("the monster's death made the death area").hasSize(1);
    AreaEffectEntity deathArea = made.get(0);
    assertThat(battle.getWorld().liveObject(deathArea.getId())).isSameAs(deathArea);

    doctor.killBy(null);
    int doctorGone = -1;
    // The death area is listed after the step before the doctor's death.
    int areaLast = battle.getBattle().getTick() - 1;
    int areaCountdown = deathArea.getCountdown();
    for (int i = 0; i < 5; i++) {
      battle.getBattle().step();
      int tick = battle.getBattle().getTick() - 1;
      if (doctorGone < 0 && battle.getWorld().liveObject(doctor.getId()) == null) {
        doctorGone = tick;
      }
      if (battle.getWorld().liveObject(deathArea.getId()) != null) {
        areaLast = tick;
        areaCountdown = deathArea.getCountdown();
      }
    }
    assertThat(doctorGone).as("the doctor left").isGreaterThanOrEqualTo(0);
    return new int[] {doctorGone, areaLast, areaCountdown};
  }

  /** Steps the battle until it has run the given tick. */
  private static void run(Standard1v1Battle battle, int lastTick) {
    while (battle.getBattle().getTick() <= lastTick) {
      battle.getBattle().step();
    }
  }
}
