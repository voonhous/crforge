/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.CardPlacement;
import org.crforge.core.battle.match.EvolutionItem;
import org.crforge.core.battle.match.Hand;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A visualizer session on the battle core: its plays go through the battle's play path, and a
 * refused play becomes a message instead of a crash.
 */
class BattleSessionTest {

  /** A Knight and an Archer, then the Mirrors the shuffle holds back for the refills. */
  private static final List<String> MIRRORS =
      List.of("Knight", "Archer", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror");

  /** A deck of Zaps only. */
  private static final List<String> ZAPS = Collections.nCopies(8, "Zap");

  @Test
  @DisplayName(
      "the default decks deal, and every card of them plays without halting the battle, blue's"
          + " hero slots in their hero form and its evolution slot's card evolved")
  void theDefaultDecksPlay() {
    BattleSession session = BattleSession.ladder(GameData.tables());
    List<List<String>> decks = List.of(BattleDecks.BLUE, BattleDecks.RED);
    List<int[]> slotFlags = List.of(BattleDecks.blueSlots(), BattleDecks.redSlots());
    // Each side plays its hand slots in turn, each as soon as the client offers it, so its hand
    // cycles through its whole deck; an evolution slot's card is played whenever it is offered, so
    // its count is reached within the battle. Up to five minutes of battle, the regular time and
    // the overtime, or until the match is over.
    int[] turn = new int[2];
    for (int step = 0; step < 6000 && !session.isOver(); step++) {
      for (int side = 0; side < 2; side++) {
        int y = side == 0 ? 8500 : 23500;
        int evolution = offeredEvolutionSlot(session, side, slotFlags.get(side));
        if (evolution >= 0) {
          session.play(side, evolution, 3500, y);
        } else if (session.cardUnavailableReason(side, turn[side]) == null
            && session.play(side, turn[side], 3500, y)) {
          turn[side] = (turn[side] + 1) % 4;
        }
      }
      assertThat(session.step()).as("step %d: %s", step, session.messages()).isTrue();
    }
    assertThat(session.getHalted()).isNull();
    List<Standard1v1Battle.Play> plays = session.getBattle().getPlays();
    assertThat(plays).hasSizeGreaterThan(16);
    // Every play is placed as it runs, but one given before the match was decided and run after.
    assertThat(plays)
        .allSatisfy(
            play -> {
              if (play.matchCode() != LadderMatch.OVER) {
                assertThat(play.matchCode()).as("%s's match code", play.name()).isZero();
                assertThat(play.result().placed()).as("%s placed", play.name()).isTrue();
              }
            });

    // Every card of both decks is played, a hero slot's card always in its hero form, an evolution
    // slot's card evolved once its count is reached and plain otherwise, any other card plain.
    for (int side = 0; side < 2; side++) {
      int s = side;
      List<EvolutionItem> items =
          plays.stream()
              .filter(play -> play.side() == s && play.matchCode() == 0)
              .map(Standard1v1Battle.Play::evolution)
              .toList();
      for (int index = 0; index < decks.get(side).size(); index++) {
        int deckIndex = index;
        List<EvolutionItem> card = items.stream().filter(i -> i.index() == deckIndex).toList();
        String name = "side " + side + "'s " + decks.get(side).get(index);
        int flags = slotFlags.get(side)[index];
        assertThat(card).as(name).isNotEmpty();
        if ((flags & MatchSide.HERO_SLOT) != 0) {
          assertThat(card).as(name).allMatch(i -> i.field() == EvolutionItem.HERO);
        } else if ((flags & MatchSide.EVOLUTION_SLOT) != 0) {
          assertThat(card)
              .as(name)
              .anyMatch(i -> i.field() == EvolutionItem.EVOLVED)
              .anyMatch(i -> i.field() == 0);
        } else {
          assertThat(card).as(name).allMatch(i -> i.field() == 0);
        }
      }
    }
  }

  @Test
  @DisplayName("a note from the screen joins the session's messages")
  void aNoteIsKept() {
    BattleSession session = BattleSession.ladder(GameData.tables());

    session.note("data version 2.0.0 refused");

    assertThat(session.messages()).last().isEqualTo("[0] data version 2.0.0 refused");
  }

  @Test
  @DisplayName("a play the elixir does not cover is refused on the spot with a message")
  void notEnoughElixir() {
    BattleSession session = only("Golem");
    int elixir = session.match().side(0).wholeElixir();

    assertThat(session.cardUnavailableReason(0, 0)).contains("not enough elixir");
    assertThat(BattleAdapter.frame(session).sides().get(0).hand().get(0).unavailableReason())
        .isEqualTo(session.cardUnavailableReason(0, 0));
    assertThat(session.play(0, 0, 9500, 8500)).isFalse();

    assertThat(elixir).isLessThan(Shipped.cost("Golem"));
    assertThat(session.messages()).last().asString().contains("Golem refused, not enough elixir");
    assertThat(session.isPending(0, 0)).isFalse();
  }

  @Test
  @DisplayName("a message about a side names it as the reader names the sides when it is read")
  void aSidesMessageIsNamedWhenRead() {
    BattleSession session = only("Golem");
    String now = "[" + session.tick() + "]";

    session.play(0, 0, 9500, 8500);
    session.note(1, " taps a test ability");
    session.note("no side named");

    List<String> standing = session.messages();
    assertThat(standing.get(standing.size() - 3)).startsWith(now + " blue: Golem refused");
    assertThat(standing.get(standing.size() - 2)).isEqualTo(now + " red taps a test ability");
    List<String> flipped = session.messages(side -> side == 1 ? "blue" : "red");
    assertThat(flipped.get(flipped.size() - 3)).startsWith(now + " red: Golem refused");
    assertThat(flipped.get(flipped.size() - 2)).isEqualTo(now + " blue taps a test ability");
    assertThat(flipped).last().isEqualTo(now + " no side named");
  }

  @Test
  @DisplayName("a card already played is refused until its play has run, and then cycled")
  void aPendingCardIsRefused() {
    BattleSession session = only("Knight");

    assertThat(session.play(0, 0, 9500, 8500)).isTrue();
    assertThat(session.isPending(0, 0)).isTrue();
    assertThat(session.cardUnavailableReason(0, 0)).contains("already played");
    assertThat(session.play(0, 0, 9500, 8500)).isFalse();
    assertThat(session.messages()).last().asString().contains("already played");

    for (int i = 0; i <= Standard1v1Battle.PLAY_DELAY_TICKS + 1; i++) {
      session.step();
    }
    assertThat(session.getBattle().getPlays()).hasSize(1);
    assertThat(session.getBattle().getPlays().get(0).units()).hasSize(1);
    assertThat(session.isPending(0, 0)).isFalse();
  }

  @Test
  @DisplayName("the elixir check sets aside the cost of the side's plays still waiting to run")
  void pendingCostsAreSetAside() {
    BattleSession session = only("Musketeer");
    int elixir = session.match().side(0).wholeElixir();
    // One Musketeer is covered; a second while the first waits to run is not.
    assertThat(elixir).isBetween(Shipped.cost("Musketeer"), 2 * Shipped.cost("Musketeer") - 1);
    assertThat(session.play(0, 0, 3500, 8500)).isTrue();
    assertThat(session.cardUnavailableReason(0, 1)).contains("not enough elixir");
    assertThat(session.play(0, 1, 14500, 8500)).isFalse();
    assertThat(session.messages()).last().asString().contains("not enough elixir");
  }

  @Test
  @DisplayName(
      "a play is sent on the tile the search finds from the clicked point, one off the map"
          + " included, and the preview shows it placed there")
  void aPlayIsSentOnTheTileFound() {
    BattleSession session = only("Knight");
    CardPlacement.Result preview = session.preview(0, 0, -500, 8500);
    assertThat(preview.placed()).isTrue();

    assertThat(session.play(0, 0, -500, 8500)).isTrue();

    for (int i = 0; i <= Standard1v1Battle.PLAY_DELAY_TICKS + 1; i++) {
      session.step();
    }
    Standard1v1Battle.Play played = session.getBattle().getPlays().get(0);
    assertThat(new int[] {played.result().x(), played.result().y()})
        .containsExactly(preview.x(), preview.y());
  }

  @Test
  @DisplayName(
      "a Mirror whose king has no last card finds no tile and is refused on the spot with a"
          + " message")
  void noTile() {
    BattleSession session =
        open(BattleSession.ladder(GameData.tables(), MIRRORS, Collections.nCopies(8, "Knight")));
    int mirror = slot(session, "Mirror");
    assertThat(session.cardUnavailableReason(0, mirror)).contains("no legal tile");
    assertThat(session.play(0, mirror, 3500, 8500)).isFalse();
    assertThat(session.messages()).last().asString().contains("no legal tile");
  }

  @Test
  @DisplayName(
      "a step the battle core refuses to finish halts the session with its message, and no"
          + " further step runs")
  void aRefusedStepHalts() {
    // A Mirror given in the tick of another play of its side is refused inside the step.
    BattleSession session =
        BattleSession.ladder(GameData.tables(), MIRRORS, Collections.nCopies(8, "Knight"));
    // An Archer's play runs first, so the king's last card, which the screen's search places a
    // Mirror as, is placed on the Knight's tile.
    open(session);
    while (session.match().side(0).wholeElixir() < Shipped.cost("Archer")) {
      assertThat(session.step()).isTrue();
    }
    assertThat(session.play(0, slot(session, "Archer"), 3500, 8500)).isTrue();
    for (int i = 0; i <= Standard1v1Battle.PLAY_DELAY_TICKS + 1; i++) {
      assertThat(session.step()).isTrue();
    }
    int knight = slot(session, "Knight");
    int mirror = slot(session, "Mirror");
    // The elixir covers the Knight and the Mirror's item, which repeats the waiting Knight.
    int both = 2 * Shipped.cost("Knight") + Shipped.cost("Mirror");
    while (session.match().side(0).wholeElixir() < both) {
      assertThat(session.step()).isTrue();
    }
    assertThat(session.play(0, knight, 3500, 8500)).isTrue();
    assertThat(session.cardUnavailableReason(0, mirror)).isNull();
    assertThat(BattleAdapter.frame(session).sides().get(0).hand().get(mirror).unavailableReason())
        .isNull();
    assertThat(session.play(0, mirror, 14500, 8500)).isTrue();

    boolean stepped = true;
    for (int i = 0; i < 40 && stepped; i++) {
      stepped = session.step();
    }

    assertThat(stepped).isFalse();
    assertThat(session.getHalted()).contains("Mirror");
    int tick = session.tick();
    assertThat(session.step()).isFalse();
    assertThat(session.tick()).isEqualTo(tick);
    assertThat(session.messages()).last().asString().contains("battle stopped");
    assertThat(session.cardUnavailableReason(1, 0)).contains("stopped");
    assertThat(session.play(1, 0, 9500, 23500)).isFalse();
  }

  @Test
  @DisplayName("a battle with no match started on it has no hands, and a play is refused")
  void aBattleWithoutAMatchHasNoHands() {
    BattleSession session = BattleSession.of(new Standard1v1Battle(GameData.tables()));

    assertThat(session.step()).isTrue();

    assertThat(session.match()).isNull();
    assertThat(session.handCard(0, 0)).isNull();
    assertThat(session.cardUnavailableReason(0, 0)).contains("no hand");
    assertThat(session.play(0, 0, 9500, 8500)).isFalse();
    assertThat(session.messages()).last().asString().contains("no hand");
  }

  @Test
  @DisplayName(
      "each hit of an area spell in the filter form is collected for the area damage indicators,"
          + " its circle about the spell's point")
  void areaHitsAreCollected(@TempDir Path folder) throws IOException {
    // The Zap's area effect, as its card names it, written to one hit in a circle of 2500 on its
    // first update, with no hit switch, so it takes the filter form whatever the tables ship.
    String area = GameData.card("Zap").areaEffect();
    GameTables tables =
        GameData.altered(
            folder,
            "area_effect_objects",
            rows -> {
              ObjectNode columns = GameData.columns(rows, area);
              columns.remove(List.of("HitsAir", "HitsGround", "MaxRadius", "HitSpeedOffset"));
              columns.put("Radius", 2500);
              columns.put("LifeDuration", 1);
            });
    BattleSession session = open(BattleSession.ladder(tables, ZAPS, ZAPS));
    assertThat(session.getBattle().getWorld().getRecords().areaEffect(area).filterHits())
        .as("%s in the filter form", area)
        .isTrue();

    assertThat(session.play(1, 0, 9500, 20500)).isTrue();
    List<AreaHitLog.AreaHit> hits = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      session.step();
      hits.addAll(session.getAreaHits().drain());
    }

    assertThat(hits)
        .singleElement()
        .satisfies(
            hit -> {
              assertThat(hit.side()).isEqualTo(1);
              assertThat(hit.x()).isEqualTo(9500);
              assertThat(hit.y()).isEqualTo(20500);
              assertThat(hit.radius()).isEqualTo(2500);
            });
    assertThat(session.getAreaHits().drain()).isEmpty();
  }

  @Test
  @DisplayName(
      "a battle given its commands elsewhere is stepped and its plays reported as the session's own")
  void aBattleBuiltElsewhere() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    List<String> knights = Collections.nCopies(8, "Knight");
    int open = battle.startLadderMatch(knights, knights, 0, 0).getStartCooldownTicks();
    battle.play(
        open + 30,
        battle.getWorld().getRecords().card("Knight"),
        BattleSession.LEVEL,
        0,
        3500,
        8500,
        "cmd0");
    // A point off the map: the play's placement refuses it as it runs.
    battle.play(
        open + 40,
        battle.getWorld().getRecords().card("Knight"),
        BattleSession.LEVEL,
        1,
        3500,
        -9000,
        "cmd1");
    BattleSession session = BattleSession.of(battle);

    for (int i = 0; i < open + 60; i++) {
      assertThat(session.step()).isTrue();
    }

    assertThat(battle.getPlays())
        .extracting(Standard1v1Battle.Play::name)
        .containsExactly("cmd0", "cmd1");
    assertThat(BattleAdapter.frame(session).entities()).anyMatch(e -> e.name().equals("Knight"));
    assertThat(session.messages()).last().asString().contains("cmd1 found no legal tile as it ran");
  }

  @Test
  @DisplayName(
      "a variant card is given from the screen as the option its client picks, and runs 20"
          + " ticks later")
  void aVariantCardIsGiven() {
    BattleSession session = only("MergeMaiden");
    int given = session.tick() + 30;
    for (int i = 0; i < 30; i++) {
      assertThat(session.step()).isTrue();
    }

    assertThat(session.play(0, 0, 3500, 8500)).as("%s", session.messages()).isTrue();

    for (int i = 0; i <= Standard1v1Battle.PLAY_DELAY_TICKS; i++) {
      assertThat(session.step()).isTrue();
    }
    assertThat(session.getBattle().getPlays()).hasSize(1);
    assertThat(session.getBattle().getPlays().get(0).variant()).isNotNull();
    assertThat(session.getBattle().getPlays().get(0).tick())
        .isEqualTo(given + Standard1v1Battle.PLAY_DELAY_TICKS);
  }

  /** A Ladder battle in which both decks are eight copies of one card. */
  private static BattleSession only(String card) {
    List<String> deck = Collections.nCopies(8, card);
    return open(BattleSession.ladder(GameData.tables(), deck, deck));
  }

  /** Steps a session to the start cooldown, the first tick counter a card play may be given at. */
  private static BattleSession open(BattleSession session) {
    while (session.tick() < session.match().getStartCooldownTicks()) {
      assertThat(session.step()).isTrue();
    }
    return session;
  }

  /** The hand slot of a side's evolution slot's card when the client offers it now, else -1. */
  private static int offeredEvolutionSlot(BattleSession session, int side, int[] slotFlags) {
    int[] hand = session.match().side(side).getHand().slots();
    for (int slot = 0; slot < 4; slot++) {
      if (hand[slot] != Hand.EMPTY
          && (slotFlags[hand[slot]] & MatchSide.EVOLUTION_SLOT) != 0
          && session.cardUnavailableReason(side, slot) == null) {
        return slot;
      }
    }
    return -1;
  }

  /** The first slot of side 0's hand holding a card. */
  private static int slot(BattleSession session, String card) {
    for (int slot = 0; slot < 4; slot++) {
      if (session.handCard(0, slot) != null && session.handCard(0, slot).name().equals(card)) {
        return slot;
      }
    }
    throw new IllegalStateException(card + " is not in the opening hand");
  }
}
