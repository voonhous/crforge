/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.deploy.CardPlacement;
import org.crforge.core.battle.deploy.InitialDelay;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.match.MirrorItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Mirror where the reference runs do not take it: a Mirror after a Mirror, a Mirror with
 * nothing to repeat, a Mirror of a card that staggers its units, a Mirror given while another play
 * of its side is pending or after one refused at its run, and the plays it refuses - outside a
 * match, in the tick of another play of its side or the tick after, and about the tick a refused
 * play's promise is dropped - and a Mirror of a champion, by a card or a hero play, whose copy the
 * champion's slot follows.
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

  /** The same with a Mini Pekka, played from the hero slot, in place of the Knight. */
  private static final List<String> PEKKA_MIRRORS =
      List.of("MiniPekka", "Archer", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror");

  /** The same with Skeletons, for 1 elixir, in place of the Knight. */
  private static final List<String> SKELETON_MIRRORS =
      List.of("Skeletons", "Archer", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror");

  /** The same with a Giant, which the elixir a Knight leaves at the start does not cover. */
  private static final List<String> GIANT_MIRRORS =
      List.of("Knight", "Giant", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror");

  /** The same with Goblins, which stagger their units, in place of the Knight. */
  private static final List<String> GOBLIN_MIRRORS =
      List.of("Goblins", "Archer", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror");

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

  /** The ticks a Mirror runs after the play before it in the tests that leave nothing pending. */
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
  @DisplayName(
      "a Mirror of a card that staggers its units deploys them all at once, as the stagger is read"
          + " from the Mirror's own row, which sets none")
  void aMirrorOfAStaggeredCardDeploysEveryUnitAtOnce() {
    // The case this test is about: the repeated card staggers its units, the Mirror does not.
    GameRow goblins = Shipped.row("spells_characters", "Goblins");
    assertThat(Shipped.number(goblins, "SummonDeployDelay")).isPositive();
    assertThat(Shipped.number(goblins, "SummonNumber")).isGreaterThan(1);
    GameRow mirror = Shipped.row("spells_other", "Mirror");
    assertThat(Shipped.column(mirror, "SummonDeployDelay")).isNull();
    assertThat(Shipped.column(mirror, "SummonDeployDelaySecond")).isNull();
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    LadderMatch match = battle.startLadderMatch(GOBLIN_MIRRORS, KNIGHTS, 0, 0);
    battle.play(20, GameData.card("Goblins"), LEVEL, 0, 3500, 10000, "g");
    run(battle, 20);
    // The direct play's units' states on the tick it ran.
    Standard1v1Battle.Play direct = battle.getPlays().get(0);
    List<Integer> directStates =
        direct.units().stream().map(unit -> unit.getView().getState()).toList();
    int mirrorCost = Math.min(Shipped.cost("Goblins") + Shipped.cost("Mirror"), MAX_MANA);
    int tick = Math.max(20 + AFTER_A_PLAY, coveredFrom(battle, match, mirrorCost));
    battle.playMirror(tick, "Mirror", LEVEL, 0, 14500, 10000, "m");
    run(battle, tick);

    // The direct play: the first unit deploys at once, each later one waits its turn.
    assertThat(directStates).containsExactlyElementsOf(staggered(direct.units().size()));
    // The Mirror's play: as many units, every one deploying on the tick it ran.
    Standard1v1Battle.Play mirrored = battle.getPlays().get(1);
    assertThat(mirrored.mirror().repeats().name()).isEqualTo("Goblins");
    assertThat(mirrored.units()).hasSameSizeAs(direct.units());
    assertThat(mirrored.units())
        .extracting(unit -> unit.getView().getState())
        .containsOnly(InitialDelay.DEPLOYING);
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
      "a Mirror given while another play of its side is pending repeats that play, which its"
          + " player's client lists as promised, not the last card the king holds then")
  void aMirrorGivenWhileAPlayIsPendingRepeatsThatPlay() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    LadderMatch match = battle.startLadderMatch(MIRRORS, KNIGHTS, 0, 0);
    int archerCost = Shipped.cost("Archer");
    int mirrorCost = Math.min(archerCost + Shipped.cost("Mirror"), MAX_MANA);
    battle.play(20, GameData.card("Knight"), LEVEL, 0, 3500, 10000, "k");
    run(battle, 20);
    // The Archers run on a tick the elixir covers them and the Mirror after; the Mirror is given
    // ten ticks after them, while they are still pending and the Knight is the last card.
    int archers = Math.max(20 + AFTER_A_PLAY, coveredFrom(battle, match, archerCost + mirrorCost));
    battle.play(archers, GameData.card("Archer"), LEVEL, 0, 3500, 10000, "a");
    battle.playMirror(archers + 10, "Mirror", LEVEL, 0, 14500, 10000, "m");
    run(battle, archers - 1);
    assertThat(match.side(0).lastPlayed().name()).isEqualTo("Knight");
    run(battle, archers + 10);

    Standard1v1Battle.Play mirror = battle.getPlays().get(2);
    assertThat(mirror.matchCode()).isZero();
    assertThat(mirror.mirror().repeats().name()).isEqualTo("Archer");
    assertThat(mirror.mirror().cost()).isEqualTo(mirrorCost);
    assertThat(mirror.units()).hasSameSizeAs(battle.getPlays().get(1).units());
    assertThat(mirror.units()).allMatch(unit -> unit.getData().name().equals("Archer"));
  }

  @Test
  @DisplayName(
      "a Mirror given in the tick of another play of its side or the tick after is refused, and"
          + " one two ticks after repeats that play")
  void aMirrorGivenInThePlaysTickOrTheTickAfterIsRefused() {
    for (int mirror : new int[] {20, 21}) {
      Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
      battle.startLadderMatch(SKELETON_MIRRORS, KNIGHTS, 0, 0);
      battle.play(20, GameData.card("Skeletons"), LEVEL, 0, 3500, 10000, "s");
      battle.playMirror(mirror, "Mirror", LEVEL, 0, 14500, 10000, "m");

      assertThatThrownBy(() -> run(battle, mirror))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessage(
              "m: a Mirror given in the tick of another play of its side or the tick after, which"
                  + " the player's client may not yet repeat, is not modelled");
    }

    Standard1v1Battle later = new Standard1v1Battle(GameData.tables());
    later.startLadderMatch(SKELETON_MIRRORS, KNIGHTS, 0, 0);
    later.play(20, GameData.card("Skeletons"), LEVEL, 0, 3500, 10000, "s");
    later.playMirror(22, "Mirror", LEVEL, 0, 14500, 10000, "m");
    run(later, 22);
    assertThat(later.getPlays().get(1).mirror().repeats().name()).isEqualTo("Skeletons");
  }

  @Test
  @DisplayName(
      "a Mirror given while a play of its side is pending that the match refuses at its run"
          + " repeats that play, which its client still lists, not the last card")
  void aMirrorRepeatsAPendingPlayRefusedAtItsRun() {
    Standard1v1Battle battle = refusedGiant();
    // Given on tick 11, while the Giant given on 1 is pending.
    battle.playMirror(31, "Mirror", LEVEL, 0, 14500, 10000, "m");
    run(battle, 31);

    Standard1v1Battle.Play mirror = battle.getPlays().get(2);
    assertThat(mirror.mirror().repeats().name()).isEqualTo("Giant");
    assertThat(mirror.mirror().cost())
        .isEqualTo(Math.min(Shipped.cost("Giant") + Shipped.cost("Mirror"), MAX_MANA));
    assertThat(battle.getMatch().side(0).lastPlayed().name()).isEqualTo("Knight");
  }

  @Test
  @DisplayName(
      "a Mirror repeats a play of its side the match refused at its run while its client lists"
          + " the play's promise, up to 60 ticks after its giving, is refused about the tick it is"
          + " dropped, and repeats the last card after")
  void aMirrorRepeatsARefusedPlayWhileItsPromiseIsListed() {
    // The Giant was given on tick 1: its promise is dropped about tick 61.
    Standard1v1Battle listed = refusedGiant();
    listed.playMirror(79, "Mirror", LEVEL, 0, 14500, 10000, "m");
    run(listed, 79);
    assertThat(listed.getPlays().get(2).mirror().repeats().name()).isEqualTo("Giant");

    for (int mirror : new int[] {80, 83}) {
      Standard1v1Battle uncertain = refusedGiant();
      uncertain.playMirror(mirror, "Mirror", LEVEL, 0, 14500, 10000, "m");
      assertThatThrownBy(() -> run(uncertain, mirror))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessage(
              "m: a Mirror given about the tick the promise of a play of its side refused at its"
                  + " run is dropped, which the client may still repeat, is not modelled");
    }

    Standard1v1Battle dropped = refusedGiant();
    dropped.playMirror(84, "Mirror", LEVEL, 0, 14500, 10000, "m");
    run(dropped, 84);
    assertThat(dropped.getPlays().get(2).mirror().repeats().name()).isEqualTo("Knight");
  }

  /** A Knight on tick 20 and a Giant on 21, which the elixir left does not cover. */
  private static Standard1v1Battle refusedGiant() {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(GIANT_MIRRORS, KNIGHTS, 0, 0);
    battle.play(20, GameData.card("Knight"), LEVEL, 0, 3500, 10000, "k");
    battle.play(21, GameData.card("Giant"), LEVEL, 0, 3500, 10000, "g");
    run(battle, 21);
    assertThat(battle.getPlays().get(1).matchCode()).isNotZero();
    return battle;
  }

  @Test
  @DisplayName(
      "a Mirror of a champion places a copy one level up while the first lives, for the item's"
          + " cost, and the champion's slot follows the copy: a command naming the first is"
          + " refused, one naming the copy passes")
  void aMirrorOfAChampionIsFollowedBySlot() {
    // The towers stand passive, so the first copy lives on.
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    LadderMatch match = battle.startLadderMatch(QUEEN_MIRRORS, KNIGHTS, 0, 0);
    battle.play(20, GameData.card("ArcherQueen"), LEVEL, 0, 3500, 4000, "q");
    run(battle, 20);
    CharacterEntity queen = battle.getPlays().get(0).units().get(0);
    // Kept where she stands, so she takes no tower.
    queen.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    // The Archer Queen's 5 and the Mirror's 1: played once the elixir covers 6.
    int cost = Math.min(Shipped.cost("ArcherQueen") + Shipped.cost("Mirror"), MAX_MANA);
    int mirror = Math.max(20 + AFTER_A_PLAY, coveredFrom(battle, match, cost));
    battle.playMirror(mirror, "Mirror", LEVEL, 0, 14500, 4000, "m");
    run(battle, mirror);

    Standard1v1Battle.Play play = battle.getPlays().get(1);
    assertThat(play.matchCode()).isZero();
    assertThat(play.mirror().repeats().name()).isEqualTo("ArcherQueen");
    assertThat(play.mirror().level()).isEqualTo(LEVEL + MIRROR_LEVEL_OFFSET);
    assertThat(play.mirror().cost()).isEqualTo(cost);
    CharacterEntity copy = play.units().get(0);
    copy.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    GameRow row = Shipped.unitRow("ArcherQueen");
    assertThat(copy.getData().name()).isEqualTo("ArcherQueen");
    assertThat(copy.getHitPoints().getMaximum())
        .isEqualTo(Shipped.scaled(Shipped.number(row, "Hitpoints"), row, play.mirror().level()));
    assertThat(battle.getWorld().getHolder().entities()).contains(queen, copy);
    assertThat(match.side(0).getSpent())
        .isEqualTo((Shipped.cost("ArcherQueen") + cost) * MatchSide.SCALE);
    // The slot hears the Mirror's play as a play of the card it repeats, and follows the copy.
    ChampionController slot = battle.getWorld().kingTower(0).championSlot(1);
    assertThat(copy.getDeployIndex()).isEqualTo(1);
    assertThat(slot.getDeployIndex()).isEqualTo(copy.getDeployIndex());

    int abilityCost = Shipped.number(queenAbility(), "ManaCost");
    int deployed = battle.getBattle().getTick() + Shipped.number(row, "DeployTime") / 50 + 1;
    while (battle.getBattle().getTick() < deployed || match.side(0).wholeElixir() < abilityCost) {
      battle.getBattle().step();
    }
    int now = battle.getBattle().getTick();
    battle.useAbility(now, 0, "q_0", "old");
    battle.useAbility(now + 1, 0, "m_0", "new");
    run(battle, now + 1);
    List<Standard1v1Battle.AbilityUse> uses = battle.getAbilityUses();
    assertThat(uses.get(uses.size() - 2).outcome().code()).isEqualTo(AbilityCommand.NO_CHAMPION);
    assertThat(uses.get(uses.size() - 1).outcome().code()).isEqualTo(AbilityCommand.OK);
    assertThat(uses.get(uses.size() - 1).outcome().requested()).containsExactly(copy);
  }

  @Test
  @DisplayName(
      "a Mirror after a hero play repeats the hero row one level up, and the champion's slot"
          + " follows the copy")
  void aMirrorOfAHeroPlayIsFollowedBySlot() {
    int[] heroFirst = new int[8];
    heroFirst[0] = MatchSide.HERO_SLOT;
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    LadderMatch match =
        battle.startLadderMatch(PEKKA_MIRRORS, KNIGHTS, 0, 0, heroFirst, new int[8]);
    battle.play(20, GameData.card("MiniPekka"), LEVEL, 0, 3500, 4000, "p");
    run(battle, 20);
    MatchCard heroForm = match.side(0).deck().get(0).formRow(MatchCard.HERO_FORM);
    String heroRow = heroForm.name();
    int cost = Math.min(heroForm.cost() + Shipped.cost("Mirror"), MAX_MANA);
    int mirror = Math.max(20 + AFTER_A_PLAY, coveredFrom(battle, match, cost));
    battle.playMirror(mirror, "Mirror", LEVEL, 0, 14500, 4000, "m");
    run(battle, mirror);

    Standard1v1Battle.Play hero = battle.getPlays().get(0);
    Standard1v1Battle.Play play = battle.getPlays().get(1);
    assertThat(play.matchCode()).isZero();
    assertThat(play.mirror().repeats().name()).isEqualTo(heroRow);
    assertThat(play.mirror().level()).isEqualTo(LEVEL + MIRROR_LEVEL_OFFSET);
    assertThat(play.mirror().cost()).isEqualTo(cost);
    assertThat(play.result().placed()).isTrue();
    // The slot follows the Mirror's play, the count of plays before it.
    ChampionController slot = battle.getWorld().kingTower(0).championSlot(1);
    assertThat(slot.getChampion().name())
        .isEqualTo(GameData.records().cardChampion(heroRow).name());
    assertThat(slot.getDeployIndex()).isEqualTo(1);
    assertThat(hero.matchCode()).isZero();
  }

  /** The ability row the Archer Queen's row names. */
  private static GameRow queenAbility() {
    return Shipped.row(
        "character_abilities", Shipped.text(Shipped.unitRow("ArcherQueen"), "Ability"));
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

  /**
   * The states of a staggered play's units on the tick it ran: the first deploys, the rest wait.
   */
  private static List<Integer> staggered(int units) {
    List<Integer> states = new ArrayList<>();
    states.add(InitialDelay.DEPLOYING);
    states.addAll(Collections.nCopies(units - 1, InitialDelay.WAITING));
    return states;
  }

  /** Steps the battle until it has run the given tick, and one step more. */
  private static void run(Standard1v1Battle battle, int lastTick) {
    while (battle.getBattle().getTick() <= lastTick) {
      battle.getBattle().step();
    }
  }
}
