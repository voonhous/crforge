/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Goblins' hero form: its champion slot follows the banner the card links, the goblins hold the
 * slot's button state while they live, and the last goblin to fall leaves the banner on its spot,
 * which stands for its timer and the wait after it and then goes; an ability command naming the
 * banner has it cast and send the second wave of goblins before it goes.
 */
class BattleGoblinHeroTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String BANNER = "GoblinHero_Flag_Building";

  /** The goblins of the banner's second wave. */
  private static final String DUMMY = "Goblin_dummy";

  /** The Goblins first, in the hero slot, and seven other cards. */
  private static final List<String> GOBLINS_DECK =
      List.of("Goblins", "Archer", "Knight", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  /** The Goblins first, in the hero slot, and seven cheap cards to cycle it back to the hand. */
  private static final List<String> CYCLE_DECK =
      List.of(
          "Goblins",
          "Skeletons",
          "IceSpirits",
          "FireSpirits",
          "ElectroSpirit",
          "Bats",
          "Knight",
          "Arrows");

  /**
   * Steps a banner stands: its timer's interval, then the wait its about-to-disappear group's kill
   * waits, each in whole steps.
   */
  private static final int BANNER_STEPS =
      Shipped.ticks(Shipped.numbers("GoblinHero_Flag_Building_Timer", "Intervals").get(0))
          + Shipped.ticks(
              Shipped.numbers("GoblinHero_Flag_About_To_Disappears_Group", "SubActionsDelay")
                  .get(2));

  /** The banner's ability row. */
  private static final GameRow ABILITY =
      Shipped.row("character_abilities", Shipped.text(Shipped.unitRow(BANNER), "Ability"));

  /** The hero form's row of the Goblins card. */
  private static final GameRow HERO_FORM = Shipped.row("spells_hero_form", "Goblins_hero");

  @Test
  @DisplayName(
      "a hero slot's Goblins make the first champion slot follow the banner the hero form links,"
          + " with the card's deck index")
  void theSlotFollowsTheLinkedBanner() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(GOBLINS_DECK, KNIGHTS, 0, 0, heroFirst(), new int[8]);
    ChampionController slot = battle.getWorld().kingTower(0).championSlot(1);
    assertThat(slot.getChampion().name()).isEqualTo(BANNER);
    assertThat(slot.getDeckIndex()).isZero();
    assertThat(battle.getWorld().kingTower(0).championSlot(2).getChampion()).isNull();
  }

  @Test
  @DisplayName(
      "the hero goblins hold the slot's button state not yet available while they live, and the"
          + " play and their start leave the slot one charge; the last to fall leaves the banner on"
          + " its spot, a live copy of the slot, which goes after its timer and the wait")
  void theLastGoblinLeavesTheBanner() {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    for (int word = 0; match == null || !inHand(match, "Goblins"); word++) {
      battle = new Standard1v1Battle(GameData.tables());
      match = battle.startLadderMatch(GOBLINS_DECK, KNIGHTS, word, 0, heroFirst(), new int[8]);
    }
    ChampionController slot = battle.getWorld().kingTower(0).championSlot(1);
    int cost = GameData.records().matchCard("Goblins").cost();
    while (match.side(0).wholeElixir() < cost) {
      battle.getBattle().step();
    }
    int tick = battle.getBattle().getTick();
    battle.play(tick, GameData.card("Goblins"), LEVEL, 0, 3500, 14000, "g");
    step(battle);
    step(battle);
    assertThat(battle.getPlays().get(0).units())
        .extracting(unit -> unit.getData().name())
        .containsExactlyElementsOf(
            Collections.nCopies(
                Shipped.number(HERO_FORM, "SummonNumber"),
                Shipped.text(HERO_FORM, "SummonCharacter")));
    assertThat(slot.getState()).isEqualTo(ChampionController.NO_YET_AVAILABLE);
    assertThat(slot.getCharges()).isEqualTo(Shipped.number(ABILITY, "MaxCharges"));

    // Follow the goblins to their last fall: the banner stands where the last one stood.
    int limit = battle.getBattle().getTick() + 1000;
    int lastX = 0;
    int lastY = 0;
    while (!named(battle, "GoblinHero").isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      assertThat(named(battle, BANNER)).isEmpty();
      List<CharacterEntity> goblins = named(battle, "GoblinHero");
      lastX = goblins.get(0).getView().getX();
      lastY = goblins.get(0).getView().getY();
      assertThat(slot.getState()).isEqualTo(ChampionController.NO_YET_AVAILABLE);
      step(battle);
    }
    List<CharacterEntity> banners = named(battle, BANNER);
    assertThat(banners).hasSize(1);
    CharacterEntity banner = banners.get(0);
    assertThat(banner.getView().getX()).isEqualTo(lastX);
    assertThat(banner.getView().getY()).isEqualTo(lastY);
    int planted = battle.getBattle().getTick();

    step(battle);
    assertThat(slot.champions()).containsExactly(banner);
    while (!named(battle, BANNER).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(planted + 2 * BANNER_STEPS);
      step(battle);
    }
    assertThat(battle.getBattle().getTick() - planted).isEqualTo(BANNER_STEPS);
  }

  @Test
  @DisplayName(
      "a command naming the banner by its row is refused: a command by name reaches only a unit a"
          + " play made, and the banner is made by the last goblin's fall")
  void theBannerIsNotNamedByItsRow() {
    Standard1v1Battle battle = plantBanner(new LadderMatch[1]);
    CharacterEntity banner = named(battle, BANNER).get(0);
    battle.useAbility(battle.getBattle().getTick(), 0, banner.name(), "a");
    assertThatThrownBy(() -> step(battle))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("which no play of side 0 made");
  }

  @Test
  @DisplayName(
      "a command naming the banner by its game object id pays the ability's elixir and the banner"
          + " casts as a troop does: its trigger delay in the casting state, then the second wave,"
          + " two goblins its group's delay apart on either side of it and behind it, and the banner"
          + " goes at its group's kill")
  void theBannersAbilitySendsTheSecondWave() {
    LadderMatch[] match = new LadderMatch[1];
    Standard1v1Battle battle = plantBanner(match);
    CharacterEntity banner = named(battle, BANNER).get(0);
    int x = banner.getView().getX();
    int y = banner.getView().getY();
    battle.useAbility(battle.getBattle().getTick(), 0, banner.getId(), "wave");

    // The activation group's delays from the step the ability's trigger delay runs out: its two
    // spawns of the second wave, then its kill of the banner.
    int fire = Shipped.ticks(Shipped.number(ABILITY, "TriggerDelay"));
    List<Integer> delays =
        Shipped.numbers(Shipped.text(ABILITY, "OnActivationAction"), "SubActionsDelay");
    int firstSpawn = fire + Shipped.ticks(delays.get(1));
    int secondSpawn = fire + Shipped.ticks(delays.get(2));
    int kill = fire + Shipped.ticks(delays.get(3));
    // Follow the banner and the second wave one step at a time.
    int span = kill + 5;
    int[] state = new int[span];
    boolean[] standing = new boolean[span];
    int[] wave = new int[span];
    List<CharacterEntity> dummies = List.of();
    for (int k = 1; k < span; k++) {
      step(battle);
      standing[k] = !named(battle, BANNER).isEmpty();
      state[k] = banner.getView().getState();
      dummies = named(battle, DUMMY);
      wave[k] = dummies.size();
      if (k == 1) {
        // The command passes and pays the ability's elixir; the banner is the one requested.
        assertThat(battle.getAbilityUses()).hasSize(1);
        AbilityCommand.Outcome outcome = battle.getAbilityUses().get(0).outcome();
        assertThat(outcome.code()).isZero();
        assertThat(outcome.elixirBefore() - outcome.elixirAfter())
            .isEqualTo(Shipped.number(ABILITY, "ManaCost") * KingElixir.SCALE);
        assertThat(outcome.requested()).containsExactly(banner);
      }
      if (k == firstSpawn || k == secondSpawn) {
        // The two goblins of the wave stand where their expressions put them: beside the banner
        // toward the middle, then away from it, each half a tile behind it.
        CharacterEntity last = dummies.get(dummies.size() - 1);
        int toMiddle = x > 9000 ? -1000 : 1000;
        assertThat(last.getView().getX()).isEqualTo(k == firstSpawn ? x + toMiddle : x - toMiddle);
        assertThat(last.getView().getY()).isEqualTo(y - 500);
        assertThat(last.side()).isZero();
      }
    }
    // The casting state until the ability's cast time runs out, as its trigger delay runs out and
    // fires its activation group.
    int cast = Shipped.ticks(Shipped.number(ABILITY, "CastTime"));
    for (int k = 1; k < cast; k++) {
      assertThat(state[k]).as("step %d", k).isEqualTo(GridEntityState.CASTING);
    }
    assertThat(state[cast]).isNotEqualTo(GridEntityState.CASTING);
    // The group's two spawns, then its kill takes the banner.
    assertThat(wave[firstSpawn - 1]).isZero();
    assertThat(wave[firstSpawn]).isEqualTo(1);
    assertThat(wave[secondSpawn - 1]).isEqualTo(1);
    assertThat(wave[secondSpawn]).isEqualTo(2);
    assertThat(standing[kill - 1]).isTrue();
    assertThat(standing[kill]).isFalse();
    assertThat(wave[span - 1]).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "a new play of the hero Goblins while the banner stands is heard by the banner's listener,"
          + " the hero form tested against its card group's heroes: the banner goes in the step the"
          + " new goblins appear")
  void aNewHeroPlayTakesTheStandingBanner() {
    LadderMatch[] match = new LadderMatch[1];
    Standard1v1Battle battle = cycleMatch(match);
    playAndRun(battle, "Goblins", 3500, 14000, "g");
    cycleBackToGoblins(battle, match[0]);
    int limit = battle.getBattle().getTick() + 1000;
    while (named(battle, BANNER).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    step(battle);
    assertThat(named(battle, BANNER)).hasSize(1);
    assertThat(named(battle, "GoblinHero")).isEmpty();

    // The banner stands until the play runs and its goblins are made.
    playAndRun(battle, "Goblins", 3500, 14000, "h");
    while (named(battle, "GoblinHero").isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      assertThat(named(battle, BANNER)).hasSize(1);
      step(battle);
    }
    assertThat(named(battle, "GoblinHero")).hasSize(Shipped.number(HERO_FORM, "SummonNumber"));
    assertThat(named(battle, BANNER)).isEmpty();
  }

  @Test
  @DisplayName(
      "a new play of the hero Goblins while goblins of an earlier play live tags them through"
          + " their own listener: their last fall leaves no banner, and only the new goblins' last"
          + " fall plants one")
  void aNewHeroPlayTagsTheGoblinsStillAlive() {
    LadderMatch[] match = new LadderMatch[1];
    Standard1v1Battle battle = cycleMatch(match);
    playAndRun(battle, "Goblins", 3500, 14000, "g");
    step(battle);
    List<CharacterEntity> earlier = named(battle, "GoblinHero");
    assertThat(earlier).hasSize(Shipped.number(HERO_FORM, "SummonNumber"));
    cycleBackToGoblins(battle, match[0]);
    assertThat(named(battle, "GoblinHero")).containsAnyElementsOf(earlier);
    // The new goblins behind the earlier ones, which fall first.
    playAndRun(battle, "Goblins", 3500, 8000, "h");
    int limit = battle.getBattle().getTick() + 1000;
    while (named(battle, "GoblinHero").stream().anyMatch(earlier::contains)) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      assertThat(named(battle, BANNER)).isEmpty();
      step(battle);
    }
    assertThat(named(battle, "GoblinHero")).isNotEmpty();
    while (!named(battle, "GoblinHero").isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      assertThat(named(battle, BANNER)).isEmpty();
      step(battle);
    }
    assertThat(named(battle, BANNER)).hasSize(1);
  }

  /**
   * A match in which side 0 holds the hero Goblins in its hand and a full elixir bar, its other
   * seven cards cheap ones, so a play of the Goblins can come back to the hand while its goblins or
   * its banner stand.
   *
   * @param match receives the battle's match
   */
  private static Standard1v1Battle cycleMatch(LadderMatch[] match) {
    Standard1v1Battle battle = null;
    for (int word = 0; match[0] == null || !inHand(match[0], "Goblins"); word++) {
      battle = new Standard1v1Battle(GameData.tables());
      match[0] = battle.startLadderMatch(CYCLE_DECK, KNIGHTS, word, 0, heroFirst(), new int[8]);
    }
    int full = 10;
    while (match[0].side(0).wholeElixir() < full) {
      step(battle);
    }
    return battle;
  }

  /**
   * Plays the cheapest other card of side 0's hand at the back of the right lane, as soon as the
   * hand is full and the elixir is there, until the Goblins are back in the hand.
   */
  private static void cycleBackToGoblins(Standard1v1Battle battle, LadderMatch match) {
    int limit = battle.getBattle().getTick() + 400;
    int made = 0;
    while (!inHand(match, "Goblins")) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      List<MatchCard> deck = match.side(0).deck();
      int[] slots = match.side(0).getHand().slots();
      MatchCard cheapest = null;
      boolean full = true;
      for (int index : slots) {
        if (index < 0) {
          full = false;
        } else if (cheapest == null || deck.get(index).cost() < cheapest.cost()) {
          cheapest = deck.get(index);
        }
      }
      if (full && cheapest != null && match.side(0).wholeElixir() >= cheapest.cost()) {
        playAndRun(battle, cheapest.name(), 14500, 4000, "c" + made++);
      } else {
        step(battle);
      }
    }
  }

  /** Gives side 0's play of a card and steps until the play has run, which the match passed. */
  private static void playAndRun(Standard1v1Battle battle, String card, int x, int y, String name) {
    int before = battle.getPlays().size();
    battle.play(battle.getBattle().getTick(), GameData.card(card), LEVEL, 0, x, y, name);
    while (battle.getPlays().size() == before) {
      step(battle);
    }
    assertThat(battle.getPlays().get(before).matchCode()).as(name).isZero();
  }

  /**
   * A battle in which side 0 played the hero Goblins and its last goblin has just fallen, leaving
   * the banner.
   *
   * @param match receives the battle's match
   */
  private static Standard1v1Battle plantBanner(LadderMatch[] match) {
    Standard1v1Battle battle = null;
    for (int word = 0; match[0] == null || !inHand(match[0], "Goblins"); word++) {
      battle = new Standard1v1Battle(GameData.tables());
      match[0] = battle.startLadderMatch(GOBLINS_DECK, KNIGHTS, word, 0, heroFirst(), new int[8]);
    }
    int cost = GameData.records().matchCard("Goblins").cost();
    while (match[0].side(0).wholeElixir() < cost) {
      step(battle);
    }
    battle.play(battle.getBattle().getTick(), GameData.card("Goblins"), LEVEL, 0, 3500, 14000, "g");
    int limit = battle.getBattle().getTick() + 1000;
    while (named(battle, BANNER).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
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

  private static void step(Standard1v1Battle battle) {
    battle.getBattle().step();
  }
}
