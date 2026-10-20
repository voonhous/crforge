/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.Hand;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.unit.ChampionController;
import org.crforge.core.battle.unit.Standard1v1Battle.Command;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A Ladder environment's fork and the vector's look ahead from it. */
class LookaheadTest {

  private static final List<String> BASIC =
      List.of("Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap");

  private static final List<String> OTHER =
      List.of(
          "Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Goblins");

  /** Plain troop cards neither deck holds: cards a seat may believe the enemy's deck holds. */
  private static final List<String> BELIEVED =
      List.of(
          "Valkyrie",
          "HogRider",
          "Wizard",
          "Bomber",
          "Barbarians",
          "MegaMinion",
          "Minions",
          "SpearGoblins");

  private static GameTables tables;

  @BeforeAll
  static void loadTables() {
    tables = GameData.tables();
  }

  private static VectorEnv ladder(int count, int threads) {
    VectorEnv env = VectorEnv.ladder(tables, EnvConfig.DEFAULT, count, threads);
    int[] seeds = new int[count];
    for (int i = 0; i < count; i++) {
      seeds[i] = 300 + i;
    }
    env.reset(seeds, BASIC, OTHER);
    return env;
  }

  private static LadderEnv ladderEnv(VectorEnv env, int index) {
    return (LadderEnv) env.env(index);
  }

  /** A random open play of a seat of an environment, three decisions in ten, else the wait. */
  private static int randomAction(LadderEnv env, int seat, SplittableRandom random) {
    if (random.nextInt(10) >= 3) {
      return Schema.WAIT;
    }
    List<Integer> open = new ArrayList<>();
    for (int a = 1; a < Schema.ACTIONS; a++) {
      if (env.open(seat, a)) {
        open.add(a);
      }
    }
    return open.isEmpty() ? Schema.WAIT : open.get(random.nextInt(open.size()));
  }

  /** Steps an environment of a vector some decisions with random plays of both seats. */
  private static void play(VectorEnv env, int index, int decisions, SplittableRandom random) {
    LadderEnv ladder = ladderEnv(env, index);
    for (int d = 0; d < decisions && !ladder.done(); d++) {
      ladder.step(randomAction(ladder, 0, random), randomAction(ladder, 1, random));
    }
  }

  /** Steps an environment with waits until a seat has a play open, and returns its first. */
  private static int openPlay(LadderEnv env, int seat) {
    while (!env.done()) {
      for (int a = 1; a < Schema.ACTIONS; a++) {
        if (!Schema.isAbility(a) && env.open(seat, a)) {
          return a;
        }
      }
      env.step(Schema.WAIT, Schema.WAIT);
    }
    throw new AssertionError("the battle ended before seat " + seat + " could play");
  }

  /** Both seats' observations and masks and the info record of an environment, as written. */
  private static int[] written(LadderEnv env) {
    int[] all = new int[2 * Schema.SEAT_INTS + 2 * Schema.ACTIONS + Schema.INFO_INTS];
    env.copyObservation(0, all, 0);
    env.copyObservation(1, all, Schema.SEAT_INTS);
    for (int seat = 0; seat < 2; seat++) {
      for (int a = 0; a < Schema.ACTIONS; a++) {
        all[2 * Schema.SEAT_INTS + seat * Schema.ACTIONS + a] = env.open(seat, a) ? 1 : 0;
      }
    }
    env.copyInfo(all, 2 * Schema.SEAT_INTS + 2 * Schema.ACTIONS);
    return all;
  }

  private static byte[] all(ByteBuffer buffer) {
    byte[] bytes = new byte[buffer.capacity()];
    buffer.get(0, bytes);
    return bytes;
  }

  @Test
  void aForkOfTheBattleAsItIsPlaysTheSameBattleToItsEnd() {
    try (VectorEnv env = ladder(1, 1)) {
      SplittableRandom random = new SplittableRandom(7);
      play(env, 0, 400, random);
      LadderEnv original = ladderEnv(env, 0);
      assertThat(original.done()).isFalse();
      LadderEnv fork = original.fork(-1);
      assertThat(fork.tick()).isEqualTo(original.tick());
      assertThat(written(fork)).isEqualTo(written(original));
      while (!original.done()) {
        int action0 = randomAction(original, 0, random);
        int action1 = randomAction(original, 1, random);
        original.step(action0, action1);
        fork.step(action0, action1);
        assertThat(written(fork)).isEqualTo(written(original));
      }
      assertThat(fork.done()).isTrue();
    }
  }

  @Test
  void aCopiedForkIsTheBattlePlayedAgainFromItsStart() {
    for (int seed = 0; seed < 3; seed++) {
      try (VectorEnv env = ladder(1, 1)) {
        SplittableRandom random = new SplittableRandom(40 + seed);
        LadderEnv original = ladderEnv(env, 0);
        for (int at : new int[] {80, 250, 500}) {
          play(env, 0, at - original.episodeStep(), random);
          if (original.done()) {
            break;
          }
          LadderEnv copied = original.fork(-1);
          LadderEnv replayed = original.replayed(-1);
          assertThat(written(copied)).isEqualTo(written(original));
          assertThat(written(replayed)).isEqualTo(written(original));
          SplittableRandom ahead = new SplittableRandom(seed * 1000L + at);
          for (int d = 0; d < 120 && !replayed.done(); d++) {
            int action0 = randomAction(replayed, 0, ahead);
            int action1 = randomAction(replayed, 1, ahead);
            copied.step(action0, action1);
            replayed.step(action0, action1);
            assertThat(written(copied))
                .as("decision %d after %d", d, at)
                .isEqualTo(written(replayed));
          }
        }
      }
    }
  }

  @Test
  void aSeatsCopiedForkSeesWhatItsReplayedForkSees() {
    try (VectorEnv env = ladder(1, 1)) {
      LadderEnv original = ladderEnv(env, 0);
      play(env, 0, 200, new SplittableRandom(13));
      original.step(Schema.WAIT, openPlay(original, 1));
      LadderEnv copied = original.fork(0);
      LadderEnv replayed = original.replayed(0);
      assertThat(copied.battle().getCommands()).isEqualTo(replayed.battle().getCommands());
      for (int d = 0; d < 60 && !replayed.done(); d++) {
        int[] mine = new int[Schema.SEAT_INTS];
        int[] theirs = new int[Schema.SEAT_INTS];
        copied.copyObservation(0, mine, 0);
        replayed.copyObservation(0, theirs, 0);
        assertThat(mine).as("decision %d", d).isEqualTo(theirs);
        copied.step(Schema.WAIT, Schema.WAIT);
        replayed.step(Schema.WAIT, Schema.WAIT);
      }
    }
  }

  @Test
  void aSeatsCopiedForkShowsTheEnemyWhatItsReplayedForkShows() {
    for (int seat = 0; seat < 2; seat++) {
      try (VectorEnv env = ladder(1, 1)) {
        LadderEnv original = ladderEnv(env, 0);
        play(env, 0, 200, new SplittableRandom(13 + seat));
        int enemy = 1 - seat;
        int hidden = openPlay(original, enemy);
        original.step(seat == 0 ? Schema.WAIT : hidden, seat == 0 ? hidden : Schema.WAIT);
        int[] header = new int[Schema.SEAT_INTS];
        original.copyObservation(enemy, header, 0);
        assertThat(header[Schema.H_ELIXIR_RESERVED]).as("the enemy holds elixir back").isPositive();
        LadderEnv copied = original.fork(seat);
        LadderEnv replayed = original.replayed(seat);
        // The fork shows what the battle last wrote until its first step writes again; from then
        // on the enemy's client is as one that never gave the play: its elixir not held back, its
        // card offered, its slot refilled as the replayed battle's.
        for (int d = 0; d < 60 && !replayed.done(); d++) {
          copied.step(Schema.WAIT, Schema.WAIT);
          replayed.step(Schema.WAIT, Schema.WAIT);
          assertThat(written(copied))
              .as("seat %d, decision %d", seat, d)
              .isEqualTo(written(replayed));
        }
      }
    }
  }

  @Test
  void aSeatsForkLeavesOutTheEnemyCommandsTheSeatHasNotSeenRun() {
    try (VectorEnv env = ladder(1, 1)) {
      LadderEnv original = ladderEnv(env, 0);
      SplittableRandom random = new SplittableRandom(11);
      play(env, 0, 200, random);
      // Seat 1 plays, seat 0 waits: the play is given and waits its delay, unseen by seat 0.
      int play = openPlay(original, 1);
      int before = original.battle().getCommands().size();
      original.step(Schema.WAIT, play);
      List<Command> given = original.battle().getCommands();
      assertThat(given).hasSize(before + 1);
      assertThat(given.get(before).tick()).isGreaterThan(original.tick());

      LadderEnv mine = original.fork(0);
      assertThat(mine.battle().getCommands()).hasSize(before);
      // The fork gives its own plays on, under names still new.
      LadderEnv playing = original.fork(0);
      playing.step(openPlay(playing, 0), Schema.WAIT);
      assertThat(playing.fault()).isNull();
      assertThat(playing.battle().getCommands()).hasSize(before + 1);
      LadderEnv theirs = original.fork(1);
      assertThat(theirs.battle().getCommands()).hasSize(before + 1);

      // Once it has run, seat 0 has seen it, and its fork gives it too.
      while (original.tick() <= given.get(before).tick()) {
        original.step(Schema.WAIT, Schema.WAIT);
      }
      assertThat(original.fork(0).battle().getCommands()).hasSize(before + 1);
    }
  }

  @Test
  void theLookAheadStepsForksAndLeavesTheVectorAsItWas() {
    try (VectorEnv env = ladder(2, 2)) {
      for (int i = 0; i < 2; i++) {
        play(env, i, 300, new SplittableRandom(20 + i));
      }
      LadderEnv first = ladderEnv(env, 0);
      int open = openPlay(first, 0);
      byte[] observations = all(env.observations());
      byte[] masks = all(env.masks());
      byte[] infos = all(env.infos());
      int[] indices = {0, 0, 1, 1};
      int[] seats = {0, 0, 1, 0};
      int[] actions = {Schema.WAIT, open, Schema.WAIT, Schema.WAIT};
      int horizon = 160;
      int[] out = env.lookahead(indices, seats, actions, horizon);
      assertThat(env.lookahead(indices, seats, actions, horizon)).isEqualTo(out);
      assertThat(all(env.observations())).isEqualTo(observations);
      assertThat(all(env.masks())).isEqualTo(masks);
      assertThat(all(env.infos())).isEqualTo(infos);

      int stride = Schema.SEAT_INTS + Schema.INFO_INTS;
      // The wait's request is its seat's fork stepped with waits over the horizon.
      LadderEnv fork = first.fork(0);
      int until = fork.tick() + horizon;
      while (!fork.done() && fork.tick() < until) {
        fork.step(Schema.WAIT, Schema.WAIT);
      }
      int[] seat0 = new int[Schema.SEAT_INTS];
      fork.copyObservation(0, seat0, 0);
      assertThat(Arrays.copyOfRange(out, 0, Schema.SEAT_INTS)).isEqualTo(seat0);
      assertThat(out[Schema.SEAT_INTS + Schema.I_TICK]).isGreaterThanOrEqualTo(until);
      assertThat(out[Schema.SEAT_INTS + Schema.I_FAULT]).isEqualTo(Schema.FAULT_NONE);
      // A play looks ahead to another battle than the wait.
      assertThat(Arrays.copyOfRange(out, stride, stride + Schema.SEAT_INTS))
          .isNotEqualTo(Arrays.copyOfRange(out, 0, Schema.SEAT_INTS));
      // Each seat's request is written in its own frame.
      assertThat(out[2 * stride + Schema.H_SEAT]).isEqualTo(1);
      assertThat(out[3 * stride + Schema.H_SEAT]).isZero();
    }
  }

  @Test
  void aForkPutIntoAVectorIsSteppedOnAsTheBattle() {
    try (VectorEnv source = ladder(1, 1);
        VectorEnv forks = ladder(3, 2)) {
      SplittableRandom random = new SplittableRandom(21);
      LadderEnv original = ladderEnv(source, 0);
      play(source, 0, 250, random);
      forks.forkFrom(source, 0, -1, new int[] {0, 2});
      int[] seen = new int[2 * Schema.SEAT_INTS];
      int[] mine = new int[2 * Schema.SEAT_INTS];
      for (int d = 0; d < 80 && !original.done(); d++) {
        for (int target : new int[] {0, 2}) {
          LadderEnv fork = ladderEnv(forks, target);
          assertThat(forks.dones().get(target)).isZero();
          assertThat(written(fork))
              .as("decision %d, fork %d", d, target)
              .isEqualTo(written(original));
          // The vector's own buffers hold what the fork wrote.
          forks.observations().asIntBuffer().get(target * 2 * Schema.SEAT_INTS, mine);
          original.copyObservation(0, seen, 0);
          original.copyObservation(1, seen, Schema.SEAT_INTS);
          assertThat(mine).isEqualTo(seen);
        }
        int action0 = randomAction(original, 0, random);
        int action1 = randomAction(original, 1, random);
        original.step(action0, action1);
        forks.actions().asIntBuffer().put(new int[] {action0, action1, 0, 0, action0, action1});
        forks.step();
      }
      assertThatThrownBy(() -> forks.forkFrom(forks, 1, -1, new int[] {1}))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void aReseededForkDrawsFromItsOwnState() {
    try (VectorEnv env = ladder(1, 1)) {
      LadderEnv original = ladderEnv(env, 0);
      play(env, 0, 100, new SplittableRandom(4));
      int before = original.battle().getWorld().getRandom().getState();
      LadderEnv fork = original.fork(-1);
      fork.reseed(before + 12345);
      assertThat(fork.battle().getWorld().getRandom().getState()).isEqualTo(before + 12345);
      assertThat(original.battle().getWorld().getRandom().getState()).isEqualTo(before);
      // A reseeded look ahead repeats for the same sequence and leaves the battle as it was.
      int play = openPlay(original, 0);
      int[] request = {0};
      env.setLookaheadReseed(9L);
      int[] first = env.lookahead(request, new int[] {0}, new int[] {play}, 160);
      env.setLookaheadReseed(9L);
      int[] again = env.lookahead(request, new int[] {0}, new int[] {play}, 160);
      assertThat(again).isEqualTo(first);
      env.setLookaheadReseed(null);
    }
  }

  @Test
  void eachForkPutIntoAVectorDrawsFromTheSeedItIsGiven() {
    try (VectorEnv source = ladder(1, 1);
        VectorEnv forks = ladder(4, 2)) {
      LadderEnv original = ladderEnv(source, 0);
      play(source, 0, 100, new SplittableRandom(4));
      // The battle as it is, put aside before any fork is made.
      LadderEnv twin = original.fork(-1);
      int state = original.battle().getWorld().getRandom().getState();
      int[] targets = {0, 1, 2, 3};
      forks.forkFrom(source, 0, 0, targets, null, new long[] {5, 6, 5, 7});

      int[] draws = new int[targets.length];
      for (int target : targets) {
        draws[target] = ladderEnv(forks, target).battle().getWorld().getRandom().next(1 << 30);
      }
      assertThat(draws[2]).as("the same seed draws the same").isEqualTo(draws[0]);
      assertThat(draws[1]).as("another seed draws otherwise").isNotEqualTo(draws[0]);
      assertThat(draws[3]).isNotEqualTo(draws[0]).isNotEqualTo(draws[1]);
      int real = twin.battle().getWorld().getRandom().next(1 << 30);
      assertThat(draws).as("no fork draws the battle's own next number").doesNotContain(real);
      // Forking leaves the battle's own draws as they were.
      assertThat(original.battle().getWorld().getRandom().getState()).isEqualTo(state);
      assertThat(original.battle().getWorld().getRandom().next(1 << 30)).isEqualTo(real);

      // A seat's fork is given a seed for each target, and the battle as it is may be too.
      assertThatThrownBy(() -> forks.forkFrom(source, 0, 0, new int[] {0}))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> forks.forkFrom(source, 0, 1, new int[] {0, 1}, null, new long[] {1}))
          .isInstanceOf(IllegalArgumentException.class);
      forks.forkFrom(source, 0, -1, new int[] {0}, null, new long[] {5});
      assertThat(ladderEnv(forks, 0).battle().getWorld().getRandom().next(1 << 30))
          .isEqualTo(draws[0]);
    }
  }

  @Test
  void aShuffledForkDealsAgainOnlyTheEnemyCardsTheSeatHasNotSeen() {
    try (VectorEnv source = ladder(1, 1);
        VectorEnv forks = ladder(4, 2)) {
      LadderEnv original = ladderEnv(source, 0);
      play(source, 0, 60, new SplittableRandom(8));
      Hand hand = original.battle().getMatch().side(1).getHand();
      List<String> deck = OTHER;
      int[] slots = hand.slots();
      List<Integer> queue = hand.queue();
      int[] targets = {0, 1, 2, 3};
      forks.forkFrom(source, 0, 0, targets, new long[] {1, 2, 3, 1}, new long[] {1, 2, 3, 4});
      List<String> seen = original.enemySeen(0);
      boolean moved = false;
      for (int target : targets) {
        Hand dealt = ladderEnv(forks, target).battle().getMatch().side(1).getHand();
        int[] places = new int[Hand.SLOTS + queue.size()];
        int[] then = new int[places.length];
        for (int k = 0; k < places.length; k++) {
          places[k] = k < Hand.SLOTS ? slots[k] : queue.get(k - Hand.SLOTS);
          then[k] = k < Hand.SLOTS ? dealt.slots()[k] : dealt.queue().get(k - Hand.SLOTS);
          if (places[k] != Hand.EMPTY && seen.contains(deck.get(places[k]))) {
            assertThat(then[k]).as("a card seen keeps its place").isEqualTo(places[k]);
          }
          moved |= then[k] != places[k];
        }
        assertThat(Arrays.stream(then).sorted().toArray())
            .isEqualTo(Arrays.stream(places).sorted().toArray());
        // The seat's own view is the fork's as written.
        int[] mine = new int[Schema.SEAT_INTS];
        int[] theirs = new int[Schema.SEAT_INTS];
        ladderEnv(forks, target).copyObservation(0, mine, 0);
        original.copyObservation(0, theirs, 0);
        assertThat(mine).isEqualTo(theirs);
      }
      assertThat(moved).isTrue();
      // The same seed deals the same hand.
      assertThat(ladderEnv(forks, 3).battle().getMatch().side(1).getHand().slots())
          .isEqualTo(ladderEnv(forks, 0).battle().getMatch().side(1).getHand().slots());
      // The enemy's hand of the battle itself is untouched.
      assertThat(hand.slots()).isEqualTo(slots);
    }
  }

  /** A side's deck in a Ladder environment's match, by card row name. */
  private static List<String> deckOf(LadderEnv env, int side) {
    return env.battle().getMatch().side(side).deck().stream().map(MatchCard::name).toList();
  }

  /** A side's hand slots then queue, by card row name, null for an empty slot. */
  private static List<String> handOf(LadderEnv env, int side) {
    Hand hand = env.battle().getMatch().side(side).getHand();
    List<String> deck = deckOf(env, side);
    List<String> cards = new ArrayList<>();
    for (int index : hand.slots()) {
      cards.add(index == Hand.EMPTY ? null : deck.get(index));
    }
    hand.queue().forEach(index -> cards.add(deck.get(index)));
    return cards;
  }

  @Test
  void aForkDealsTheCardsGivenInPlaceOfTheEnemyCardsTheSeatHasNotSeen() {
    try (VectorEnv source = ladder(1, 1);
        VectorEnv forks = ladder(2, 2)) {
      LadderEnv original = ladderEnv(source, 0);
      play(source, 0, 120, new SplittableRandom(8));
      List<String> seen = original.enemySeen(0);
      List<String> real = deckOf(original, 1);
      List<String> realHand = handOf(original, 1);
      List<String> unseen = real.stream().filter(card -> !seen.contains(card)).toList();
      assertThat(unseen).isNotEmpty();
      assertThat(source.enemySeen(0, 0)).containsExactlyElementsOf(seen);
      List<String> given = BELIEVED.subList(0, unseen.size());
      String[] cards = given.toArray(String[]::new);
      forks.forkFrom(
          source,
          0,
          0,
          new int[] {0, 1},
          new long[] {1, 2},
          new long[] {1, 2},
          new String[][] {cards, cards},
          null);

      Vocabulary vocabulary = VectorEnv.vocabulary(tables);
      Set<Integer> realUnseenIds = new HashSet<>();
      unseen.forEach(card -> realUnseenIds.add(vocabulary.id(card)));
      for (int target = 0; target < 2; target++) {
        LadderEnv fork = ladderEnv(forks, target);
        List<String> deck = deckOf(fork, 1);
        for (int index = 0; index < deck.size(); index++) {
          if (seen.contains(real.get(index))) {
            assertThat(deck.get(index))
                .as("a seen card keeps its deck index")
                .isEqualTo(real.get(index));
          }
        }
        assertThat(deck).containsExactlyInAnyOrderElementsOf(concat(seen, given));
        List<String> dealt = handOf(fork, 1);
        for (int k = 0; k < realHand.size(); k++) {
          if (realHand.get(k) != null && seen.contains(realHand.get(k))) {
            assertThat(dealt.get(k)).as("a seen card keeps its place").isEqualTo(realHand.get(k));
          } else if (realHand.get(k) != null) {
            assertThat(given).contains(dealt.get(k));
          }
        }
        // The enemy's own view shows the hand it was dealt, none of its real unseen cards.
        fork.step(Schema.WAIT, Schema.WAIT);
        int[] view = new int[Schema.SEAT_INTS];
        fork.copyObservation(1, view, 0);
        List<String> now = handOf(fork, 1);
        for (int k = 0; k < Hand.SLOTS; k++) {
          if (now.get(k) != null) {
            assertThat(view[Schema.H_HAND_CARD + k]).isEqualTo(vocabulary.id(now.get(k)));
            assertThat(realUnseenIds).doesNotContain(view[Schema.H_HAND_CARD + k]);
          }
        }
        // And it plays them: a dealt card's play runs as that card.
        String played = null;
        int action = Schema.WAIT;
        while (played == null && !fork.done()) {
          for (int a = 1; a < Schema.ACTIONS && played == null; a++) {
            if (!Schema.isAbility(a) && fork.open(1, a)) {
              String card = handOf(fork, 1).get((a - 1) / Schema.TILES);
              if (given.contains(card)) {
                played = card;
                action = a;
              }
            }
          }
          if (played == null) {
            fork.step(Schema.WAIT, Schema.WAIT);
          }
        }
        assertThat(played).isNotNull();
        int plays = fork.battle().getPlays().size();
        fork.step(Schema.WAIT, action);
        for (int d = 0; d < 10; d++) {
          fork.step(Schema.WAIT, Schema.WAIT);
        }
        assertThat(fork.battle().getPlays().subList(plays, fork.battle().getPlays().size()))
            .filteredOn(p -> p.side() == 1)
            .extracting(p -> p.evolution().spell().name())
            .contains(played);
      }
      assertThat(deckOf(original, 1)).as("the battle itself keeps its deck").isEqualTo(real);
      assertThat(handOf(original, 1)).isEqualTo(realHand);
    }
  }

  @Test
  void aDealPutsTheCardsGivenInTheSlotsGivenAndFollowsAChampionDealt() {
    int[] heroFirst = {Schema.SLOT_HERO, 0, 0, 0, 0, 0, 0, 0};
    try (VectorEnv source = VectorEnv.ladder(tables, EnvConfig.DEFAULT, 1, 1);
        VectorEnv forks = ladder(1, 1)) {
      source.reset(new int[] {300}, BASIC, OTHER, new int[Schema.DECK_SIZE], heroFirst);
      LadderEnv original = ladderEnv(source, 0);
      original.step(Schema.WAIT, Schema.WAIT);
      assertThat(original.enemySeen(0)).isEmpty();
      assertThat(source.enemyUnseenSlots(0, 0))
          .containsExactlyInAnyOrder(Schema.SLOT_HERO, 0, 0, 0, 0, 0, 0, 0);
      List<String> given = new ArrayList<>(BELIEVED.subList(0, 7));
      given.add("ArcherQueen");
      int[] slots = {0, 0, 0, 0, 0, 0, 0, 0};
      slots[2] = Schema.SLOT_HERO;
      forks.forkFrom(
          source,
          0,
          0,
          new int[] {0},
          new long[] {4},
          new long[] {4},
          new String[][] {given.toArray(String[]::new)},
          new int[][] {slots});
      LadderEnv fork = ladderEnv(forks, 0);
      List<String> deck = deckOf(fork, 1);
      assertThat(deck.get(0)).as("the card given for the hero slot").isEqualTo(given.get(2));
      assertThat(deck).containsExactlyInAnyOrderElementsOf(given);
      MatchSide side = fork.battle().getMatch().side(1);
      assertThat(side.slotFlags(0)).isEqualTo(Schema.SLOT_HERO);
      // The champion dealt has one of the king's slots, at its deck index; the real deck's king
      // has none for it.
      List<String> followed = new ArrayList<>();
      List<String> real = new ArrayList<>();
      for (int n = 1; n <= 2; n++) {
        ChampionController slot = fork.battle().getWorld().kingTower(1).championSlot(n);
        if (slot.getChampion() != null) {
          followed.add(deck.get(slot.getDeckIndex()));
        }
        ChampionController before = original.battle().getWorld().kingTower(1).championSlot(n);
        if (before.getChampion() != null) {
          real.add(deckOf(original, 1).get(before.getDeckIndex()));
        }
      }
      assertThat(followed).contains("ArcherQueen");
      assertThat(real).doesNotContain("ArcherQueen");
      fork.step(Schema.WAIT, Schema.WAIT);
      assertThat(fork.fault()).isNull();
    }
  }

  @Test
  void aDealTheEnemysDeckCannotHoldIsRefused() {
    int[] heroFirst = {Schema.SLOT_HERO, 0, 0, 0, 0, 0, 0, 0};
    try (VectorEnv source = VectorEnv.ladder(tables, EnvConfig.DEFAULT, 1, 1);
        VectorEnv forks = ladder(1, 1)) {
      source.reset(new int[] {300}, BASIC, OTHER, new int[Schema.DECK_SIZE], heroFirst);
      ladderEnv(source, 0).step(Schema.WAIT, Schema.WAIT);
      int[] target = {0};
      long[] one = {1};
      String[] seven = BELIEVED.subList(0, 7).toArray(String[]::new);
      String[] twice = BELIEVED.toArray(String[]::new);
      twice[7] = twice[0];
      String[] unknown = BELIEVED.toArray(String[]::new);
      unknown[7] = "NoSuchCard";
      // Cards none of which a hero slot holds: spells or buildings with no hero form, as the
      // match's set up checks.
      Vocabulary vocabulary = VectorEnv.vocabulary(tables);
      String[] noHero =
          vocabulary.names().stream()
              .filter(vocabulary::isDeckCard)
              .filter(card -> !OTHER.contains(card))
              .filter(
                  card ->
                      !LadderMatch.slotHolds(
                          ladderEnv(source, 0).battle().getWorld().getRecords(),
                          card,
                          MatchSide.HERO_SLOT))
              .limit(Schema.DECK_SIZE)
              .toArray(String[]::new);
      assertThat(noHero).hasSize(Schema.DECK_SIZE);
      String[] believed = BELIEVED.toArray(String[]::new);
      for (String[][] cards :
          List.of(
              new String[][] {seven},
              new String[][] {twice},
              new String[][] {unknown},
              new String[][] {noHero},
              new String[][] {believed, believed})) {
        assertThatThrownBy(() -> forks.forkFrom(source, 0, 0, target, one, one, cards, null))
            .as(Arrays.toString(cards[0]))
            .isInstanceOf(IllegalArgumentException.class);
      }
      // Slot flags given that are not those of the unseen places.
      assertThatThrownBy(
              () ->
                  forks.forkFrom(
                      source,
                      0,
                      0,
                      target,
                      one,
                      one,
                      new String[][] {believed},
                      new int[][] {new int[Schema.DECK_SIZE]}))
          .isInstanceOf(IllegalArgumentException.class);
      // Cards dealt without a shuffle seed, or into the battle as it is.
      assertThatThrownBy(
              () ->
                  forks.forkFrom(source, 0, 0, target, null, one, new String[][] {believed}, null))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  forks.forkFrom(source, 0, -1, target, null, one, new String[][] {believed}, null))
          .isInstanceOf(IllegalArgumentException.class);
      // The legal deal goes through.
      forks.forkFrom(source, 0, 0, target, one, one, new String[][] {believed}, null);
      assertThat(deckOf(ladderEnv(forks, 0), 1)).containsExactlyInAnyOrderElementsOf(BELIEVED);
    }
  }

  private static List<String> concat(List<String> first, List<String> second) {
    List<String> all = new ArrayList<>(first);
    all.addAll(second);
    return all;
  }

  @Test
  void onlyARunningLadderEpisodeIsForked() {
    try (VectorEnv env = ladder(1, 1)) {
      LadderEnv ladder = ladderEnv(env, 0);
      assertThatThrownBy(() -> ladder.fork(2)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> env.lookahead(new int[] {0}, new int[] {0}, new int[0], 160))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> env.lookahead(new int[] {1}, new int[] {0}, new int[] {0}, 160))
          .isInstanceOf(IllegalArgumentException.class);
      while (!ladder.done()) {
        ladder.step(Schema.WAIT, Schema.WAIT);
      }
      assertThatThrownBy(() -> ladder.fork(0)).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> env.lookahead(new int[] {0}, new int[] {0}, new int[] {0}, 160))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
