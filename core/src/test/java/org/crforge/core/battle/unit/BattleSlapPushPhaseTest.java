/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.EntityActions;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Giant hero's slap push is read in the pending pass after the post-hooks (its UpdatePhase,
 * PostGameObjectTick), so a push queued on an evolved Mega Knight behind the actions its hit queued
 * stays in the queue through the earlier passes. The pending pass takes an entry by swapping the
 * last one into its place and looks at the same place again; with the push skipped there, the hit's
 * counter update now starts before its uppercut, which reads the counter as updated.
 */
class BattleSlapPushPhaseTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The selector the Giant hero's ability starts; it queues the push on the enemy it picks. */
  private static final String SELECTOR = "GiantHero_Target_Selector";

  /** The push the selector queues. */
  private static final String PUSH = Shipped.actionNames(SELECTOR, "Actions").get(0);

  @Test
  @DisplayName("the slap's push is read as the pending pass after the post-hooks")
  void thePushIsReadAsTheLastPass() {
    GameTables tables = GameData.tables();
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    BattleWorld world = match.getWorld();
    CharacterEntity mk =
        match.deploy(0, world.getRecords().unit("MegaKnight_EV1"), LEVEL, 0, 3500, 8000, "mk");
    match.getBattle().step();

    BattleAction push = world.getActions().build(PUSH, world.binding(mk));

    assertThat(push.phase()).isEqualTo(EntityActions.PHASE_POST_GAME_OBJECT_TICK);
  }

  @Test
  @DisplayName(
      "an evolved Mega Knight whose last hit uppercut uppercuts again when a slap's push is queued"
          + " on it behind its hit's actions in the same step")
  void theHitAfterAnUppercutUppercutsBehindASlapPush() {
    GameTables tables = GameData.tables();
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    BattleWorld world = match.getWorld();
    List<Integer> uppercuts = new ArrayList<>();
    world.addObserver(
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
            uppercuts.add(tick);
          }
        });
    UnitData mkData = world.getRecords().unit("MegaKnight_EV1");
    CharacterEntity mk = match.deploy(0, mkData, LEVEL, 0, 3500, 8000, "mk");
    CharacterEntity giant =
        match.deploy(0, world.getRecords().unit("GiantHero"), LEVEL, 1, 14500, 24000, "giant");
    // Both deployed and standing apart, far from anything they could hit.
    for (int i = 0; i < 25; i++) {
      match.getBattle().step();
    }
    assertThat(uppercuts).isEmpty();
    assertThat(mk.actionHolder().queued()).isEmpty();

    // The hit's action group: the counter update first, the uppercut second, the uppercut gated on
    // an even counter.
    String group = mkData.onAttackAction();
    List<String> parts = Shipped.actionNames(group, "SubActions");
    String counter = Shipped.text(parts.get(0), "Variable");
    BattleAction hit = world.getActions().build(group, world.binding(mk));
    // The last hit uppercut: the counter stands at one, so the gate is false until the update.
    mk.setVariable(world.variableKey(counter), 1);
    mk.getUnit().targeting().setReference(giant.getTargetView());

    // The hit queues its group (with its parts) from the targeting visit; the selector queues the
    // push afterwards, from its run, both caused by the Giant.
    mk.actionHolder().schedule(hit, ActionHolder.OWN_DELAY, false, giant.actionHolder());
    mk.actionHolder()
        .schedule(
            world.getActions().build(PUSH, world.binding(mk)),
            ActionHolder.OWN_DELAY,
            false,
            giant.actionHolder());
    int tick = match.getBattle().getTick();
    match.getBattle().step();

    assertThat(mk.variable(world.variableKey(counter))).as("the counter after the hit").isZero();
    assertThat(uppercuts).as("the uppercut the hit starts").containsExactly(tick);
  }
}
