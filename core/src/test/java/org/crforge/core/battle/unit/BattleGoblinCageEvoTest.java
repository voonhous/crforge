/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.Shipped.number;
import static org.crforge.core.battle.Shipped.text;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.pathfinding.combat.LevelScaling;
import org.crforge.core.pathfinding.combat.ScalingGlobals;
import org.crforge.core.pathfinding.combat.ScalingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Goblin Cage's capture: a character that claims the enemy nearest it, gives it the
 * capture buff, the cage its parent and source, waits the drag delay and the pause, drags it onto
 * its point, hides it there and hits it once per hit frequency at its level, until the cage leaves,
 * its buff with it, and the unit walks on.
 */
class BattleGoblinCageEvoTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The capture row the cage starts. */
  private static final String CAPTURE = "GoblinCage_EV1_CaptureUnit";

  /** Its hit frequency, in ticks. */
  private static final int HIT_TICKS = number(CAPTURE, "HitFrequency") / 50;

  /** The buff it gives each capture. */
  private static final String BUFF = text(CAPTURE, "BuffDuringCapture");

  @Test
  @DisplayName(
      "the evolved cage captures a Giant, puts it on its point hidden, hits it every second at its"
          + " level and lets it walk on once it leaves")
  void theCageCapturesAndHitsAGiant() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    Battle battle = match.getBattle();
    CharacterEntity cage =
        match.deploy(0, GameData.unit("GoblinCage_EV1_TEMPNAME"), LEVEL, 0, 3500, 14500);
    // Late in the cage's life, so the Giant outlives the hits it takes before the cage leaves.
    CharacterEntity giant = match.deploy(200, GameData.unit("Giant"), LEVEL, 1, 3500, 19000);
    // The capture's damage per hit at the cage's level, scaled as a card's damage by the cage's own
    // row's rarity.
    int hit =
        LevelScaling.scale(
            ScalingGlobals.standard(),
            number(CAPTURE, "DamagePerHit"),
            cage.packedLevel(),
            ScalingMode.CARD_DAMAGE,
            cage.getData().rarity());
    int cageX = cage.getView().getX();
    int cageY = cage.getView().getY();

    int snapped = -1;
    for (int tick = 0; tick < 300 && snapped < 0; tick++) {
      battle.step();
      if (giant.getView().getX() == cageX && giant.getView().getY() == cageY) {
        snapped = tick;
      }
    }
    assertThat(snapped).as("the Giant is put on the cage's point").isNotNegative();
    // The capture buff, the cage its parent and source, at the cage's level.
    assertThat(captureBuffs(giant, cage)).as("the capture buff from the cage").hasSize(1);
    assertThat(captureBuffs(giant, cage).get(0).getPackedLevel()).isEqualTo(cage.packedLevel());

    // Once hidden only the cage's hits reach it: each the damage per hit at the cage's level, one
    // hit frequency apart.
    for (int tick = 0; tick < 5; tick++) {
      battle.step();
    }
    assertThat(giant.hidden()).as("hidden while the cage holds it").isTrue();
    List<Integer> drops = new ArrayList<>();
    List<Integer> at = new ArrayList<>();
    int hp = giant.getHitPoints().getHitPoints();
    for (int tick = 0; tick < 100; tick++) {
      battle.step();
      int now = giant.getHitPoints().getHitPoints();
      if (now != hp) {
        drops.add(hp - now);
        at.add(tick);
        hp = now;
      }
      assertThat(giant.getView().getX()).isEqualTo(cageX);
      assertThat(giant.getView().getY()).isEqualTo(cageY);
    }
    assertThat(drops).hasSize(5).containsOnly(hit);
    for (int i = 1; i < at.size(); i++) {
      assertThat(at.get(i) - at.get(i - 1)).isEqualTo(HIT_TICKS);
    }

    // The cage's life ends; the hide stops with its hider and the Giant walks on.
    for (int tick = 0; tick < 600 && !cage.isRemovable(); tick++) {
      battle.step();
    }
    assertThat(cage.isRemovable()).as("the cage's life ends").isTrue();
    for (int tick = 0; tick < 10; tick++) {
      battle.step();
    }
    assertThat(giant.getHitPoints().getHitPoints()).as("the Giant outlived the hits").isPositive();
    assertThat(giant.hidden()).as("no longer hidden").isFalse();
    assertThat(captureBuffs(giant, cage)).as("the buff left with its parent").isEmpty();
    assertThat(giant.getView().getY()).as("walking again").isNotEqualTo(cageY);
  }

  /** The unit's instances of the capture buff whose parent and source are the cage. */
  private static List<BuffInstance> captureBuffs(CharacterEntity unit, CharacterEntity cage) {
    List<BuffInstance> out = new ArrayList<>();
    for (BuffInstance instance : unit.getBuffs().items()) {
      if (instance.getBuff().name().equals(BUFF)
          && instance.getParent() == cage
          && instance.getSource() == cage) {
        out.add(instance);
      }
    }
    return out;
  }
}
