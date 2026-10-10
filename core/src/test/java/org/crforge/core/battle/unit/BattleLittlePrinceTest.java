package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Little Prince where the reference runs do not take it: a stun on a ramped Little Prince. Its
 * row restarts its hit timer without a target, so the stun's loss of the reference clears its
 * attack time. The stun's combat gate switches its targeting off and raises COMBAT_DISABLED, which
 * its constant ticker reads the step after: LP_AttackCount goes back to 0, and its fastest
 * speed-up, alive only while the count is 6 or more, ends at the buff visit that follows.
 */
class BattleLittlePrinceTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The Little Prince's point, on the bottom side's left, at a cell's centre. */
  private static final int X = 3250;

  private static final int Y = 11250;

  /** How far ahead of it its target stands, within its range of 5500. */
  private static final int AHEAD = 3500;

  /** Long enough for the ramp to reach its fastest speed-up. */
  private static final int RAMP_TICKS = 300;

  @Test
  @DisplayName(
      "a Zap on a ramped Little Prince clears its attack time, resets its count and ends its"
          + " fastest speed-up")
  void aStunEndsTheRamp() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    List<String> buffs = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void buffApplied(int tick, WorldEntity target, BuffInstance buff) {
                buffs.add("applied " + buff.getBuff().name());
              }

              @Override
              public void buffRemoved(int tick, WorldEntity target, BuffInstance buff) {
                buffs.add("removed " + buff.getBuff().name());
              }
            });
    CharacterEntity prince =
        match.deploy(0, GameData.unit("LittlePrince"), LEVEL, 0, X, Y, "prince");
    prince.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    CharacterEntity golem =
        match.deploy(0, GameData.unit("Golem"), LEVEL, 1, X, Y + AHEAD, "golem");
    golem.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    int tick = 0;
    while (!prince.getBuffs().carries("LittlePrinceLvlMax")) {
      assertThat(tick).as("the ramp reaches its fastest speed-up").isLessThan(RAMP_TICKS);
      match.getBattle().step();
      tick++;
    }
    // Two hits on the fastest speed-up, so its attack time is well past a hit.
    for (int i = 0; i < 16; i++) {
      match.getBattle().step();
      tick++;
    }
    assertThat(prince.getTargeting().getAttackTimerMs()).isGreaterThan(7200);
    // The count the buffs' AliveIfTrue read is LP_AttackCount, a variable: the attack start that
    // finds it at 6 applies LittlePrinceLvlMax before the buff visit that ends LittlePrinceLvl1.
    assertThat(buffs)
        .containsExactly(
            "applied LittlePrinceLvl1", "applied LittlePrinceLvlMax", "removed LittlePrinceLvl1");

    int count = match.getWorld().variableKey("LP_AttackCount");
    assertThat(prince.variable(count)).as("the ramp's count").isGreaterThanOrEqualTo(6);

    match.placeAreaEffect(tick, "Zap", LEVEL, 1, X, Y, "zap");
    // The stun's gate raises COMBAT_DISABLED for the next step, whose ticker resets the count;
    // the buff visit of the step after ends the fastest speed-up.
    for (int i = 0; i < 3; i++) {
      match.getBattle().step();
    }

    assertThat(prince.getBuffs().carries("ZapFreeze")).isTrue();
    assertThat(prince.getView().getFlags() & prince.getView().getFlagBits().combatDisabled())
        .as("COMBAT_DISABLED while the stun holds it")
        .isNotZero();
    assertThat(prince.variable(count)).as("the ramp's count after the stun").isZero();
    assertThat(prince.getTargeting().getAttackTimerMs()).isZero();
    assertThat(prince.getBuffs().carries("LittlePrinceLvlMax")).isFalse();
    assertThat(buffs)
        .containsExactly(
            "applied LittlePrinceLvl1",
            "applied LittlePrinceLvlMax",
            "removed LittlePrinceLvl1",
            "applied ZapFreeze",
            "removed LittlePrinceLvlMax");
  }
}
