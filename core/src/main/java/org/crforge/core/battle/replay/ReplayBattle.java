/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.replay;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.Standard1v1Battle;

/**
 * The battle a translated replay scenario gives ({@link ScenarioPlan}), built on the battle core,
 * and the check of each play's item as the battle runs it. A conformance run and the replay viewer
 * build and check a replay's battle the same way, through this class.
 */
public final class ReplayBattle {

  private ReplayBattle() {
    // Utility class
  }

  /**
   * Builds the battle a translated scenario gives, before its first step: the towers, the seed,
   * each player's data, the Ladder match between the two decks, and every command queued in the
   * scenario's order, each play and ability command named {@code cmd<index>} after its index in the
   * scenario.
   *
   * @param tables the game tables
   * @param plan the translated scenario
   * @return the battle, not yet stepped
   */
  public static Standard1v1Battle build(GameTables tables, ScenarioPlan plan) {
    Standard1v1Battle battle = new Standard1v1Battle(tables, plan.towers(), true);
    battle.getWorld().seed(plan.seed());
    // Each player's data is handed over before the decks are dealt, in the scenario's order.
    for (int choices : plan.playerDataChoices()) {
      battle.addPlayerData(choices);
    }
    // A player's word, which joins its deck shuffle's draw, is the low word of its account id. Each
    // deck card comes with its slot flags.
    battle.startLadderMatch(
        plan.decks().get(0),
        plan.decks().get(1),
        plan.accounts().get(0)[1],
        plan.accounts().get(1)[1],
        plan.slotFlags().get(0),
        plan.slotFlags().get(1));
    // The commands are queued in the scenario's order, plays and ability commands alike: within a
    // tick they run in the order they were queued.
    Map<Integer, ScenarioPlan.Play> playsByIndex = new HashMap<>();
    Map<Integer, ScenarioPlan.Ability> abilitiesByIndex = new HashMap<>();
    plan.plays().forEach(play -> playsByIndex.put(play.index(), play));
    plan.abilities().forEach(ability -> abilitiesByIndex.put(ability.index(), ability));
    int commands = plan.plays().size() + plan.abilities().size();
    for (int index = 0; index < commands; index++) {
      ScenarioPlan.Play play = playsByIndex.get(index);
      ScenarioPlan.Ability ability = abilitiesByIndex.get(index);
      if (play != null) {
        if (play.repeats() != null) {
          // A Mirror's play repeats its side's last card, as the battle's Mirror builds it.
          battle.playMirror(
              play.runTick(),
              play.card(),
              play.level(),
              play.side(),
              play.x(),
              play.y(),
              "cmd" + index);
        } else if (play.option() != null) {
          // A variant card's play runs as the option the battle's player picks as it gives it.
          battle.playVariant(
              play.runTick(),
              play.card(),
              play.level(),
              play.side(),
              play.x(),
              play.y(),
              "cmd" + index);
        } else {
          battle.play(
              play.runTick(),
              battle.getWorld().getRecords().card(play.card()),
              play.level(),
              play.side(),
              play.x(),
              play.y(),
              "cmd" + index);
        }
      } else {
        battle.useAbility(ability.runTick(), ability.side(), ability.objectId(), "cmd" + index);
      }
    }
    return battle;
  }

  /**
   * Checks the items of the plays that ran since the last check against the scenario's.
   *
   * @param battle the battle {@link #build} built from the plan
   * @param plan the translated scenario
   * @param checked how many of the battle's plays have been checked
   * @return how many have been checked now
   * @throws UnsupportedScenarioException for a play whose item is not the one the battle built
   */
  public static int checkItems(Standard1v1Battle battle, ScenarioPlan plan, int checked) {
    List<Standard1v1Battle.Play> run = battle.getPlays();
    if (checked == run.size()) {
      return checked;
    }
    Map<String, ScenarioPlan.Play> planned = new HashMap<>();
    plan.plays().forEach(play -> planned.put("cmd" + play.index(), play));
    for (int i = checked; i < run.size(); i++) {
      Standard1v1Battle.Play play = run.get(i);
      ScenarioPlan.Play given = planned.get(play.name());
      if (given == null) {
        throw new IllegalStateException(
            "the battle ran a play the scenario does not give: " + play);
      }
      if (given.repeats() != null) {
        ReplayScenario.checkMirrorItem(given, play.mirror());
        continue;
      }
      if (given.option() != null) {
        ReplayScenario.checkVariantItem(given, play.variant());
        continue;
      }
      int deckIndex = plan.decks().get(given.side()).indexOf(given.card());
      ReplayScenario.checkItem(
          given, plan.slotFlags().get(given.side())[deckIndex], play.evolution());
    }
    return run.size();
  }
}
