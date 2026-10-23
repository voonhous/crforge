/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.deploy.CardPlacement;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.deploy.MapCheck;
import org.crforge.core.battle.match.Hand;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.unit.PlayerCommands.Giving;
import org.crforge.core.battle.unit.PlayerCommands.Refusal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A player's commands given as its client gives them: every kind stamped with the tick counter, or
 * 1 at tick 0, and run 20 ticks after the stamp; the client's try at the giving, its elixir less
 * the costs its waiting commands promised; a promise kept 60 ticks after the giving of a play
 * refused at its run; and a refused giving that changes nothing.
 */
class PlayerCommandsTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The start cooldown: the first tick counter a card play may be given at. */
  private static final int OPEN = Shipped.startCooldownTicks();

  private static final List<String> KNIGHTS = Collections.nCopies(8, "Knight");

  /** A Knight and an Archer, then the Mirrors the shuffle holds back for the refills. */
  private static final List<String> KNIGHT_MIRRORS =
      List.of("Knight", "Archer", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror", "Mirror");

  private static final List<String> MAIDENS = Collections.nCopies(8, "MergeMaiden");

  /** One Knight and seven Skeletons. */
  private static final List<String> KNIGHT_SKELETONS =
      List.of(
          "Knight",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons");

  /** An Archer Queen, a champion, and seven Skeletons. */
  private static final List<String> QUEEN_SKELETONS =
      List.of(
          "ArcherQueen",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons",
          "Skeletons");

  /** A point on side 0's half the map takes, away from the bridges. */
  private static final int X = 3500;

  private static final int Y = 8500;

  @Test
  @DisplayName("a deck card's play given at tick t runs at t + 20, not before")
  void aCardPlayRunsTwentyTicksLater() {
    Standard1v1Battle battle = ladder(KNIGHTS, KNIGHTS, 0);
    PlayerCommands commands = new PlayerCommands(battle);
    stepTo(battle, OPEN + 30);

    Giving giving = commands.playCard(0, 0, LEVEL, X, Y, "k");

    assertThat(giving.given()).isTrue();
    assertThat(giving.givenTick()).isEqualTo(OPEN + 30);
    assertThat(giving.runTick()).isEqualTo(OPEN + 30 + Standard1v1Battle.PLAY_DELAY_TICKS);
    stepTo(battle, giving.runTick());
    assertThat(battle.getPlays()).as("nothing has run before its run tick").isEmpty();
    battle.getBattle().step();
    assertThat(battle.getPlays()).hasSize(1);
    assertThat(battle.getPlays().get(0).tick()).isEqualTo(giving.runTick());
    assertThat(battle.getPlays().get(0).units()).hasSize(1);
  }

  @Test
  @DisplayName(
      "a command given before the first step is stamped 1 and would run on tick 21; a card play"
          + " there is refused by the start cooldown")
  void aCommandAtTickZeroIsStampedOne() {
    Standard1v1Battle battle = ladder(KNIGHTS, KNIGHTS, 0);
    PlayerCommands commands = new PlayerCommands(battle);

    Giving play = commands.playCard(0, 0, LEVEL, X, Y, "k");
    Giving ability = commands.useAbility(0, "nobody_0", "a");

    for (Giving giving : List.of(play, ability)) {
      assertThat(giving.givenTick()).isEqualTo(1);
      assertThat(giving.runTick()).isEqualTo(1 + Standard1v1Battle.PLAY_DELAY_TICKS);
    }
    assertThat(play.refusal()).isEqualTo(Refusal.START_COOLDOWN);
    stepTo(battle, play.runTick() + 1);
    assertThat(battle.getPlays()).isEmpty();
  }

  @Test
  @DisplayName(
      "a Mirror given while a Knight's play waits promises the item its client builds from that"
          + " play, and runs 20 ticks after its own stamp")
  void aMirrorRunsTwentyTicksLater() {
    Standard1v1Battle battle = dealt(KNIGHT_MIRRORS, "Knight", "Archer", "Mirror");
    LadderMatch match = battle.getMatch();
    PlayerCommands commands = new PlayerCommands(battle);
    // An Archer's play runs first: the king's last card, which the screen's search reads for a
    // Mirror, is then a card placed on the same tile as the waiting Knight.
    playAndRun(battle, commands, "Archer", "a");
    int knightCost = Shipped.cost("Knight");
    int mirrorItemCost = Shipped.cost("Mirror") + knightCost;
    stepTo(battle, OPEN);
    while (match.side(0).wholeElixir() < knightCost + mirrorItemCost) {
      battle.getBattle().step();
    }
    Giving knight = commands.playCard(0, slot(match, "Knight"), LEVEL, X, Y, "k");
    // Two ticks later, so the client lists the Knight's play as promised.
    stepTo(battle, battle.getBattle().getTick() + 2);

    Giving mirror = commands.playCard(0, slot(match, "Mirror"), LEVEL, 14500, Y, "m");

    assertThat(knight.given()).isTrue();
    assertThat(mirror.given()).isTrue();
    assertThat(mirror.cost()).isEqualTo(mirrorItemCost);
    assertThat(commands.promised(0)).isEqualTo(knightCost + mirrorItemCost);
    assertThat(mirror.runTick())
        .isEqualTo(battle.getBattle().getTick() + Standard1v1Battle.PLAY_DELAY_TICKS);
    stepTo(battle, mirror.runTick() + 1);
    Standard1v1Battle.Play played = battle.getPlays().get(2);
    assertThat(played.name()).isEqualTo("m");
    assertThat(played.tick()).isEqualTo(mirror.runTick());
    assertThat(played.mirror().repeats().name()).isEqualTo("Knight");
    assertThat(played.mirror().cost()).isEqualTo(mirror.cost());
    assertThat(played.units()).hasSize(1);
    assertThat(commands.promised(0)).as("both placed, both promises released").isZero();
  }

  @Test
  @DisplayName(
      "a variant card's play given at tick t runs at t + 20 as the option its client picked at"
          + " the giving, for that option's cost")
  void aVariantPlayRunsTwentyTicksLater() {
    Standard1v1Battle battle = ladder(MAIDENS, KNIGHTS, 0);
    PlayerCommands commands = new PlayerCommands(battle);
    stepTo(battle, OPEN + 40);

    Giving giving = commands.playCard(0, 0, LEVEL, X, Y, "m");

    assertThat(giving.given()).isTrue();
    assertThat(giving.runTick()).isEqualTo(OPEN + 40 + Standard1v1Battle.PLAY_DELAY_TICKS);
    stepTo(battle, giving.runTick() + 1);
    Standard1v1Battle.Play played = battle.getPlays().get(0);
    assertThat(played.tick()).isEqualTo(giving.runTick());
    assertThat(played.variant()).isNotNull();
    assertThat(played.variant().cost()).isEqualTo(giving.cost());
    assertThat(battle.getMatch().side(0).getSpent()).isEqualTo(giving.cost() * MatchSide.SCALE);
  }

  @Test
  @DisplayName(
      "an ability command given at tick t runs at t + 20, and promises the ability's cost until"
          + " its run")
  void anAbilityCommandRunsTwentyTicksLater() {
    Standard1v1Battle battle = dealt(QUEEN_SKELETONS, "ArcherQueen");
    LadderMatch match = battle.getMatch();
    PlayerCommands commands = new PlayerCommands(battle);
    int queenCost = Shipped.cost("ArcherQueen");
    stepTo(battle, OPEN);
    while (match.side(0).wholeElixir() < queenCost) {
      battle.getBattle().step();
    }
    Giving queen = commands.playCard(0, slot(match, "ArcherQueen"), LEVEL, X, 4000, "q");
    assertThat(queen.given()).isTrue();
    int abilityCost = battle.getWorld().getRecords().unit("ArcherQueen").ability().manaCost();
    int deployed =
        queen.runTick() + Shipped.number(Shipped.unitRow("ArcherQueen"), "DeployTime") / 50;
    while (battle.getBattle().getTick() <= deployed || match.side(0).wholeElixir() < abilityCost) {
      battle.getBattle().step();
    }
    int tick = battle.getBattle().getTick();

    Giving ability = commands.useAbility(0, "q_0", "a");

    assertThat(ability.given()).isTrue();
    assertThat(ability.cost()).isEqualTo(abilityCost);
    assertThat(ability.runTick()).isEqualTo(tick + Standard1v1Battle.PLAY_DELAY_TICKS);
    assertThat(commands.promised(0)).isEqualTo(abilityCost);
    stepTo(battle, ability.runTick());
    assertThat(battle.getAbilityUses()).isEmpty();
    battle.getBattle().step();
    assertThat(battle.getAbilityUses()).hasSize(1);
    assertThat(battle.getAbilityUses().get(0).tick()).isEqualTo(ability.runTick());
    assertThat(battle.getAbilityUses().get(0).outcome().code()).isEqualTo(AbilityCommand.OK);
    assertThat(commands.promised(0)).as("released by its run").isZero();
  }

  @Test
  @DisplayName(
      "the elixir check sets aside the costs the waiting plays promised: a Knight the whole elixir"
          + " covers is refused while they wait, and given once they have run")
  void waitingPlaysPromisesAreSetAside() {
    Standard1v1Battle battle = ladder(KNIGHTS, KNIGHTS, 0);
    MatchSide side = battle.getMatch().side(0);
    PlayerCommands commands = new PlayerCommands(battle);
    int cost = Shipped.cost("Knight");
    stepTo(battle, OPEN + 10);
    // Knights from slot after slot until one is refused; the most elixir there can be is below
    // four Knights, so one of the four is.
    List<Giving> given = new ArrayList<>();
    Giving refused = null;
    for (int slot = 0; slot < Hand.SLOTS && refused == null; slot++) {
      Giving giving = commands.playCard(0, slot, LEVEL, 2000 + 3000 * slot, Y, "k" + slot);
      if (giving.given()) {
        given.add(giving);
      } else {
        refused = giving;
      }
    }

    assertThat(given).isNotEmpty();
    assertThat(refused).isNotNull();
    assertThat(refused.refusal()).isEqualTo(Refusal.NOT_ENOUGH_ELIXIR);
    assertThat(refused.code()).isEqualTo(LadderMatch.NOT_ENOUGH_ELIXIR);
    assertThat(refused.cost()).isEqualTo(cost);
    assertThat(commands.promised(0)).isEqualTo(given.size() * cost);
    assertThat(side.wholeElixir())
        .as("the whole elixir covers it; less the promises it does not")
        .isGreaterThanOrEqualTo(cost)
        .isLessThan(commands.promised(0) + cost);
    // The plays pay at their run and release their promises.
    stepTo(battle, given.get(given.size() - 1).runTick() + 1);
    while (side.wholeElixir() < cost) {
      battle.getBattle().step();
    }
    assertThat(commands.promised(0)).isZero();
    assertThat(commands.playCard(0, 3, LEVEL, 14500, Y, "after").given()).isTrue();
  }

  @Test
  @DisplayName(
      "two plays of one side wait at once when the elixir covers both, each run on its own tick")
  void twoCommandsWaitAtOnce() {
    Standard1v1Battle battle = ladder(KNIGHTS, KNIGHTS, 0);
    MatchSide side = battle.getMatch().side(0);
    PlayerCommands commands = new PlayerCommands(battle);
    int cost = Shipped.cost("Knight");
    stepTo(battle, OPEN + 10);
    while (side.wholeElixir() < 2 * cost) {
      battle.getBattle().step();
    }

    Giving first = commands.playCard(0, 0, LEVEL, X, Y, "k1");
    battle.getBattle().step();
    Giving second = commands.playCard(0, 1, LEVEL, 14500, Y, "k2");

    assertThat(first.given()).isTrue();
    assertThat(second.given()).isTrue();
    assertThat(second.runTick()).isEqualTo(first.runTick() + 1);
    assertThat(commands.promised(0)).isEqualTo(2 * cost);
    int spentBefore = side.getSpent();
    stepTo(battle, second.runTick() + 1);
    assertThat(battle.getPlays())
        .extracting(Standard1v1Battle.Play::name, Standard1v1Battle.Play::tick)
        .containsExactly(tuple("k1", first.runTick()), tuple("k2", second.runTick()));
    assertThat(side.getSpent() - spentBefore).isEqualTo(2 * cost * MatchSide.SCALE);
    assertThat(commands.promised(0)).isZero();
  }

  @Test
  @DisplayName(
      "a play withdrawn before its run is as never given: its promise released, its card offered"
          + " again, nothing run, the battle as one where it was not given")
  void aWithdrawnPlayIsAsNeverGiven() {
    Standard1v1Battle battle = ladder(KNIGHTS, KNIGHTS, 0);
    Standard1v1Battle untouched = ladder(KNIGHTS, KNIGHTS, 0);
    PlayerCommands commands = new PlayerCommands(battle);
    int cost = Shipped.cost("Knight");
    stepTo(battle, OPEN + 10);
    stepTo(untouched, OPEN + 10);
    while (battle.getMatch().side(0).wholeElixir() < cost) {
      battle.getBattle().step();
      untouched.getBattle().step();
    }
    int deckIndex = battle.getMatch().side(0).getHand().slots()[0];
    Giving giving = commands.playCard(0, 0, LEVEL, X, Y, "k");
    assertThat(giving.given()).isTrue();
    assertThat(commands.promised(0)).isEqualTo(cost);
    assertThat(commands.isPending(0, deckIndex)).isTrue();

    commands.withdraw("k");

    assertThat(commands.promised(0)).isZero();
    assertThat(commands.isPending(0, deckIndex)).isFalse();
    assertThat(commands.tryCard(0, 0, LEVEL).given()).as("the card is offered again").isTrue();
    assertThat(battle.getCommands()).isEmpty();
    // Past the tick it would have run on, nothing ran and nothing is held back for it.
    stepTo(battle, giving.runTick() + 5);
    stepTo(untouched, giving.runTick() + 5);
    assertThat(battle.getPlays()).isEmpty();
    assertThat(commands.promised(0)).isZero();
    assertThat(commands.isPending(0, deckIndex)).isFalse();
    assertThat(state(battle)).isEqualTo(state(untouched));
    assertThat(commands.playCard(0, 0, LEVEL, X, Y, "again").given()).isTrue();
    // A command that is not waiting is not withdrawn, and changes nothing.
    stepTo(battle, battle.getBattle().getTick() + Standard1v1Battle.PLAY_DELAY_TICKS + 1);
    assertThat(battle.getPlays()).hasSize(1);
    assertThatThrownBy(() -> commands.withdraw("again"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> commands.withdraw("nobody"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("an ability command withdrawn before its run releases its promise and does not run")
  void aWithdrawnAbilityCommandReleasesItsPromise() {
    Standard1v1Battle battle = dealt(QUEEN_SKELETONS, "ArcherQueen");
    LadderMatch match = battle.getMatch();
    PlayerCommands commands = new PlayerCommands(battle);
    int queenCost = Shipped.cost("ArcherQueen");
    stepTo(battle, OPEN);
    while (match.side(0).wholeElixir() < queenCost) {
      battle.getBattle().step();
    }
    Giving queen = commands.playCard(0, slot(match, "ArcherQueen"), LEVEL, X, 4000, "q");
    assertThat(queen.given()).isTrue();
    int abilityCost = battle.getWorld().getRecords().unit("ArcherQueen").ability().manaCost();
    int deployed =
        queen.runTick() + Shipped.number(Shipped.unitRow("ArcherQueen"), "DeployTime") / 50;
    while (battle.getBattle().getTick() <= deployed || match.side(0).wholeElixir() < abilityCost) {
      battle.getBattle().step();
    }
    Giving giving = commands.useAbility(0, "q_0", "a");
    assertThat(giving.given()).isTrue();
    assertThat(commands.promised(0)).isEqualTo(abilityCost);

    commands.withdraw("a");

    assertThat(commands.promised(0)).isZero();
    stepTo(battle, giving.runTick() + 5);
    assertThat(battle.getAbilityUses()).isEmpty();
    assertThat(commands.promised(0)).isZero();
  }

  @Test
  @DisplayName(
      "cards a side has never played are dealt again in place, its hand untouched; a card it has"
          + " played is not")
  void onlyUnplayedCardsAreReplaced() {
    Standard1v1Battle battle = dealt(KNIGHT_SKELETONS, "Knight");
    PlayerCommands commands = new PlayerCommands(battle);
    MatchSide side = battle.getMatch().side(0);
    int knight = side.getHand().slots()[slot(battle.getMatch(), "Knight")];
    playAndRun(battle, commands, "Knight", "k");
    int unplayed = knight == 0 ? 1 : 0;
    int[] hand = side.getHand().slots();
    List<Integer> queue = side.getHand().queue();

    battle.replaceUnplayedCards(0, new int[] {unplayed}, List.of("Archer"));

    assertThat(side.deck().get(unplayed).name()).isEqualTo("Archer");
    assertThat(side.deck().get(knight).name()).isEqualTo("Knight");
    assertThat(side.getHand().slots()).isEqualTo(hand);
    assertThat(side.getHand().queue()).isEqualTo(queue);
    assertThatThrownBy(() -> battle.replaceUnplayedCards(0, new int[] {knight}, List.of("Archer")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> battle.replaceUnplayedCards(0, new int[] {unplayed, unplayed}, List.of("A", "B")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(side.deck().get(unplayed).name()).isEqualTo("Archer");
  }

  @Test
  @DisplayName(
      "a play refused at its run keeps its promise until 60 ticks after the tick counter it was"
          + " given at, and the elixir check sets it aside until then")
  void aRefusedPlaysPromiseExpires() {
    Standard1v1Battle battle = dealt(KNIGHT_SKELETONS, "Knight");
    LadderMatch match = battle.getMatch();
    MatchSide side = match.side(0);
    PlayerCommands commands = new PlayerCommands(battle);
    int knight = Shipped.cost("Knight");
    int skeletons = Shipped.cost("Skeletons");
    stepTo(battle, OPEN);
    int given = battle.getBattle().getTick();
    Giving refusedAtRun = commands.playCard(0, slot(match, "Knight"), LEVEL, X, Y, "k");
    // A play of the deck's one Knight queued with its run tick, outside the client, runs first
    // and sends it to the back of the queue: the given play finds it in no hand slot at its run.
    // Skeletons queued with it leave less than the Knight's and the Skeletons' costs.
    battle.play(
        given, battle.getWorld().getRecords().card("Knight"), LEVEL, 0, 14500, Y, "outside");
    battle.play(
        given, battle.getWorld().getRecords().card("Skeletons"), LEVEL, 0, 9000, Y, "spent");
    stepTo(battle, refusedAtRun.runTick() + 1);
    Standard1v1Battle.Play run = battle.getPlays().get(2);
    assertThat(run.name()).isEqualTo("k");
    assertThat(run.matchCode()).as("refused at its run").isEqualTo(LadderMatch.NOT_IN_HAND);
    int expiry = given + Standard1v1Battle.PROMISE_TICKS;

    // While it is kept, Skeletons the whole elixir covers are refused: the Knight's cost is set
    // aside.
    while (side.wholeElixir() < skeletons) {
      battle.getBattle().step();
    }
    assertThat(battle.getBattle().getTick()).isLessThanOrEqualTo(expiry - 2);
    assertThat(side.wholeElixir()).isLessThan(knight + skeletons);
    assertThat(commands.promised(0)).isEqualTo(knight);
    Giving whileKept = commands.tryCard(0, slot(match, "Skeletons"), LEVEL);
    assertThat(whileKept.refusal()).isEqualTo(Refusal.NOT_ENOUGH_ELIXIR);
    // Kept until two ticks before its expiry, whose step is not traced to the tick.
    stepTo(battle, expiry - 2);
    assertThat(commands.promised(0)).isEqualTo(knight);
    // Dropped from three ticks after it.
    stepTo(battle, expiry + 3);
    assertThat(commands.promised(0)).isZero();
    assertThat(side.wholeElixir()).isGreaterThanOrEqualTo(skeletons);
    assertThat(commands.tryCard(0, slot(match, "Skeletons"), LEVEL).given()).isTrue();
  }

  @Test
  @DisplayName(
      "a refused giving changes nothing: the battle runs on as one where nothing was given, and"
          + " no cost is promised")
  void aRefusedGivingChangesNothing() {
    Standard1v1Battle refused = ladder(KNIGHTS, KNIGHTS, 0);
    Standard1v1Battle untouched = ladder(KNIGHTS, KNIGHTS, 0);
    PlayerCommands commands = new PlayerCommands(refused);
    PlayerCommands others = new PlayerCommands(untouched);
    stepTo(refused, OPEN + 10);
    stepTo(untouched, OPEN + 10);
    int cost = Shipped.cost("Knight");
    MatchSide side = refused.getMatch().side(0);
    assertThat(side.wholeElixir()).isGreaterThanOrEqualTo(cost);
    // Side 0 gives the Knights its elixir covers in both battles, side 1 one; in one battle side 0
    // also gives two the client refuses: one its elixir does not cover, and its first hand slot
    // again.
    int slot = 0;
    for (; slot < 3 && side.wholeElixir() >= commands.promised(0) + cost; slot++) {
      for (PlayerCommands each : List.of(commands, others)) {
        assertThat(each.playCard(0, slot, LEVEL, 2000 + 3000 * slot, Y, "k" + slot).given())
            .isTrue();
      }
    }
    for (PlayerCommands each : List.of(commands, others)) {
      assertThat(each.playCard(1, 0, LEVEL, X, 23500, "r").given()).isTrue();
    }
    Giving shortOfElixir = commands.playCard(0, 3, LEVEL, 14500, Y, "short");
    Giving again = commands.playCard(0, 0, LEVEL, X, Y, "again");

    assertThat(shortOfElixir.refusal()).isEqualTo(Refusal.NOT_ENOUGH_ELIXIR);
    assertThat(again.refusal()).isEqualTo(Refusal.CARD_PENDING);
    assertThat(again.code()).isEqualTo(PlayerCommands.SCREEN);
    assertThat(commands.promised(0)).isEqualTo(others.promised(0)).isEqualTo(slot * cost);
    for (int tick = 0; tick < 120; tick++) {
      refused.getBattle().step();
      untouched.getBattle().step();
      assertThat(state(refused)).as("tick %d", tick).isEqualTo(state(untouched));
    }
    assertThat(refused.getPlays())
        .extracting(Standard1v1Battle.Play::name)
        .doesNotContain("short", "again")
        .hasSize(slot + 1);
  }

  @Test
  @DisplayName(
      "a troop dropped in the enemy half is given on the tile the placement search finds now, the"
          + " tile its run places it on")
  void aTroopInTheEnemyHalfIsGivenOnItsOwnTile() {
    Standard1v1Battle battle = ladder(KNIGHTS, KNIGHTS, 0);
    PlayerCommands commands = new PlayerCommands(battle);
    stepTo(battle, OPEN + 30);
    DeployCard knight = battle.getWorld().getRecords().card("Knight");
    // The mirror of side 0's point in side 1's half, between two tiles.
    int x = X + 123;
    int y = lengthUnits(battle) - Y + 77;
    CardPlacement.Result now = battle.previewPlacement(knight, 0, x, y);
    assertThat(now.placed()).isTrue();

    Giving giving = commands.playCard(0, 0, LEVEL, x, y, "k");

    assertThat(giving.given()).isTrue();
    assertThat(new int[] {giving.x(), giving.y()})
        .as("the tile the search finds now")
        .containsExactly(now.x(), now.y());
    assertThat(giving.y()).as("on side 0's half").isLessThan(lengthUnits(battle) / 2);
    stepTo(battle, giving.runTick() + 1);
    Standard1v1Battle.Play played = battle.getPlays().get(0);
    assertThat(new int[] {played.x(), played.y()})
        .as("the command carries the tile")
        .containsExactly(giving.x(), giving.y());
    assertThat(new int[] {played.result().x(), played.result().y()})
        .as("placed on the tile sent")
        .containsExactly(giving.x(), giving.y());
  }

  @Test
  @DisplayName(
      "a troop dropped on its king tower, a cell the map check refuses, or on a princess tower is"
          + " moved to the nearest legal tile and given, as is one dropped off the map")
  void aDropTheMapRefusesIsMoved() {
    Standard1v1Battle battle = ladder(KNIGHTS, KNIGHTS, 0);
    PlayerCommands commands = new PlayerCommands(battle);
    DeployCard knight = battle.getWorld().getRecords().card("Knight");
    TowerEntity king = tower(battle, 0, true);
    TowerEntity princess = tower(battle, 0, false);
    int kx = king.getView().getX();
    int ky = king.getView().getY();
    int px = princess.getView().getX();
    int py = princess.getView().getY();
    assertThat(MapCheck.check(battle.getWorld().getTileMap(), knight, kx, ky))
        .as("the map check refuses the king tower's centre")
        .isNotEqualTo(MapCheck.OK);
    int[][] drops = {{kx, ky}, {px, py}, {-500, Y}};
    stepTo(battle, OPEN);

    for (int[] drop : drops) {
      while (battle.getMatch().side(0).wholeElixir() < Shipped.cost("Knight")) {
        battle.getBattle().step();
      }
      int[] found = battle.searchPlacement(knight, 0, drop[0], drop[1]);
      Giving giving = commands.playCard(0, 0, LEVEL, drop[0], drop[1], "k" + drop[0]);
      assertThat(giving.given()).as("dropped at %d, %d", drop[0], drop[1]).isTrue();
      assertThat(new int[] {giving.x(), giving.y()}).containsExactly(found);
      assertThat(new int[] {giving.x(), giving.y()}).isNotEqualTo(drop);
      stepTo(battle, giving.runTick() + 1);
    }

    Standard1v1Battle.Play onPrincess = battle.getPlays().get(1);
    assertThat(Math.max(Math.abs(onPrincess.x() - px), Math.abs(onPrincess.y() - py)))
        .as("off the princess tower's footprint")
        .isGreaterThan(princess.getData().collisionRadius());
    assertThat(battle.getPlays().get(2).x()).as("on the map").isNotNegative();
    assertThat(battle.getPlays())
        .extracting(play -> play.result().placed())
        .containsExactly(true, true, true);
  }

  @Test
  @DisplayName("a spell dropped between two tiles is given on its tile's centre")
  void aSpellIsGivenOnItsTileCentre() {
    Standard1v1Battle battle = ladder(Collections.nCopies(8, "Fireball"), KNIGHTS, 0);
    PlayerCommands commands = new PlayerCommands(battle);
    stepTo(battle, OPEN + 60);
    int x = X + 321;
    int y = lengthUnits(battle) - Y + 77;

    Giving giving = commands.playCard(0, 0, LEVEL, x, y, "f");

    assertThat(giving.given()).isTrue();
    assertThat(new int[] {giving.x(), giving.y()})
        .containsExactly(x / 1000 * 1000 + 500, y / 1000 * 1000 + 500);
  }

  @Test
  @DisplayName(
      "a Mirror dropped while its king has no last card finds no tile and is refused on the"
          + " screen: nothing is sent, nothing promised, and the battle runs on unchanged")
  void aMirrorWithNothingToRepeatFindsNoTile() {
    Standard1v1Battle battle = dealt(KNIGHT_MIRRORS, "Mirror");
    Standard1v1Battle untouched = dealt(KNIGHT_MIRRORS, "Mirror");
    LadderMatch match = battle.getMatch();
    PlayerCommands commands = new PlayerCommands(battle);
    while (battle.getBattle().getTick() < OPEN
        || match.side(0).wholeElixir() < Shipped.cost("Mirror")) {
      battle.getBattle().step();
      untouched.getBattle().step();
    }

    Giving tried = commands.tryCard(0, slot(match, "Mirror"), LEVEL);
    Giving giving = commands.playCard(0, slot(match, "Mirror"), LEVEL, X, Y, "m");

    assertThat(tried.refusal()).isEqualTo(Refusal.NO_TILE);
    assertThat(giving.refusal()).isEqualTo(Refusal.NO_TILE);
    assertThat(giving.code()).isEqualTo(PlayerCommands.SCREEN);
    assertThat(commands.promised(0)).isZero();
    for (int tick = 0; tick < 40; tick++) {
      battle.getBattle().step();
      untouched.getBattle().step();
      assertThat(state(battle)).as("tick %d", tick).isEqualTo(state(untouched));
    }
    assertThat(battle.getPlays()).isEmpty();
  }

  @Test
  @DisplayName(
      "a Mirror dropped while its king has no last card but a play of its side waits is refused"
          + " as not modelled: which card the screen's search is handed then is not traced")
  void aMirrorWhoseSearchCardIsUntracedIsRefused() {
    Standard1v1Battle battle = dealt(KNIGHT_MIRRORS, "Knight", "Mirror");
    LadderMatch match = battle.getMatch();
    PlayerCommands commands = new PlayerCommands(battle);
    int both = 2 * Shipped.cost("Knight") + Shipped.cost("Mirror");
    stepTo(battle, OPEN);
    while (match.side(0).wholeElixir() < both) {
      battle.getBattle().step();
    }
    assertThat(commands.playCard(0, slot(match, "Knight"), LEVEL, X, Y, "k").given()).isTrue();
    stepTo(battle, battle.getBattle().getTick() + 2);
    int mirror = slot(match, "Mirror");

    assertThatThrownBy(() -> commands.playCard(0, mirror, LEVEL, X, Y, "m"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("not modelled");
  }

  @Test
  @DisplayName(
      "the board changing in the 20 ticks moves the play again at its run, from the tile it was"
          + " sent on")
  void aBoardChangeMovesThePlayAgainAtItsRun() {
    List<String> deck = new ArrayList<>(Collections.nCopies(7, "Knight"));
    deck.add(0, "Cannon");
    Standard1v1Battle battle = dealt(deck, "Cannon", "Knight");
    // A point on side 0's half away from its towers and the river.
    int x = 6500;
    int y = 10500;
    LadderMatch match = battle.getMatch();
    PlayerCommands commands = new PlayerCommands(battle);
    stepTo(battle, OPEN + 10);
    while (match.side(0).wholeElixir() < Shipped.cost("Cannon") + Shipped.cost("Knight")) {
      battle.getBattle().step();
    }
    Giving cannon = commands.playCard(0, slot(match, "Cannon"), LEVEL, x, y, "c");
    battle.getBattle().step();
    // The Cannon has not run: the Knight's tile is the one under the Cannon's point.
    Giving knight = commands.playCard(0, slot(match, "Knight"), LEVEL, x, y, "k");
    assertThat(cannon.given()).isTrue();
    assertThat(knight.given()).isTrue();
    assertThat(knight.runTick()).isEqualTo(cannon.runTick() + 1);
    assertThat(new int[] {knight.x(), knight.y()})
        .containsExactly(battle.searchPlacement(GameData.card("Knight"), 0, x, y));

    stepTo(battle, knight.runTick() + 1);

    Standard1v1Battle.Play played = battle.getPlays().get(1);
    assertThat(played.name()).isEqualTo("k");
    assertThat(new int[] {played.x(), played.y()}).containsExactly(knight.x(), knight.y());
    assertThat(played.result().placed()).isTrue();
    assertThat(new int[] {played.result().x(), played.result().y()})
        .as("moved off the Cannon that landed on its tile")
        .isNotEqualTo(new int[] {knight.x(), knight.y()});
  }

  @Test
  @DisplayName("an ability command naming no unit of its side is refused, and nothing is queued")
  void anAbilityNamingNoUnitIsRefused() {
    Standard1v1Battle battle = ladder(QUEEN_SKELETONS, KNIGHTS, 0);
    PlayerCommands commands = new PlayerCommands(battle);

    Giving giving = commands.useAbility(0, "nobody_0", "a");

    assertThat(giving.refusal()).isEqualTo(Refusal.NO_CHAMPION);
    assertThat(giving.code()).isEqualTo(AbilityCommand.NO_CHAMPION);
    assertThat(commands.promised(0)).isZero();
    stepTo(battle, giving.runTick() + 1);
    assertThat(battle.getAbilityUses()).isEmpty();
  }

  @Test
  @DisplayName(
      "an ability's try comes to what its giving would on every tick of a champion's life, the"
          + " king's death included, and changes nothing: a battle whose side tries before each"
          + " giving runs as one that only gives")
  void anAbilitysTryAgreesWithItsGiving() {
    Standard1v1Battle tried = dealt(QUEEN_SKELETONS, "ArcherQueen");
    Standard1v1Battle given = dealt(QUEEN_SKELETONS, "ArcherQueen");
    PlayerCommands triedCommands = new PlayerCommands(tried);
    PlayerCommands givenCommands = new PlayerCommands(given);
    int queenCost = Shipped.cost("ArcherQueen");
    boolean queenGiven = false;
    Set<Refusal> seen = EnumSet.noneOf(Refusal.class);
    for (int tick = 0; tick < OPEN + 600; tick++) {
      if (!queenGiven
          && tick >= OPEN
          && tried.getMatch().side(0).wholeElixir() - triedCommands.promised(0) >= queenCost) {
        int slot = slot(tried.getMatch(), "ArcherQueen");
        assertThat(triedCommands.playCard(0, slot, LEVEL, X, 4000, "q").given()).isTrue();
        assertThat(givenCommands.playCard(0, slot, LEVEL, X, 4000, "q").given()).isTrue();
        queenGiven = true;
      }
      Giving attempt = triedCommands.tryAbility(0, "q_0");
      Giving giving = triedCommands.useAbility(0, "q_0", "a" + tick);
      Giving plain = givenCommands.useAbility(0, "q_0", "a" + tick);

      assertThat(attempt).as("tick %d", tick).isEqualTo(giving).isEqualTo(plain);
      seen.add(giving.refusal());
      tried.getBattle().step();
      given.getBattle().step();
      assertThat(state(tried)).as("tick %d", tick).isEqualTo(state(given));
      assertThat(triedCommands.promised(0)).isEqualTo(givenCommands.promised(0));
      assertThat(tried.getAbilityUses()).hasSameSizeAs(given.getAbilityUses());
    }
    assertThat(seen)
        .as("the champion's life reaches these gates")
        .contains(Refusal.NO_CHAMPION, Refusal.NONE, Refusal.NOT_ENOUGH_ELIXIR);
    assertThat(seen)
        .as("and one gate of a used ability")
        .containsAnyOf(Refusal.ABILITY_PENDING, Refusal.COOLDOWN, Refusal.NO_CHARGES);

    for (Standard1v1Battle battle : List.of(tried, given)) {
      battle.getWorld().kingTower(0).getHitPoints().setHitPoints(0);
    }
    Giving attempt = triedCommands.tryAbility(0, "q_0");
    assertThat(attempt.refusal()).isEqualTo(Refusal.KING_DEAD);
    assertThat(attempt).isEqualTo(triedCommands.useAbility(0, "q_0", "dead"));
  }

  @Test
  @DisplayName("an ability's try naming no unit is refused as no champion, and queues nothing")
  void anAbilitysTryNamingNoUnitIsRefused() {
    Standard1v1Battle battle = ladder(QUEEN_SKELETONS, KNIGHTS, 0);
    PlayerCommands commands = new PlayerCommands(battle);

    Giving attempt = commands.tryAbility(0, "nobody_0");

    assertThat(attempt.refusal()).isEqualTo(Refusal.NO_CHAMPION);
    assertThat(attempt.code()).isEqualTo(AbilityCommand.NO_CHAMPION);
    assertThat(attempt).isEqualTo(commands.useAbility(0, "nobody_0", "a"));
    stepTo(battle, attempt.runTick() + 1);
    assertThat(battle.getAbilityUses()).isEmpty();
  }

  @Test
  @DisplayName(
      "a champion slot names no unit until its champion's play has run, then the champion that"
          + " play made, which an ability command naming it passes")
  void aChampionSlotNamesTheUnitItsPlayMade() {
    Standard1v1Battle battle = dealt(QUEEN_SKELETONS, "ArcherQueen");
    LadderMatch match = battle.getMatch();
    PlayerCommands commands = new PlayerCommands(battle);
    stepTo(battle, OPEN);
    while (match.side(0).wholeElixir() < Shipped.cost("ArcherQueen")) {
      battle.getBattle().step();
    }
    assertThat(commands.championUnit(0, 0)).as("before any play").isNull();
    Giving queen = commands.playCard(0, slot(match, "ArcherQueen"), LEVEL, X, 4000, "q");
    stepTo(battle, queen.runTick());
    assertThat(commands.championUnit(0, 0)).as("before the play runs").isNull();
    battle.getBattle().step();

    CharacterEntity unit = commands.championUnit(0, 0);

    assertThat(unit).isNotNull();
    assertThat(unit.name()).isEqualTo("q_0");
    assertThat(unit.getData().ability().champion()).isTrue();
    assertThat(commands.championUnit(0, 1)).as("the second slot follows nothing").isNull();
    assertThat(commands.championUnit(1, 0)).as("side 1 played no champion").isNull();
    int abilityCost = unit.getData().ability().manaCost();
    int deployed =
        queen.runTick() + Shipped.number(Shipped.unitRow("ArcherQueen"), "DeployTime") / 50;
    while (battle.getBattle().getTick() <= deployed || match.side(0).wholeElixir() < abilityCost) {
      battle.getBattle().step();
    }
    assertThat(commands.useAbility(0, unit.name(), "a").given()).isTrue();
  }

  /** A Ladder battle, side 0 shuffled with a word. */
  private static Standard1v1Battle ladder(List<String> deck0, List<String> deck1, int word) {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables());
    battle.startLadderMatch(deck0, deck1, word, 0);
    return battle;
  }

  /** A Ladder battle whose side 0 is dealt every card named into its opening hand. */
  private static Standard1v1Battle dealt(List<String> deck, String... cards) {
    for (int word = 0; ; word++) {
      Standard1v1Battle battle = ladder(deck, KNIGHTS, word);
      boolean all = true;
      for (String card : cards) {
        all &= slotOrNone(battle.getMatch(), card) >= 0;
      }
      if (all) {
        return battle;
      }
    }
  }

  /** Side 0's first hand slot holding a card. */
  private static int slot(LadderMatch match, String card) {
    int slot = slotOrNone(match, card);
    assertThat(slot).as("%s in the hand", card).isNotNegative();
    return slot;
  }

  private static int slotOrNone(LadderMatch match, String card) {
    int[] slots = match.side(0).getHand().slots();
    for (int slot = 0; slot < Hand.SLOTS; slot++) {
      if (slots[slot] != Hand.EMPTY && match.side(0).deck().get(slots[slot]).name().equals(card)) {
        return slot;
      }
    }
    return -1;
  }

  /** Gives side 0's play of a hand card at the usual point and steps past its run. */
  private static void playAndRun(
      Standard1v1Battle battle, PlayerCommands commands, String card, String name) {
    stepTo(battle, OPEN);
    while (battle.getMatch().side(0).wholeElixir() < Shipped.cost(card)) {
      battle.getBattle().step();
    }
    Giving giving = commands.playCard(0, slot(battle.getMatch(), card), LEVEL, X, Y, name);
    assertThat(giving.given()).isTrue();
    stepTo(battle, giving.runTick() + 1);
  }

  /** A side's king tower, or one of its princess towers. */
  private static TowerEntity tower(Standard1v1Battle battle, int side, boolean king) {
    for (BattleEntity entity : battle.getWorld().getHolder().entities()) {
      if (entity instanceof TowerEntity tower
          && tower.side() == side
          && tower.getData().king() == king) {
        return tower;
      }
    }
    throw new AssertionError("no tower");
  }

  /** The arena's length in game units. */
  private static int lengthUnits(Standard1v1Battle battle) {
    return battle.getWorld().getTileMap().height() * 500;
  }

  /** Steps the battle until its tick counter reaches a tick. */
  private static void stepTo(Standard1v1Battle battle, int tick) {
    while (battle.getBattle().getTick() < tick) {
      battle.getBattle().step();
    }
  }

  /** What two battles are compared by: each side's elixir, spend and hand, and every entity. */
  private static List<String> state(Standard1v1Battle battle) {
    List<String> state = new ArrayList<>();
    for (int s = 0; s < 2; s++) {
      MatchSide side = battle.getMatch().side(s);
      state.add(side.getElixir() + " " + side.getSpent() + " " + side.getHand());
    }
    for (BattleEntity entity : battle.getWorld().getHolder().entities()) {
      if (entity instanceof WorldEntity w) {
        state.add(
            entity.getId()
                + " "
                + w.getData().name()
                + " "
                + w.getView().getX()
                + " "
                + w.getView().getY());
      }
    }
    return state;
  }
}
