/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Lumberjack's ghost: its death drops RageBarbarianBottleEvo, whose rage area makes
 * RageBarbarianEvoGhost as it starts. The ghost ignores Rage, but its wait swaps each Rage the area
 * gives it for RageDummyBuff, which it carries for as long as the Rage would last; once the dummy
 * buff is gone the wait kills the ghost.
 */
class BattleRageBarbarianEvoTest {

  @Test
  @DisplayName(
      "the ghost carries the rage as a dummy buff while the area lasts and dies the step its last"
          + " dummy buff runs out")
  void theGhostLivesOnTheRage() {
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity lumberjack =
        match.deploy(0, GameData.unit("RageBarbarian_EV1"), 11, 0, 9000, 12000);
    for (int i = 0; i < 40; i++) {
      battle.step();
    }
    match.getWorld().kill(lumberjack, null);

    CharacterEntity ghost = null;
    boolean dummySeen = false;
    int lastDummy = -1;
    int gone = -1;
    boolean lifeLeft = false;
    for (int step = 1; step <= 300 && gone < 0; step++) {
      battle.step();
      if (ghost == null) {
        ghost = character(battle, "RageBarbarianEvoGhost");
        continue;
      }
      if (ghost.isRemovable() || character(battle, "RageBarbarianEvoGhost") == null) {
        gone = step;
        break;
      }
      // The Rage itself never lands: the ghost's row ignores it, and the wait swaps it first.
      assertThat(ghost.getBuffs().carries("Rage")).isFalse();
      lifeLeft = ghost.getBuffs().carries("RageBarbarianEvoGhostLifeControllerBuff");
      if (ghost.getBuffs().carries("RageDummyBuff")) {
        dummySeen = true;
        lastDummy = step;
      }
    }
    assertThat(ghost).as("the rage area makes the ghost").isNotNull();
    assertThat(dummySeen).as("the swapped dummy buff reaches the ghost").isTrue();
    assertThat(gone).as("the ghost dies once its dummy buff has gone").isPositive();
    // The step the dummy buff runs out the wait finds it missing and schedules the kill, which a
    // later pending pass of the same step runs.
    assertThat(gone - lastDummy).isEqualTo(1);
    // The ghost walked out of the area, so the rage ran out first: its life controller buff was
    // still on it, and the wait that considers the dummy buff killed it.
    assertThat(lifeLeft).as("the life controller buff outlasts the rage here").isTrue();
  }

  @Test
  @DisplayName(
      "a ghost placed alone dies the step its life controller buff runs out: the buff's part starts"
          + " last, so the wait that considers it is listed and swaps it")
  void aGhostAloneDiesWithItsLifeControllerBuff() {
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), Standard1v1Battle.DEFAULT_LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity ghost =
        match.deploy(0, GameData.unit("RageBarbarianEvoGhost"), 11, 0, 9000, 8000);
    // The starting group's parts are queued in order, and the pending pass takes the last entry
    // into each started one's place: the group, the forever invisibility, the two waits, and only
    // then the life controller buff (its SpawnTime), which the second wait swaps for itself and so
    // arms.
    int buffGone = -1;
    int dead = -1;
    for (int step = 0; step < 160 && dead < 0; step++) {
      battle.step();
      if (buffGone < 0
          && !ghost.getBuffs().carries("RageBarbarianEvoGhostLifeControllerBuff")
          && step > 0) {
        buffGone = step;
      }
      if (ghost.isRemovable()) {
        dead = step;
      }
    }
    // The buff's time in whole steps, a part step counting as one: the instance applied in the
    // first runs out in the last of them, whose run pass finds it missing and schedules the kill,
    // run in the same step's later pending pass.
    int lifeTime = Shipped.number("add_buff_for_count_lifetime", "SpawnTime");
    int lastStep = (lifeTime + 49) / 50 - 1;
    assertThat(buffGone).isEqualTo(lastStep);
    assertThat(dead).isEqualTo(lastStep);
  }

  private static CharacterEntity character(Battle battle, String row) {
    for (BattleEntity entity : battle.getHolder().entities()) {
      if (entity instanceof CharacterEntity c && c.getData().name().equals(row)) {
        return c;
      }
    }
    return null;
  }
}
