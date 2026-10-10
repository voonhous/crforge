/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

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
 * pending play's cost the pick sets aside, a play the elixir does not cover, and the plays it
 * refuses - outside a match, before tick 21, given in or right after another play's tick, with a
 * Mirror pending or a refused play's promise held, and repeated by a Mirror.
 */
class BattleMergeMaidenTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /**
   * A deck of Zaps and Merge Maidens in turn, whose opening hand is a Zap and three Merge Maidens;
   * a Merge Maiden's play is held by the reference battle card_item_merge_maiden_on_foot.
   */
  private static final List<String> ZAP_MAIDENS =
      List.of(
          "Zap", "MergeMaiden", "Zap", "MergeMaiden", "Zap", "MergeMaiden", "Zap", "MergeMaiden");

  private static final List<String> MAIDENS = Collections.nCopies(8, "MergeMaiden");

  /** A Merge Maiden and an Archer, then the Mirrors the shuffle holds back for the refills. */
  private static final List<String> MAIDEN_MIRRORS =
      List.of("MergeMaiden", "Archer", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror");

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  /** The maiden on foot's cost. */
  private static final int ON_FOOT = Shipped.cost("MergeMaiden_Normal");

  /** The mounted maiden's cost. */
  private static final int MOUNTED = Shipped.cost("MergeMaiden_Mounted");

  /** The Zap's cost. */
  private static final int ZAP = Shipped.cost("Zap");

  @Test
  @DisplayName(
      "the option is picked from the elixir after the step 21 before the play's run: just below"
          + " the mounted option's trigger then, though past it by the run, the maiden on foot; a"
          + " tick later, the mounted one")
  void theOptionIsPickedAsThePlayIsGiven() {
    // The first step after which the elixir reaches the mounted option's trigger is found on a
    // battle of the same plays, so the two plays below run 21 ticks after the step before it and
    // after it.
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
      "a play picked as the maiden on foot with less elixir than its cost at its run is refused"
          + " for want of elixir, nothing taken")
  void aPlayTheElixirDoesNotCoverIsRefused() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    LadderMatch match = battle.startLadderMatch(MAIDENS, KNIGHTS, 0, 0);
    // The first Merge Maiden comes mounted; the second is picked on foot after step 21 and finds
    // less than its cost at its run.
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
      "a variant play given while a play of its side given two ticks or more before is pending"
          + " picks from the elixir less that play's cost: on foot, where the elixir alone would"
          + " pick it mounted")
  void aPendingPlaysCostIsSetAsideByThePick() {
    // The Zap is given on 21 and runs on 41; the Merge Maiden is given on 30, its option picked
    // after step 29, while the Zap is still pending.
    int zapRun = 41;
    int maidenRun = 50;
    // The elixir after step 29 alone reaches the mounted option's trigger; less the Zap's cost it
    // does not.
    int elixir = elixirAfterStep(maidenRun - 21);
    assertThat(elixir).isGreaterThanOrEqualTo(mountedTrigger());
    assertThat(elixir - ZAP * MatchSide.SCALE).isLessThan(mountedTrigger());

    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    LadderMatch match = battle.startLadderMatch(ZAP_MAIDENS, KNIGHTS, 0, 0);
    battle.play(zapRun, GameData.card("Zap"), LEVEL, 0, 9000, 16000, "z");
    battle.playVariant(maidenRun, "MergeMaiden", LEVEL, 0, 3500, 10000, "m");
    run(battle, maidenRun);

    Standard1v1Battle.Play maiden = battle.getPlays().get(1);
    assertThat(maiden.matchCode()).isZero();
    assertThat(maiden.variant().spell()).isEqualTo("MergeMaiden_Normal");
    assertThat(maiden.variant().cost()).isEqualTo(ON_FOOT);
    assertThat(maiden.units().get(0).getData().name()).isEqualTo("MergeMaiden_Normal");
    assertThat(match.side(0).getSpent()).isEqualTo((ZAP + ON_FOOT) * MatchSide.SCALE);
  }

  @Test
  @DisplayName(
      "a variant play given in the tick of another play of its side, or the tick after it, is"
          + " refused; one given after that play has run is picked from the elixir alone")
  void aVariantGivenWithAnotherPlayIsRefused() {
    for (int maidenRun : new int[] {41, 42}) {
      Standard1v1Battle pending = new Standard1v1Battle(GameData.tables());
      pending.startLadderMatch(ZAP_MAIDENS, KNIGHTS, 0, 0);
      pending.play(41, GameData.card("Zap"), LEVEL, 0, 9000, 16000, "z");
      pending.playVariant(maidenRun, "MergeMaiden", LEVEL, 0, 3500, 10000, "m");

      assertThatThrownBy(() -> run(pending, maidenRun))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessage(
              "m: a variant play given in the tick of another play of its side or the tick after,"
                  + " whose cost the player's client may not yet set aside, is not modelled");
    }

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
  @DisplayName(
      "a variant play given while a Mirror of its side is pending, within the 62 ticks after a"
          + " play of its side the match refused at its run, or with an ability command of its"
          + " side given in the 62 ticks up to it, is refused")
  void aVariantWithAnUnmodelledPromiseIsRefused() {
    Standard1v1Battle mirror = new Standard1v1Battle(GameData.tables());
    mirror.startLadderMatch(MAIDEN_MIRRORS, KNIGHTS, 0, 0);
    mirror.play(21, GameData.card("Archer"), LEVEL, 0, 3500, 10000, "a");
    mirror.playMirror(60, "Mirror", LEVEL, 0, 14500, 10000, "r");
    mirror.playVariant(70, "MergeMaiden", LEVEL, 0, 3500, 10000, "m");

    assertThatThrownBy(() -> run(mirror, 70))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "m: a variant play given while a Mirror's play of its side is pending, whose item's"
                + " cost the pick would set aside, is not modelled");

    // The first Merge Maiden comes mounted; the second finds less than its cost at its run and is
    // refused, its promise held by the player's client past its run.
    Standard1v1Battle refused = new Standard1v1Battle(GameData.tables());
    refused.startLadderMatch(MAIDENS, KNIGHTS, 0, 0);
    refused.playVariant(21, "MergeMaiden", LEVEL, 0, 3500, 10000, "m1");
    refused.playVariant(42, "MergeMaiden", LEVEL, 0, 14500, 10000, "m2");
    refused.playVariant(84, "MergeMaiden", LEVEL, 0, 14500, 9000, "m3");

    assertThatThrownBy(() -> run(refused, 84))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "m3: a variant play given while a play of its side refused at its run may still hold"
                + " back its cost, is not modelled");

    Standard1v1Battle ability = new Standard1v1Battle(GameData.tables());
    ability.startLadderMatch(MAIDENS, KNIGHTS, 0, 0);
    ability.playVariant(50, "MergeMaiden", LEVEL, 0, 3500, 10000, "m");
    ability.useAbility(50, 0, "m_0", "u");

    assertThatThrownBy(() -> run(ability, 50))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage(
            "m: a variant play given while an ability command of its side may hold back its"
                + " cost, is not modelled");
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
   * the option's AvailableManaTrigger, in thousandths, times 10.
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

  /** Side 0's elixir after the given step, in a match where nothing is played. */
  private static int elixirAfterStep(int step) {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    LadderMatch match = battle.startLadderMatch(ZAP_MAIDENS, KNIGHTS, 0, 0);
    run(battle, step);
    return match.side(0).getElixir();
  }

  /** Steps the battle until it has run the given tick, and one step more. */
  private static void run(Standard1v1Battle battle, int lastTick) {
    while (battle.getBattle().getTick() <= lastTick) {
      battle.getBattle().step();
    }
  }
}
