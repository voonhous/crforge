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
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hero Knight's taunt on a troop that goes underground under it: a Mighty Miner taunted as it
 * walks that then switches lanes is let go on the taunt's next step, as the troop is underground
 * while it routes across (its row does not keep it visible there): no re-selection wait, no
 * reference, the run finished and the taunt's buff removed.
 */
class BattleKnightHeroTauntLaneSwitchTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for the Mighty Miner to deploy, walk and switch lanes. */
  private static final int TICKS = 300;

  /** The ability's spawn of the taunting area effect. */
  private static final String SPAWN = "Knight_hero_CreateTauntAEO";

  /** The taunt the area effect's hit group runs. */
  private static final String TAUNT =
      Shipped.actionNames(
              Shipped.text(
                  Shipped.row("area_effect_objects", Shipped.text(SPAWN, "SpawnData")),
                  "OnHitAction"),
              "SubActions")
          .get(0);

  private static final String BUFF = Shipped.text(TAUNT, "ValidTargetBuff");

  /** How long a troop is taunted, in ms. */
  private static final int DURATION = Shipped.number(TAUNT, "ValidDuration");

  @Test
  @DisplayName(
      "a taunted Mighty Miner that switches lanes is let go on the taunt's next step, as it is"
          + " underground while it routes across")
  void minerSwitchingLanesIsLetGo() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, true);
    CharacterEntity knight =
        match.deploy(0, GameData.unit("KnightHero"), LEVEL, 0, 3500, 13000, "knight");
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    CharacterEntity miner =
        match.deploy(0, GameData.unit("MightyMiner"), LEVEL, 1, 3500, 18000, "miner");
    assertThat(miner.getData().ingamePathfindVisible())
        .as("the Mighty Miner's row does not keep it visible as it routes across")
        .isFalse();
    List<String> lines = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void tauntStepped(
                  int tick,
                  WorldEntity unit,
                  WorldEntity forced,
                  int durationMs,
                  int falloffMs,
                  List<String> calls) {
                lines.add(unit.name() + " " + durationMs + " " + calls);
              }
            });
    int tick = 0;
    while (miner.getView().getState() != GridEntityState.MOVING) {
      match.getBattle().step();
      assertThat(++tick).as("the Mighty Miner walks").isLessThan(TICKS);
    }

    // The Knight's taunt reaches the walking Mighty Miner and forces it onto the Knight.
    BattleAction spawn = GameData.actions().build(SPAWN, match.getWorld().binding(knight));
    knight.actionHolder().start(spawn, knight.actionHolder());
    match.getBattle().step();
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0)).startsWith("miner " + DURATION + " [set_target knight 0 0 1");
    assertThat(miner.getBuffs().carries(BUFF)).isTrue();
    assertThat(miner.getTargeting().getReference()).isSameAs(knight.getTargetView());

    // Under the taunt it switches lanes; the taunt's first step on it routing across lets it go.
    miner.requestAbility();
    while (miner.getView().getState() != GridEntityState.INGAME_PATHFIND) {
      match.getBattle().step();
      assertThat(++tick).as("the Mighty Miner switches lanes").isLessThan(TICKS);
    }
    // The drop comes before the step's countdown: the duration stays as the last step left it.
    int left = durationOf(lines.get(lines.size() - 1));
    lines.clear();
    match.getBattle().step();
    assertThat(miner.getView().getState()).isEqualTo(GridEntityState.INGAME_PATHFIND);
    assertThat(lines)
        .containsExactly(
            "miner %d [remaining 0, set_target null 0 0 0, finish, remove_buff %s]"
                .formatted(left, BUFF));
    assertThat(miner.getBuffs().carries(BUFF)).isFalse();
    assertThat(miner.getTargeting().getReference()).isNull();
    assertThat(miner.getTargeting().getRetargetCooldownMs()).isZero();
    assertThat(miner.actionHolder().running())
        .noneMatch(run -> run.getAction().name().equals(TAUNT) && !run.isFinished());
  }

  /** The duration a taunt line reports, its second word. */
  private static int durationOf(String line) {
    return Integer.parseInt(line.split(" ")[1]);
  }
}
