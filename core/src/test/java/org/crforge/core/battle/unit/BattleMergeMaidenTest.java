package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.match.VariantItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Merge Maiden where the reference runs do not take it: the step its option is picked after, a
 * play the elixir does not cover, and the plays it refuses - outside a match, before tick 21, with
 * another play of its side pending, and repeated by a Mirror.
 */
class BattleMergeMaidenTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The deck of {@code merge_maiden_normal}: the opening hand is a Zap and three Merge Maidens. */
  private static final List<String> ZAP_MAIDENS =
      List.of(
          "Zap", "MergeMaiden", "Zap", "MergeMaiden", "Zap", "MergeMaiden", "Zap", "MergeMaiden");

  private static final List<String> MAIDENS = Collections.nCopies(8, "MergeMaiden");

  /** A Merge Maiden and an Archer, then the Mirrors the shuffle holds back for the refills. */
  private static final List<String> MAIDEN_MIRRORS =
      List.of("MergeMaiden", "Archer", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  /** The maiden on foot's cost (3). */
  private static final int ON_FOOT = Shipped.cost("MergeMaiden_Normal");

  /** The mounted maiden's cost (6). */
  private static final int MOUNTED = Shipped.cost("MergeMaiden_Mounted");

  /** The Zap's cost (2). */
  private static final int ZAP = Shipped.cost("Zap");

  @Test
  @DisplayName(
      "the option is picked from the elixir after the step 21 before the play's run: 5.99 then,"
          + " 6.34 at the run, is the maiden on foot; a tick later, 6.01, the mounted one")
  void theOptionIsPickedAsThePlayIsGiven() {
    // The Zap on 21 leaves 4.39 after its step, and 1x adds 178 a step: 5.99 after step 111,
    // 6.01 after step 112. The first step after which the elixir reaches the mounted option's
    // trigger is found on a battle of the same plays, so the two plays below run 21 ticks after
    // the step before it and after it.
    int reached = firstStepReaching(mountedTrigger());
    int footTick = reached - 1 + 21;
    Standard1v1Battle foot = new Standard1v1Battle(GameData.tables());
    LadderMatch footMatch = foot.startLadderMatch(ZAP_MAIDENS, KNIGHTS, 0, 0);
    foot.play(21, GameData.card("Zap"), LEVEL, 0, 9000, 16000, "z");
    foot.playVariant(footTick, "MergeMaiden", LEVEL, 0, 3500, 10000, "m");
    run(foot, footTick);
    Standard1v1Battle.Play onFoot = foot.getPlays().get(1);
    assertThat(onFoot.variant().spell()).isEqualTo("MergeMaiden_Normal");
    assertThat(onFoot.variant().cost()).isEqualTo(ON_FOOT);
    assertThat(onFoot.units().get(0).getData().name()).isEqualTo("MergeMaiden_Normal");
    assertThat(footMatch.side(0).getSpent()).isEqualTo((ZAP + ON_FOOT) * MatchSide.SCALE);

    Standard1v1Battle mounted = new Standard1v1Battle(GameData.tables());
    LadderMatch mountedMatch = mounted.startLadderMatch(ZAP_MAIDENS, KNIGHTS, 0, 0);
    mounted.play(21, GameData.card("Zap"), LEVEL, 0, 9000, 16000, "z");
    mounted.playVariant(footTick + 1, "MergeMaiden", LEVEL, 0, 3500, 10000, "m");
    run(mounted, footTick + 1);
    Standard1v1Battle.Play flying = mounted.getPlays().get(1);
    assertThat(flying.variant().spell()).isEqualTo("MergeMaiden_Mounted");
    assertThat(flying.variant().option()).isZero();
    assertThat(flying.units().get(0).getData().name()).isEqualTo("MergeMaiden_Mounted");
    assertThat(mountedMatch.side(0).getSpent()).isEqualTo((ZAP + MOUNTED) * MatchSide.SCALE);
  }

  @Test
  @DisplayName(
      "a play picked as the maiden on foot with less than 3 elixir at its run is refused with 0xd,"
          + " nothing taken")
  void aPlayTheElixirDoesNotCoverIsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    LadderMatch match = battle.startLadderMatch(MAIDENS, KNIGHTS, 0, 0);
    // The first Merge Maiden comes mounted for 6 and leaves 0.37; the second is picked on foot
    // after step 21 and finds 0.75 at its run.
    battle.playVariant(21, "MergeMaiden", LEVEL, 0, 3500, 10000, "m1");
    battle.playVariant(42, "MergeMaiden", LEVEL, 0, 14500, 10000, "m2");
    run(battle, 42);

    Standard1v1Battle.Play refused = battle.getPlays().get(1);
    assertThat(refused.matchCode()).isEqualTo(LadderMatch.NOT_ENOUGH_ELIXIR);
    assertThat(refused.units()).isEmpty();
    VariantItem item = refused.variant();
    assertThat(item.spell()).isEqualTo("MergeMaiden_Normal");
    assertThat(item.cost()).isEqualTo(ON_FOOT);
    assertThat(match.side(0).getSpent()).isEqualTo(MOUNTED * MatchSide.SCALE);
  }

  @Test
  @DisplayName("a variant card outside a match, with no king's elixir to pick from, is refused")
  void aVariantOutsideAMatchIsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());

    assertThatThrownBy(() -> battle.playVariant(21, "MergeMaiden", LEVEL, 0, 3500, 10000, "m"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("MergeMaiden outside a match, which picks its option from a king's elixir");
  }

  @Test
  @DisplayName("a variant play run before tick 21, given before the first step, is refused")
  void aVariantBeforeTick21IsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(MAIDENS, KNIGHTS, 0, 0);

    assertThatThrownBy(() -> battle.playVariant(20, "MergeMaiden", LEVEL, 0, 3500, 10000, "m"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "m: a variant play runs on tick 21 or later, its option picked after the step 21"
                + " ticks before");
  }

  @Test
  @DisplayName(
      "a variant play with another play of its side due in the 20 ticks up to its own is refused,"
          + " and one 21 ticks after it is played")
  void aVariantWithAPlayPendingIsRefused() {
    Standard1v1Battle pending = new Standard1v1Battle(GameData.tables());
    pending.startLadderMatch(ZAP_MAIDENS, KNIGHTS, 0, 0);
    pending.play(21, GameData.card("Zap"), LEVEL, 0, 9000, 16000, "z");
    pending.playVariant(41, "MergeMaiden", LEVEL, 0, 3500, 10000, "m");

    assertThatThrownBy(() -> run(pending, 41))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "m: a variant play given while another play of its side is pending, whose cost the"
                + " pick would set aside, is not modelled");

    Standard1v1Battle later = new Standard1v1Battle(GameData.tables());
    later.startLadderMatch(ZAP_MAIDENS, KNIGHTS, 0, 0);
    later.play(21, GameData.card("Zap"), LEVEL, 0, 9000, 16000, "z");
    later.playVariant(42, "MergeMaiden", LEVEL, 0, 3500, 10000, "m");
    run(later, 42);
    Standard1v1Battle.Play maiden = later.getPlays().get(1);
    assertThat(maiden.matchCode()).isZero();
    assertThat(maiden.variant().spell()).isEqualTo("MergeMaiden_Normal");
  }

  @Test
  @DisplayName("a Mirror after a Merge Maiden, which repeats the option played, is refused")
  void aMirrorOfAMergeMaidenIsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(MAIDEN_MIRRORS, KNIGHTS, 0, 0);
    battle.playVariant(21, "MergeMaiden", LEVEL, 0, 3500, 10000, "m");
    battle.playMirror(300, "Mirror", LEVEL, 0, 14500, 10000, "r");

    assertThatThrownBy(() -> run(battle, 300))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "a Mirror of MergeMaiden, which repeats the option it was played as, which no"
                + " reference holds");
  }

  /**
   * The elixir, in ten-thousandths, from which the Merge Maiden's card picks its mounted option:
   * the option's AvailableManaTrigger, in thousandths, times 10 (6000 is 6 elixir).
   */
  private static int mountedTrigger() {
    for (JsonNode option : Shipped.column(Shipped.row("spells_other", "MergeMaiden"), "Options")) {
      if (option.path("SpellData").asText().equals("MergeMaiden_Mounted")) {
        return option.path("AvailableManaTrigger").asInt() * 10;
      }
    }
    throw new AssertionError("no mounted option");
  }

  /**
   * The first step after which side 0's elixir reaches a value, in a match whose Zap is played on
   * 21 and nothing else.
   */
  private static int firstStepReaching(int elixir) {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    LadderMatch match = battle.startLadderMatch(ZAP_MAIDENS, KNIGHTS, 0, 0);
    battle.play(21, GameData.card("Zap"), LEVEL, 0, 9000, 16000, "z");
    run(battle, 21);
    while (match.side(0).getElixir() < elixir) {
      battle.getBattle().step();
    }
    return battle.getBattle().getTick() - 1;
  }

  /** Steps the battle until it has run the given tick, and one step more. */
  private static void run(Standard1v1Battle battle, int lastTick) {
    while (battle.getBattle().getTick() <= lastTick) {
      battle.getBattle().step();
    }
  }
}
