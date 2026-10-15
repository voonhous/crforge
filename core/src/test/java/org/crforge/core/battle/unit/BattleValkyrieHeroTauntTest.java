/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.pathfinding.GridEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hero Valkyrie's whirlwind ending under the hero Knight's taunt. The game lists a new run on
 * its holder before it calls the run's own start, so the attack chain is listed before the interval
 * its begin starts at once. As the hero's earlier runs leave, each by a swap with the last, the
 * interval comes to stand before the chain, and on the step the spin's timer reaches its most the
 * interval is stopped first and the taunt's run, swapped into its place, steps before the chain: it
 * finds the hero still on the Knight and only locks the selector, and then the chain's end drops
 * the reference and clears the re-selection wait. Nothing is marked in the targeting queue, so once
 * the finishing action's buff is gone the hero selects anew before the taunt forces it back.
 */
class BattleValkyrieHeroTauntTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The whirlwind's attack chain. */
  private static final String CHAIN = "ValkyrieHero_Execute_Charge";

  /** The interval the chain's begin starts, which spawns the whirlwind's area effect. */
  private static final String INTERVAL = Shipped.actionName(CHAIN, "OnChainBegan");

  /** The buff the chain's finishing action puts on the hero, which keeps it from attacking. */
  private static final String FORBID_BUFF =
      Shipped.text(
          Shipped.actionNames(Shipped.actionName(CHAIN, "OnFinishedAction"), "SubActions").get(1),
          "SpawnData");

  /** The hero Knight ability's spawn of the taunting area effect. */
  private static final String TAUNT_SPAWN = "Knight_hero_CreateTauntAEO";

  /** The Valkyrie first, in the hero slot, and seven other cards. */
  private static final List<String> HERO_DECK =
      List.of(
          "Valkyrie", "Archer", "Goblins", "Knight", "Minions", "Musketeer", "Fireball", "Arrows");

  /** The other side's cards, the Musketeer among them. */
  private static final List<String> OTHER_DECK =
      List.of("Knight", "Giant", "Archer", "Goblins", "Minions", "Musketeer", "Fireball", "Arrows");

  /** The tick the ability command runs on. */
  private static final int CAST = 300;

  /**
   * The hero Valkyrie whirling at a Musketeer, and every taunt line and queue flush of the hero.
   */
  private static final class Scene {
    final Standard1v1Battle battle;
    final CharacterEntity hero;
    final List<String> taunts = new ArrayList<>();
    final List<String> flushes = new ArrayList<>();

    Scene() {
      GameTables tables = GameData.tables();
      BattleRecords records = new BattleRecords(tables);
      battle = battle(tables);
      battle.play(200, records.card("Valkyrie"), LEVEL, 0, 3500, 15500, "v");
      // A Musketeer that stands its ground, within the hero's circle but out of its reach.
      battle.play(205, records.card("Musketeer"), LEVEL, 1, 3500, 20500, "m");
      stepTo(CAST - 20);
      hero = named(battle, "ValkyrieHero").get(0);
      battle.useAbility(CAST, 0, hero.name(), "a");
      battle
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
                  if (unit == hero) {
                    taunts.add(calls.toString());
                  }
                }

                @Override
                public void targetQueueFlushed(int tick, CharacterEntity unit) {
                  if (unit == hero) {
                    flushes.add(String.valueOf(tick));
                  }
                }
              });
    }

    int tick() {
      return battle.getBattle().getTick();
    }

    void stepTo(int tick) {
      while (tick() < tick) {
        battle.getBattle().step();
      }
    }

    /** The names of the hero's listed runs, in the holder's order. */
    List<String> runs() {
      return hero.actionHolder().running().stream()
          .map(ActionInstance::getAction)
          .map(BattleAction::name)
          .toList();
    }
  }

  @Test
  @DisplayName(
      "the attack chain is listed before the interval its begin starts at once, as the game lists"
          + " a run before calling its start")
  void chainListedBeforeTheIntervalItBegins() {
    Scene scene = new Scene();
    while (!scene.runs().contains(INTERVAL)) {
      scene.stepTo(scene.tick() + 1);
    }
    List<String> runs = scene.runs();
    assertThat(runs.indexOf(CHAIN)).isNotNegative().isLessThan(runs.indexOf(INTERVAL));
  }

  @Test
  @DisplayName(
      "a taunt armed before the whirlwind ends steps before the chain's end on its last step, so"
          + " nothing is marked, the re-selection wait is cleared and no flush takes the Knight"
          + " back once the hero may attack again")
  void tauntStepsBeforeTheChainEnds() {
    // The step the chain ends on, from a run with no taunt: the finishing action's buff is on the
    // hero from that step.
    Scene plain = new Scene();
    plain.stepTo(CAST);
    while (!plain.hero.getBuffs().carries(FORBID_BUFF)) {
      plain.stepTo(plain.tick() + 1);
    }
    int end = plain.tick();

    // The same battle, with a hero Knight placed beside the hero a few steps before and its taunt
    // armed on the hero.
    Scene scene = new Scene();
    scene.stepTo(end - 5);
    GridEntity view = scene.hero.getView();
    CharacterEntity knight =
        scene.battle.deploy(
            end - 5,
            GameData.unit("KnightHero"),
            LEVEL,
            1,
            view.getX() + 2000,
            view.getY(),
            "knight");
    scene.stepTo(end - 4);
    BattleAction spawn =
        GameData.actions().build(TAUNT_SPAWN, scene.battle.getWorld().binding(knight));
    knight.actionHolder().start(spawn, knight.actionHolder());
    scene.stepTo(end - 1);
    assertThat(scene.hero.getTargeting().getReference()).isSameAs(knight.getTargetView());

    // The last step: the interval stops, the taunt steps in its place and finds the Knight still
    // the reference, then the chain ends.
    scene.taunts.clear();
    scene.stepTo(end);
    assertThat(scene.hero.getBuffs().carries(FORBID_BUFF)).isTrue();
    assertThat(scene.taunts).containsExactly("[raise LOCK_TARGET]");
    assertThat(scene.hero.getTargeting().getReference()).isNull();
    assertThat(scene.hero.getTargeting().getRetargetCooldownMs()).isZero();

    // Nothing in the targeting queue: once the hero may attack again, no flush takes the Knight.
    while (scene.hero.getBuffs().carries(FORBID_BUFF)
        || !scene.hero.isActive(CharacterEntity.TARGETING_SLOT)) {
      scene.stepTo(scene.tick() + 1);
    }
    scene.stepTo(scene.tick() + 2);
    assertThat(scene.flushes).isEmpty();
  }

  /** A battle with the Valkyrie hero form in side 0's hand. */
  private static Standard1v1Battle battle(GameTables tables) {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "Valkyrie"); word++) {
      battle = new Standard1v1Battle(tables);
      match = battle.startLadderMatch(HERO_DECK, OTHER_DECK, word, 0, heroFirst(), new int[8]);
    }
    return battle;
  }

  /** Slot flags with the first card in the hero slot. */
  private static int[] heroFirst() {
    int[] slots = new int[8];
    slots[0] = MatchSide.HERO_SLOT;
    return slots;
  }

  private static boolean inHand(LadderMatch match, String card) {
    List<MatchCard> deck = match.side(0).deck();
    return Arrays.stream(match.side(0).getHand().slots())
        .anyMatch(index -> index >= 0 && deck.get(index).name().equals(card));
  }

  /** The characters of a row the holder lists, in its order. */
  private static List<CharacterEntity> named(Standard1v1Battle battle, String row) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(CharacterEntity.class::isInstance)
        .map(CharacterEntity.class::cast)
        .filter(unit -> unit.getData().name().equals(row))
        .toList();
  }
}
