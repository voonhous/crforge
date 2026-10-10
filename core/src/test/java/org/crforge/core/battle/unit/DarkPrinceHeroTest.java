/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Dark Prince's hero form: the ability's tap dismounts the hero. Its activation group swaps the
 * hero onto its walking row, which neither charges nor jumps the river, spawns the mount where the
 * hero stands, and runs the warp-back loop: a group gated on WARP_TIME below its bound whose parts
 * warp the hero back by the warp's WarpY and, a tick later, add a step to WARP_TIME and schedule
 * the group again. So the hero is warped back one step a tick, as many times as the steps fit under
 * the bound.
 *
 * <p>The scene is the recorded hero Dark Prince battle's: side 0's hero Dark Prince placed at
 * (3500, 14000) on 220, an enemy Musketeer at (3500, 21500) on 270, the ability tapped on 311.
 */
class DarkPrinceHeroTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The Dark Prince first, in the hero slot, and seven other cards. */
  private static final List<String> HERO_DECK =
      List.of(
          "DarkPrince", "Archer", "Goblins", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  /** The other side's cards, the Musketeer among them. */
  private static final List<String> OTHER_DECK =
      List.of("Knight", "Archer", "Goblins", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  /** The tick the ability command runs on. */
  private static final int TAP = 311;

  /** The hero's activation group. */
  private static final String ACTIVATION =
      Shipped.text(
          Shipped.row(
              "character_abilities", Shipped.text(Shipped.unitRow("DarkPrinceHero"), "Ability")),
          "OnActivationAction");

  @Test
  @DisplayName(
      "the tap swaps the hero onto its walking row and spawns its mount; the warp-back loop then"
          + " warps it back a tick, as many times as its steps fit under its bound")
  void theTapDismountsAndWarpsBack() {
    GameTables tables = GameData.tables();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = battle(tables);
    battle.play(220, records.card("DarkPrince"), LEVEL, 0, 3500, 14000, "h");
    battle.play(270, records.card("Musketeer"), LEVEL, 1, 3500, 21500, "m");
    stepTo(battle, TAP - 20);
    CharacterEntity hero = named(battle, "DarkPrinceHero").get(0);
    battle.useAbility(TAP, 0, hero.name(), "a");
    stepTo(battle, TAP);
    // The activation group's parts: the swap, the warp-back group and the mount's spawn.
    List<String> parts = Shipped.actionNames(ACTIVATION, "SubActions");
    String walking = Shipped.text(parts.get(1), "NewCharacterData");
    String warpGroup = parts.get(2);
    String mount = Shipped.text(parts.get(4), "SpawnData");
    List<String> loop = Shipped.actionNames(warpGroup, "SubActions");
    int warpY = Shipped.number(loop.get(0), "WarpY");
    // The loop's bound and its step, as its gate and its increment write them.
    int bound = number("WARP_TIME < (\\d+)", Shipped.text(warpGroup, "ExecuteIfTrue"));
    int increment = number("WARP_TIME \\+ (\\d+)", Shipped.text(loop.get(1), "Value"));
    int warps = (bound + increment - 1) / increment;
    int before = hero.getView().getY();
    List<Integer> steps = new ArrayList<>();
    int last = before;
    for (int k = 1; k <= warps + 4; k++) {
      stepTo(battle, TAP + k);
      if (k == 1) {
        assertThat(hero.getData().name()).isEqualTo(walking);
        assertThat(named(battle, mount)).hasSize(1);
      }
      int y = hero.getView().getY();
      steps.add(y - last);
      last = y;
    }
    // The warps back toward its own side, one a tick from the tap's own step, then none.
    List<Integer> expected = new ArrayList<>(Collections.nCopies(warps, warpY));
    expected.addAll(Collections.nCopies(4, 0));
    assertThat(steps).containsExactlyElementsOf(expected);
    assertThat(before - last).isEqualTo(-warpY * warps);
  }

  /** The number a pattern's one group finds in an expression. */
  private static int number(String pattern, String expression) {
    Matcher matcher = Pattern.compile(pattern).matcher(expression);
    assertThat(matcher.matches()).as(expression).isTrue();
    return Integer.parseInt(matcher.group(1));
  }

  /** A battle with the Dark Prince hero form in side 0's hand. */
  private static Standard1v1Battle battle(GameTables tables) {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "DarkPrince"); word++) {
      battle = new Standard1v1Battle(tables);
      match = battle.startLadderMatch(HERO_DECK, OTHER_DECK, word, 0, heroFirst(), new int[8]);
    }
    return battle;
  }

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

  private static List<CharacterEntity> named(Standard1v1Battle battle, String row) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(CharacterEntity.class::isInstance)
        .map(CharacterEntity.class::cast)
        .filter(unit -> unit.getData().name().equals(row))
        .toList();
  }

  private static void stepTo(Standard1v1Battle battle, int tick) {
    while (battle.getBattle().getTick() < tick) {
      battle.getBattle().step();
    }
  }
}
