package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.deploy.CardPlacement;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.match.MirrorItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Mirror where the reference runs do not take it: a Mirror after a Mirror, a Mirror with
 * nothing to repeat, and the plays it refuses - outside a match, with another play of its side
 * pending, and of a champion.
 */
class BattleMirrorTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /**
   * Two cards and six Mirrors. The shuffle holds the Mirrors back while it draws the first four, so
   * the opening hand is the two cards and then two Mirrors.
   */
  private static final List<String> MIRRORS =
      List.of("Knight", "Archer", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror");

  /** The same with an Archer Queen, a champion, in place of the Knight. */
  private static final List<String> QUEEN_MIRRORS =
      List.of("ArcherQueen", "Archer", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror");

  /** The same with Skeletons, for 1 elixir, in place of the Knight. */
  private static final List<String> SKELETON_MIRRORS =
      List.of("Skeletons", "Archer", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  /** The Knight's row. */
  private static final GameRow KNIGHT = Shipped.unitRow("Knight");

  /** A Knight's hit points at level 11, and at the level a Mirror at 11 plays it at, 12. */
  private static final int KNIGHT_HP_11 =
      Shipped.scaled(Shipped.number(KNIGHT, "Hitpoints"), KNIGHT, LEVEL);

  /** How many levels above its own a Mirror plays the card it repeats: the global's (1). */
  private static final int MIRROR_LEVEL_OFFSET =
      Shipped.number(Shipped.row("globals", "MIRROR_LEVEL_OFFSET"), "NumberValue");

  private static final int KNIGHT_HP_12 =
      Shipped.scaled(Shipped.number(KNIGHT, "Hitpoints"), KNIGHT, LEVEL + MIRROR_LEVEL_OFFSET);

  /** The most elixir there can be, which a Mirror's item cost is held to. */
  private static final int MAX_MANA =
      Shipped.number(Shipped.row("globals", "MAX_MANA"), "NumberValue");

  /** The ticks a Mirror runs after the play before it at the soonest, past its pending window. */
  private static final int AFTER_A_PLAY = 22;

  @Test
  @DisplayName(
      "a Mirror after a Mirror repeats the same card one level up, as the Mirror keeps the last"
          + " card it repeated")
  void aMirrorAfterAMirrorRepeatsTheSameCard() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    LadderMatch match = battle.startLadderMatch(MIRRORS, KNIGHTS, 0, 0);
    // Each Mirror costs the Knight's cost and its own (3 and 1), held to the most elixir there can
    // be, and is played once the elixir covers it.
    int knightCost = Shipped.cost("Knight");
    int mirrorCost = Math.min(knightCost + Shipped.cost("Mirror"), MAX_MANA);
    battle.play(20, GameData.card("Knight"), LEVEL, 0, 3500, 10000, "k");
    run(battle, 20);
    int first = Math.max(20 + AFTER_A_PLAY, coveredFrom(battle, match, mirrorCost));
    battle.playMirror(first, "Mirror", LEVEL, 0, 14500, 10000, "m1");
    run(battle, first);
    int second = Math.max(first + AFTER_A_PLAY, coveredFrom(battle, match, mirrorCost));
    battle.playMirror(second, "Mirror", LEVEL, 0, 3500, 10000, "m2");
    run(battle, second);

    Standard1v1Battle.Play knight = battle.getPlays().get(0);
    assertThat(knight.units().get(0).getHitPoints().getMaximum()).isEqualTo(KNIGHT_HP_11);
    for (Standard1v1Battle.Play play : battle.getPlays().subList(1, 3)) {
      MirrorItem item = play.mirror();
      assertThat(item.repeats().name()).isEqualTo("Knight");
      assertThat(item.level()).isEqualTo(LEVEL + MIRROR_LEVEL_OFFSET);
      assertThat(item.cost()).isEqualTo(mirrorCost);
      assertThat(play.units().get(0).getData().name()).isEqualTo("Knight");
      assertThat(play.units().get(0).getHitPoints().getMaximum()).isEqualTo(KNIGHT_HP_12);
    }
    MatchSide side = match.side(0);
    assertThat(side.lastPlayed().name()).isEqualTo("Knight");
    // The Knight's 3, then 4 for each Mirror.
    assertThat(side.getSpent()).isEqualTo((knightCost + 2 * mirrorCost) * MatchSide.SCALE);
  }

  @Test
  @DisplayName(
      "a Mirror before any card finds no position and is refused with 0x17, nothing taken and"
          + " nothing cycled")
  void aMirrorWithNothingToRepeatIsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    LadderMatch match = battle.startLadderMatch(MIRRORS, KNIGHTS, 0, 0);
    int[] hand = match.side(0).getHand().slots();
    battle.playMirror(20, "Mirror", LEVEL, 0, 3500, 10000, "m");
    run(battle, 20);

    Standard1v1Battle.Play play = battle.getPlays().get(0);
    assertThat(play.matchCode()).isZero();
    assertThat(play.result().code()).isEqualTo(CardPlacement.NO_POSITION);
    assertThat(play.units()).isEmpty();
    // The item is the Mirror's own: its level and its cost.
    assertThat(play.mirror().repeats()).isNull();
    assertThat(play.mirror().levelField()).isEqualTo(LEVEL - 1);
    assertThat(play.mirror().cost()).isEqualTo(Shipped.cost("Mirror"));
    assertThat(match.side(0).getSpent()).isZero();
    assertThat(match.side(0).getHand().slots()).isEqualTo(hand);
    assertThat(match.side(0).lastPlayed()).isNull();
  }

  @Test
  @DisplayName("a Mirror outside a match, which keeps no last card, is refused")
  void aMirrorOutsideAMatchIsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());

    assertThatThrownBy(() -> battle.playMirror(20, "Mirror", LEVEL, 0, 3500, 10000, "m"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("the Mirror outside a match, which keeps no last card to play again");
  }

  @Test
  @DisplayName(
      "a Mirror with another play of its side due in the 21 ticks up to its own is refused, and"
          + " one a tick later is played")
  void aMirrorWithAPlayPendingIsRefused() {
    // Skeletons for 1, so that the Mirror's 2 is covered a tick after the window.
    Standard1v1Battle pending = new Standard1v1Battle(GameData.tables());
    pending.startLadderMatch(SKELETON_MIRRORS, KNIGHTS, 0, 0);
    pending.play(20, GameData.card("Skeletons"), LEVEL, 0, 3500, 10000, "s");
    pending.playMirror(41, "Mirror", LEVEL, 0, 14500, 10000, "m");

    assertThatThrownBy(() -> run(pending, 41))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "m: a Mirror given while another play of its side is pending, which the client may"
                + " repeat in place of the last card, is not modelled");

    Standard1v1Battle later = new Standard1v1Battle(GameData.tables());
    later.startLadderMatch(SKELETON_MIRRORS, KNIGHTS, 0, 0);
    later.play(20, GameData.card("Skeletons"), LEVEL, 0, 3500, 10000, "s");
    later.playMirror(42, "Mirror", LEVEL, 0, 14500, 10000, "m");
    run(later, 42);
    Standard1v1Battle.Play mirror = later.getPlays().get(1);
    assertThat(mirror.matchCode()).isZero();
    assertThat(mirror.mirror().repeats().name()).isEqualTo("Skeletons");
    assertThat(mirror.units()).hasSameSizeAs(later.getPlays().get(0).units());
  }

  @Test
  @DisplayName("a Mirror of a champion is refused once its gates pass")
  void aMirrorOfAChampionIsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    LadderMatch match = battle.startLadderMatch(QUEEN_MIRRORS, KNIGHTS, 0, 0);
    battle.play(20, GameData.card("ArcherQueen"), LEVEL, 0, 3500, 10000, "q");
    run(battle, 20);
    // The Archer Queen's 5 and the Mirror's 1: played once the elixir covers 6.
    int covered =
        coveredFrom(
            battle,
            match,
            Math.min(Shipped.cost("ArcherQueen") + Shipped.cost("Mirror"), MAX_MANA));
    int mirror = Math.max(20 + AFTER_A_PLAY, covered);
    battle.playMirror(mirror, "Mirror", LEVEL, 0, 14500, 10000, "m");

    assertThatThrownBy(() -> run(battle, mirror))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("m: a Mirror of the champion ArcherQueen, which no reference holds");
  }

  /**
   * Steps a match until side 0's whole elixir covers a cost, and answers the next tick to run: a
   * play queued for it finds the elixir, which only grows while the side plays nothing.
   */
  private static int coveredFrom(Standard1v1Battle battle, LadderMatch match, int cost) {
    while (match.side(0).wholeElixir() < cost) {
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
