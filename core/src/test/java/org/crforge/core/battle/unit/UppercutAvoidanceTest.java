/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

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
 * The evolved Mega Knight's uppercut of data version 16.402.18 (MegaKnight_EV1_uppercut): its row
 * leaves out ResetAvoidanceAtPushback, which the loader defaults to true, so once its push has gone
 * through the pushback entry the pushed unit's avoidance blend is cleared. The unit therefore flies
 * straight toward its tower instead of each push step being turned by the blend it was steering
 * with.
 */
class UppercutAvoidanceTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for a placed Mega Knight to deploy, reach the Knight and uppercut it. */
  private static final int TICKS = 300;

  /** A blend the Knight could be steering with, well above one walking step's decay. */
  private static final int BLEND = -150;

  @Test
  @DisplayName("the uppercut's push clears the pushed unit's avoidance blend")
  void thePushClearsTheBlend() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    List<WorldEntity> started = new ArrayList<>();
    List<Integer> blendsAtPush = new ArrayList<>();
    CharacterEntity knight =
        battle.deploy(0, records.unit("Knight"), LEVEL, 1, 3500, 20500, "knight");
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void uppercutStarted(
                  int tick,
                  CharacterEntity unit,
                  String action,
                  int phase,
                  WorldEntity instigator,
                  WorldEntity target,
                  boolean finished) {
                started.add(target);
              }

              @Override
              public void uppercutStepped(
                  int tick,
                  CharacterEntity unit,
                  int delay,
                  boolean finished,
                  String outcome,
                  int[] pushPoint) {
                if (outcome.startsWith("pushed")) {
                  blendsAtPush.add(knight.getUnit().movement().getAvoidanceBlend());
                }
              }
            });
    battle.deploy(0, records.unit("MegaKnight_EV1"), LEVEL, 0, 3500, 18000, "mk");

    int tick = 0;
    while (started.isEmpty()) {
      battle.getBattle().step();
      tick++;
      assertThat(tick).as("the uppercut starts").isLessThan(TICKS);
    }
    assertThat(started.get(0)).as("the uppercut's target").isSameAs(knight);
    // As if the Knight had been steering around something up to the push on the next step.
    knight.getUnit().movement().setAvoidanceBlend(BLEND);
    battle.getBattle().step();

    assertThat(blendsAtPush).as("one push, on the step after the start").hasSize(1);
    assertThat(blendsAtPush.get(0)).as("the blend right after the push").isZero();
  }
}
