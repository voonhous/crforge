package org.crforge.desktop.battle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A visualizer session on the battle core: its plays go through the battle's play path, a refused
 * play becomes a message instead of a crash, and its trajectory export runs on the battle core's
 * own objects.
 */
class BattleSessionTest {

  @Test
  @DisplayName("the default decks deal, and every card of them plays without halting the battle")
  void theDefaultDecksPlay() {
    BattleSession session = BattleSession.ladder(Tables.get());
    // Each side plays whatever its first slot holds whenever it can, for two minutes of battle.
    for (int step = 0; step < 2400; step++) {
      for (int side = 0; side < 2; side++) {
        int y = side == 0 ? 8500 : 23500;
        if (session.handCard(side, 0) != null && !session.isPending(side, 0)) {
          session.play(side, 0, step % 2 == 0 ? 3500 : 14500, y);
        }
      }
      assertThat(session.step()).as("step %d: %s", step, session.messages()).isTrue();
    }
    assertThat(session.getHalted()).isNull();
    assertThat(session.getBattle().getPlays())
        .extracting(Standard1v1Battle.Play::name)
        .hasSizeGreaterThan(16);
  }

  /** A card's cost: its row's ManaCost, as the characters' card table writes it. */
  private static int cost(String card) {
    return Tables.get().table("spells_characters").row(card).columns().get("ManaCost").asInt();
  }

  @Test
  @DisplayName("a note from the screen joins the session's messages")
  void aNoteIsKept() {
    BattleSession session = BattleSession.ladder(Tables.get());

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

    assertThat(elixir).isLessThan(cost("Golem"));
    assertThat(session.messages()).last().asString().contains("Golem refused, not enough elixir");
    assertThat(session.isPending(0, 0)).isFalse();
  }

  @Test
  @DisplayName("a message about a side names it as the reader names the sides when it is read")
  void aSidesMessageIsNamedWhenRead() {
    BattleSession session = only("Golem");

    session.play(0, 0, 9500, 8500);
    session.note(1, " taps a test ability");
    session.note("no side named");

    List<String> standing = session.messages();
    assertThat(standing.get(standing.size() - 3)).startsWith("[0] blue: Golem refused");
    assertThat(standing.get(standing.size() - 2)).isEqualTo("[0] red taps a test ability");
    List<String> flipped = session.messages(side -> side == 1 ? "blue" : "red");
    assertThat(flipped.get(flipped.size() - 3)).startsWith("[0] red: Golem refused");
    assertThat(flipped.get(flipped.size() - 2)).isEqualTo("[0] blue taps a test ability");
    assertThat(flipped).last().isEqualTo("[0] no side named");
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
    assertThat(elixir).isBetween(cost("Musketeer"), 2 * cost("Musketeer") - 1);
    assertThat(session.play(0, 0, 3500, 8500)).isTrue();
    assertThat(session.cardUnavailableReason(0, 1)).contains("not enough elixir");
    assertThat(session.play(0, 1, 14500, 8500)).isFalse();
    assertThat(session.messages()).last().asString().contains("not enough elixir");
  }

  @Test
  @DisplayName("a point where the card finds no tile is refused on the spot with a message")
  void noTile() {
    BattleSession session = only("Knight");
    assertThat(session.play(0, 0, -500, 8500)).isFalse();
    assertThat(session.messages()).last().asString().contains("no legal tile");
  }

  @Test
  @DisplayName(
      "a step the battle core refuses to finish halts the session with its message, and no"
          + " further step runs")
  void aRefusedStepHalts() {
    // A Mirror given while another play of its side is pending is refused inside the step.
    List<String> mirrors =
        List.of("Knight", "Archer", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror");
    BattleSession session =
        BattleSession.ladder(Tables.get(), mirrors, Collections.nCopies(8, "Knight"));
    int knight = slot(session, "Knight");
    int mirror = slot(session, "Mirror");
    assertThat(session.play(0, knight, 3500, 8500)).isTrue();
    assertThat(session.cardUnavailableReason(0, mirror)).isNull();
    assertThat(BattleAdapter.frame(session).sides().get(0).hand().get(mirror).unavailableReason())
        .isNull();
    String archer = session.cardUnavailableReason(0, slot(session, "Archer"));
    assertThat(session.play(0, mirror, 14500, 8500)).isTrue();
    // Mirror reserves zero until its item resolves; the remaining Archer stays as it was.
    assertThat(session.cardUnavailableReason(0, slot(session, "Archer"))).isEqualTo(archer);

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
    BattleSession session = BattleSession.of(new Standard1v1Battle(Tables.get()));

    assertThat(session.step()).isTrue();

    assertThat(session.match()).isNull();
    assertThat(session.handCard(0, 0)).isNull();
    assertThat(session.cardUnavailableReason(0, 0)).contains("no hand");
    assertThat(session.play(0, 0, 9500, 8500)).isFalse();
    assertThat(session.messages()).last().asString().contains("no hand");
  }

  @Test
  @DisplayName("every played unit's run is recorded and exported in the reference layout")
  void playedUnitsAreRecorded(@TempDir Path folder) throws IOException {
    BattleSession session = only("Knight");
    assertThat(session.play(0, 0, 3500, 8500)).isTrue();
    for (int i = 0; i < 60; i++) {
      session.step();
    }

    List<Path> written = session.getTrajectories().export(folder);

    assertThat(written).hasSize(1);
    String text = Files.readString(written.get(0));
    assertThat(written.get(0).getFileName().toString()).isEqualTo("b1_0.json");
    assertThat(text).contains("\"card\": \"Knight\"").contains("\"records\": [");
    assertThat(text).contains("{\"tick\": 0,");
  }

  @Test
  @Disabled(
      "the filter form's hit pass, every area effect's on 16.402.18, tells no observer of its"
          + " circle, so the indicators miss it")
  @DisplayName("an area spell's hits are collected for the area damage indicators")
  void areaHitsAreCollected() {
    BattleSession session = only("Zap");
    assertThat(session.play(1, 0, 9500, 20500)).isTrue();
    List<AreaHitLog.AreaHit> hits = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      session.step();
      hits.addAll(session.getAreaHits().drain());
    }
    assertThat(hits).isNotEmpty();
    assertThat(hits).allSatisfy(hit -> assertThat(hit.side()).isEqualTo(1));
    assertThat(hits.get(0).radius()).isPositive();
    assertThat(session.getAreaHits().drain()).isEmpty();
  }

  @Test
  @DisplayName(
      "a battle given its commands elsewhere is stepped and its plays reported as the session's own")
  void aBattleBuiltElsewhere() {
    Standard1v1Battle battle = new Standard1v1Battle(Tables.get());
    List<String> knights = Collections.nCopies(8, "Knight");
    battle.startLadderMatch(knights, knights, 0, 0);
    battle.play(
        30,
        battle.getWorld().getRecords().card("Knight"),
        BattleSession.LEVEL,
        0,
        3500,
        8500,
        "cmd0");
    // A point off the map: the play's placement refuses it as it runs.
    battle.play(
        40,
        battle.getWorld().getRecords().card("Knight"),
        BattleSession.LEVEL,
        1,
        3500,
        -9000,
        "cmd1");
    BattleSession session = BattleSession.of(battle);

    for (int i = 0; i < 60; i++) {
      assertThat(session.step()).isTrue();
    }

    assertThat(battle.getPlays())
        .extracting(Standard1v1Battle.Play::name)
        .containsExactly("cmd0", "cmd1");
    assertThat(BattleAdapter.frame(session).entities()).anyMatch(e -> e.name().equals("Knight"));
    assertThat(session.messages()).last().asString().contains("cmd1 found no legal tile as it ran");
  }

  /** A Ladder battle in which both decks are eight copies of one card. */
  private static BattleSession only(String card) {
    List<String> deck = Collections.nCopies(8, card);
    return BattleSession.ladder(Tables.get(), deck, deck);
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
