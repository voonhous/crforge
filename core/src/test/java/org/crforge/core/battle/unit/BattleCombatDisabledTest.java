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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * COMBAT_DISABLED, the game tag of a character whose combat component is switched off: every combat
 * gate that switches the targeting component off raises it for one step, so a stun shows it from
 * the step after the first such gate to the step after the last, and an acting unit never carries
 * it.
 */
class BattleCombatDisabledTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The Knight's point, on the bottom side's left, at a cell's centre. */
  private static final int X = 3250;

  private static final int Y = 11250;

  /** Steps enough for the stun and the steps after it. */
  private static final int STEPS = 120;

  /** The step the Zap is placed at, with the Knight acting for many steps before it. */
  private static final int ZAP_AFTER = 60;

  private static boolean combatDisabled(CharacterEntity unit) {
    return (unit.getView().getFlags() & unit.getView().getFlagBits().combatDisabled()) != 0;
  }

  @Test
  @DisplayName(
      "a stunned character carries COMBAT_DISABLED the step after each gate that switched its"
          + " targeting off, and an acting one never does")
  void eachGateThatSwitchesOffRaisesTheTagForOneStep() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 0, X, Y, "knight");
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    List<Boolean> tag = new ArrayList<>();
    List<Boolean> switchedOff = new ArrayList<>();
    List<Boolean> stunned = new ArrayList<>();
    for (int step = 0; step < STEPS; step++) {
      if (step == ZAP_AFTER) {
        match.placeAreaEffect(step, "Zap", LEVEL, 1, X, Y, "zap");
      }
      match.getBattle().step();
      tag.add(combatDisabled(knight));
      switchedOff.add(!knight.isActive(CharacterEntity.TARGETING_SLOT));
      stunned.add(knight.getBuffs().carries("ZapFreeze"));
    }

    // The tag a step shows is the one the previous step's gate raised.
    for (int step = 1; step < STEPS; step++) {
      assertThat(tag.get(step))
          .as("COMBAT_DISABLED at step %d", step)
          .isEqualTo(switchedOff.get(step - 1));
    }
    assertThat(tag.subList(ZAP_AFTER - 10, ZAP_AFTER))
        .as("acting, before the Zap")
        .containsOnly(false);
    int firstStunned = stunned.indexOf(true);
    int lastStunned = stunned.lastIndexOf(true);
    assertThat(firstStunned).as("the Zap stuns it").isGreaterThanOrEqualTo(ZAP_AFTER);
    assertThat(tag.subList(firstStunned + 1, lastStunned + 1))
        .as("while stunned")
        .containsOnly(true);
    assertThat(tag.subList(lastStunned + 3, STEPS)).as("acting again").containsOnly(false);
  }
}
