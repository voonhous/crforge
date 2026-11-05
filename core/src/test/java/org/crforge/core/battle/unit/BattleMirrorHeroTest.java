/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.match.EvolutionItem;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.match.MirrorItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Mirror in its deck's hero slot. Its item carries the hero form field, but the Mirror lists no
 * row in the hero form, so it plays as the Mirror itself: it repeats the last card as a Mirror in
 * no slot does, the card itself after a plain play, its hero row after a hero play, one level above
 * the Mirror's for the Mirror's cost plus the card's, and its plays move no count.
 */
class BattleMirrorHeroTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The start cooldown: the first tick a card play of a match may run. */
  private static final int OPEN = Shipped.startCooldownTicks();

  /** The ticks between two plays of side 0, so that each Mirror finds the play before it run. */
  private static final int AFTER_A_PLAY = 22;

  /**
   * The Mirror first, in the hero slot, a Knight and an Archer, the cards the Mirrors repeat, and
   * five spells cast on side 0's own half to cycle the hand, so that no tower falls.
   */
  private static final List<String> DECK =
      List.of("Mirror", "Knight", "Archer", "Zap", "Arrows", "Fireball", "Poison", "Rage");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  private static final int[] NO_SLOTS = new int[8];

  @TempDir static Path folder;

  /**
   * The configured tables with the evolved Mirror's DarkElixirCost written 1, so that a counted
   * Mirror would be evolved on its second play, the evolved Mirror's level offset one above the
   * Mirror's own, so the two tell apart, and the Archer listing no evolved or hero row.
   */
  private static GameTables tables;

  private static int mirrorLevelOffset;

  @BeforeAll
  static void writeTheRows() throws IOException {
    mirrorLevelOffset =
        Shipped.number(Shipped.row("globals", "MIRROR_LEVEL_OFFSET"), "NumberValue");
    String mirrorEvolved =
        GameData.records().matchCard("Mirror").formRow(MatchCard.EVO_FORM).name();
    GameData.altered(
        folder,
        "spells_evolved",
        rows -> GameData.columns(rows, mirrorEvolved).put("DarkElixirCost", 1));
    GameData.alterLoaded(
        folder,
        "globals",
        rows ->
            GameData.columns(rows, "MIRROR_EVO_LEVEL_OFFSET")
                .put("NumberValue", mirrorLevelOffset + 1));
    GameData.alterLoaded(
        folder,
        "spells_characters",
        rows -> GameData.columns(rows, "Archer").putArray("EvolvedSpells"));
    tables = GameTables.load(folder);
  }

  /** Slot flags with the first card's set to the given flags. */
  private static int[] first(int flags) {
    int[] slots = new int[8];
    slots[0] = flags;
    return slots;
  }

  @Test
  @DisplayName(
      "a Mirror in the hero slot after a plain play of a card with a hero form repeats the card"
          + " itself, not its hero row, MIRROR_LEVEL_OFFSET above the Mirror's level, for the"
          + " Mirror's cost plus the card's, and its item carries the hero form field")
  void aHeroMirrorRepeatsACardWithAHeroFormAsItself() {
    Standard1v1Battle battle = new Standard1v1Battle(tables);
    LadderMatch match =
        battle.startLadderMatch(DECK, KNIGHTS, 0, 0, first(MatchSide.HERO_SLOT), NO_SLOTS);
    MatchCard knight = match.side(0).deck().get(1);
    assertThat(knight.formRow(MatchCard.HERO_FORM)).isNotSameAs(knight);
    assertThat(match.side(0).deck().get(0).formRow(MatchCard.HERO_FORM))
        .isSameAs(match.side(0).deck().get(0));

    Standard1v1Battle.Play play = mirrorAfter(battle, match, "Knight");

    MirrorItem item = play.mirror();
    assertThat(item.field()).isEqualTo(EvolutionItem.HERO);
    assertThat(item.count()).isZero();
    assertThat(item.repeats().name()).isEqualTo("Knight");
    assertThat(item.level()).isEqualTo(LEVEL + mirrorLevelOffset);
    assertThat(item.cost()).isEqualTo(Shipped.cost("Mirror") + knight.cost());
    assertThat(play.result().placed()).isTrue();
    DeployCard cast = battle.getWorld().getRecords().card("Knight");
    assertThat(play.units())
        .singleElement()
        .satisfies(unit -> assertThat(unit.getData().name()).isEqualTo(cast.unitAt(0).name()));
    // The last card stays the card the Mirror repeated.
    assertThat(match.side(0).lastPlayed().name()).isEqualTo("Knight");
  }

  @Test
  @DisplayName(
      "a Mirror in the hero slot after a card with no hero form repeats the card, and its plays"
          + " move no count: every one carries the hero form field, none is evolved")
  void aHeroMirrorRepeatsACardWithNoHeroFormAndMovesNoCount() {
    Standard1v1Battle battle = new Standard1v1Battle(tables);
    LadderMatch match =
        battle.startLadderMatch(DECK, KNIGHTS, 0, 0, first(MatchSide.HERO_SLOT), NO_SLOTS);
    List<Standard1v1Battle.Play> mirrors = new ArrayList<>();
    for (int play = 0; play < 3; play++) {
      mirrors.add(mirrorAfter(battle, match, "Archer"));
    }

    assertThat(mirrors)
        .allSatisfy(
            play -> {
              assertThat(play.mirror().field()).isEqualTo(EvolutionItem.HERO);
              assertThat(play.mirror().count()).isZero();
              assertThat(play.mirror().repeats().name()).isEqualTo("Archer");
              assertThat(play.mirror().level()).isEqualTo(LEVEL + mirrorLevelOffset);
              assertThat(play.units())
                  .allSatisfy(u -> assertThat(u.getData().name()).isEqualTo("Archer"));
            });
    assertThat(match.side(0).evolutionCount(0)).isZero();
  }

  @Test
  @DisplayName(
      "a Mirror in the hero slot after a hero play repeats the card's hero row, as a Mirror in no"
          + " slot does")
  void aHeroMirrorRepeatsAHeroPlayAsItsHeroRow() {
    int[] slots = first(MatchSide.HERO_SLOT);
    slots[1] = MatchSide.HERO_SLOT;
    Standard1v1Battle battle = new Standard1v1Battle(tables);
    LadderMatch match = battle.startLadderMatch(DECK, KNIGHTS, 0, 0, slots, NO_SLOTS);
    MatchCard hero = match.side(0).deck().get(1).formRow(MatchCard.HERO_FORM);

    Standard1v1Battle.Play play = mirrorAfter(battle, match, "Knight");

    MirrorItem item = play.mirror();
    assertThat(item.field()).isEqualTo(EvolutionItem.HERO);
    assertThat(item.repeats().name()).isEqualTo(hero.name());
    assertThat(item.level()).isEqualTo(LEVEL + mirrorLevelOffset);
    assertThat(item.cost()).isEqualTo(Shipped.cost("Mirror") + hero.cost());
  }

  @Test
  @DisplayName(
      "the Mirror in a slot that is both the evolution and the hero slot, which no recorded battle"
          + " holds, is refused")
  void aMirrorInBothSlotsIsRefused() {
    assertThatThrownBy(
            () ->
                new Standard1v1Battle(tables)
                    .startLadderMatch(
                        DECK,
                        KNIGHTS,
                        0,
                        0,
                        first(MatchSide.EVOLUTION_SLOT | MatchSide.HERO_SLOT),
                        NO_SLOTS))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Mirror in a slot that is both the evolution and the hero slot");
  }

  /**
   * Plays side 0's cards until a Mirror play has run right after a play of the given card, and
   * answers that Mirror's play: the card while the Mirror is in the hand, then the Mirror; any
   * other card of the hand while the Mirror or the card is not in it, a spell cast on side 0's own
   * half. Each play waits for the elixir that covers it.
   */
  private static Standard1v1Battle.Play mirrorAfter(
      Standard1v1Battle battle, LadderMatch match, String card) {
    MatchSide side = match.side(0);
    int mirror = match.deckIndex(0, "Mirror");
    int wanted = match.deckIndex(0, card);
    for (int round = 0; round < 40; round++) {
      List<Integer> hand = IntStream.of(side.getHand().slots()).boxed().toList();
      boolean after = side.lastPlayed() != null && side.lastPlayed().name().equals(card);
      int pick;
      if (hand.contains(mirror) && after) {
        pick = mirror;
      } else if (hand.contains(mirror) && hand.contains(wanted)) {
        pick = wanted;
      } else {
        pick = hand.stream().filter(i -> i != mirror && i != wanted).findFirst().orElseThrow();
      }
      String name = side.deck().get(pick).name();
      int cost =
          pick == mirror ? match.mirrorItem(0, mirror, LEVEL).cost() : match.item(0, pick).cost();
      int tick =
          Math.max(
              Math.max(OPEN + 20, battle.getBattle().getTick() + AFTER_A_PLAY),
              coveredFrom(battle, cost));
      String play = name + "@" + tick;
      if (pick == mirror) {
        battle.playMirror(tick, "Mirror", LEVEL, 0, 14500, 10000, play);
      } else {
        DeployCard deck = battle.getWorld().getRecords().card(name);
        battle.play(
            tick, deck, LEVEL, 0, deck.spell() ? 14500 : 3500, deck.spell() ? 4000 : 10000, play);
      }
      run(battle, tick);
      Standard1v1Battle.Play ran = battle.getPlays().get(battle.getPlays().size() - 1);
      assertThat(ran.matchCode()).as(play).isZero();
      if (pick == mirror) {
        return ran;
      }
    }
    throw new AssertionError("no Mirror after " + card + " in 40 plays");
  }

  /** Steps the battle until side 0's whole elixir covers a cost, and answers the tick then. */
  private static int coveredFrom(Standard1v1Battle battle, int cost) {
    while (battle.getMatch().side(0).wholeElixir() < cost) {
      battle.getBattle().step();
    }
    return battle.getBattle().getTick();
  }

  /** Steps the battle until it has run the given tick, and one step more. */
  private static void run(Standard1v1Battle battle, int lastTick) {
    while (battle.getBattle().getTick() <= lastTick) {
      battle.getBattle().step();
    }
  }
}
