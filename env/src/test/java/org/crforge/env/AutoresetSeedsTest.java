/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The seeds a vector's autoreset starts episodes at: by default the next integer of the sequence
 * each environment's reset seed starts, as before the range existed; with a range, every one inside
 * it, the same for the same reset seeds. On synthetic environments, no tables needed.
 */
class AutoresetSeedsTest {

  private static final List<String> DECK = List.of("a", "b", "c", "d", "e", "f", "g", "h");

  /** A synthetic environment that keeps every seed it is reset with, in order. */
  private static final class SeedRecorder implements BattleEnv {
    private final SyntheticEnv inner;
    private final List<Integer> seeds = new ArrayList<>();

    SeedRecorder(int episodeSteps) {
      inner = new SyntheticEnv(4, episodeSteps);
    }

    @Override
    public void bind(
        IntBuffer observations, ByteBuffer masks, FloatBuffer rewards, IntBuffer info) {
      inner.bind(observations, masks, rewards, info);
    }

    @Override
    public void checkDeck(List<String> deck, int[] slots) {
      inner.checkDeck(deck, slots);
    }

    @Override
    public void reset(
        int seed, List<String> deck0, List<String> deck1, int[] slots0, int[] slots1) {
      seeds.add(seed);
      inner.reset(seed, deck0, deck1, slots0, slots1);
    }

    @Override
    public void step(int action0, int action1) {
      inner.step(action0, action1);
    }

    @Override
    public boolean done() {
      return inner.done();
    }

    @Override
    public void fail(Throwable thrown) {
      inner.fail(thrown);
    }

    @Override
    public EnvFault fault() {
      return inner.fault();
    }
  }

  /** A vector of seed recorders whose episodes last one decision, so every other step resets. */
  private static VectorEnv recorders(int count, int threads) {
    return new VectorEnv(() -> new SeedRecorder(1), count, threads);
  }

  private static List<Integer> seeds(VectorEnv env, int index) {
    return ((SeedRecorder) env.env(index)).seeds;
  }

  /**
   * Steps a vector of one-decision episodes until each environment has autoreset {@code n} times.
   */
  private static void autoreset(VectorEnv env, int n) {
    for (int step = 0; step < 2 * n; step++) {
      env.step();
    }
  }

  @Test
  void byDefaultAnAutoresetDrawsTheNextIntegerOfTheSequenceTheResetSeedStarts() {
    try (VectorEnv env = recorders(2, 1)) {
      env.reset(new int[] {7, -3}, DECK, DECK);
      autoreset(env, 20);
      for (int index = 0; index < 2; index++) {
        int resetSeed = index == 0 ? 7 : -3;
        SplittableRandom sequence = new SplittableRandom(resetSeed);
        List<Integer> expected = new ArrayList<>(List.of(resetSeed));
        for (int n = 0; n < 20; n++) {
          expected.add(sequence.nextInt());
        }
        assertThat(seeds(env, index)).isEqualTo(expected);
      }
      assertThat(env.autoresetSeedMin()).isEqualTo(Integer.MIN_VALUE);
      assertThat(env.autoresetSeedMax()).isEqualTo(Integer.MAX_VALUE);
    }
  }

  @Test
  void theWholeRangeGivenIsTheDefaultSequence() {
    try (VectorEnv plain = recorders(1, 1);
        VectorEnv whole = recorders(1, 1)) {
      whole.setAutoresetSeeds(Integer.MIN_VALUE, Integer.MAX_VALUE);
      for (VectorEnv env : List.of(plain, whole)) {
        env.reset(new int[] {41}, DECK, DECK);
        autoreset(env, 10);
      }
      assertThat(seeds(whole, 0)).isEqualTo(seeds(plain, 0));
    }
  }

  @Test
  void withARangeEveryAutoresetSeedFallsInIt() {
    int min = 10_000_000;
    try (VectorEnv env = recorders(4, 2)) {
      env.setAutoresetSeeds(min, Integer.MAX_VALUE);
      env.reset(new int[] {0, 1, -1, Integer.MIN_VALUE}, DECK, DECK);
      autoreset(env, 500);
      for (int index = 0; index < 4; index++) {
        List<Integer> drawn = seeds(env, index).subList(1, 501);
        assertThat(drawn)
            .hasSize(500)
            .allSatisfy(seed -> assertThat(seed).isGreaterThanOrEqualTo(min));
        // Drawn evenly: some in the upper half of the range, some in the lower.
        assertThat(drawn).anySatisfy(seed -> assertThat(seed).isGreaterThan(1_100_000_000));
        assertThat(drawn).anySatisfy(seed -> assertThat(seed).isLessThan(1_000_000_000));
      }
    }
  }

  @Test
  void bothEndsOfARangeAreIncluded() {
    try (VectorEnv env = recorders(1, 1)) {
      env.setAutoresetSeeds(-2, 2);
      env.reset(new int[] {5}, DECK, DECK);
      autoreset(env, 200);
      Set<Integer> drawn = new HashSet<>(seeds(env, 0).subList(1, 201));
      assertThat(drawn).containsExactlyInAnyOrder(-2, -1, 0, 1, 2);
    }
    try (VectorEnv env = recorders(1, 1)) {
      env.setAutoresetSeeds(9, 9);
      env.reset(new int[] {5}, DECK, DECK);
      autoreset(env, 3);
      assertThat(seeds(env, 0)).containsExactly(5, 9, 9, 9);
    }
  }

  @Test
  void theSameResetSeedsGiveTheSameAutoresetSeedsOnAnyThreadCount() {
    int[] resetSeeds = {11, 12, 13};
    try (VectorEnv one = recorders(3, 1);
        VectorEnv three = recorders(3, 3);
        VectorEnv other = recorders(3, 1)) {
      for (VectorEnv env : List.of(one, three, other)) {
        env.setAutoresetSeeds(10_000_000, Integer.MAX_VALUE);
      }
      one.reset(resetSeeds, DECK, DECK);
      three.reset(resetSeeds, DECK, DECK);
      other.reset(new int[] {21, 22, 23}, DECK, DECK);
      for (VectorEnv env : List.of(one, three, other)) {
        autoreset(env, 50);
      }
      for (int index = 0; index < 3; index++) {
        assertThat(seeds(three, index)).isEqualTo(seeds(one, index));
        assertThat(seeds(other, index).subList(1, 51))
            .isNotEqualTo(seeds(one, index).subList(1, 51));
      }
    }
  }

  @Test
  void aResetOfOneEnvironmentRestartsItsSequenceInsideTheRange() {
    try (VectorEnv reset = recorders(2, 1);
        VectorEnv fresh = recorders(2, 1)) {
      reset.setAutoresetSeeds(100, 1_000);
      fresh.setAutoresetSeeds(100, 1_000);
      reset.reset(new int[] {1, 2}, DECK, DECK);
      autoreset(reset, 5);
      reset.resetEnv(0, 99);
      fresh.reset(new int[] {99, 2}, DECK, DECK);
      autoreset(reset, 30);
      autoreset(fresh, 30);
      List<Integer> after =
          seeds(reset, 0).subList(seeds(reset, 0).size() - 31, seeds(reset, 0).size());
      assertThat(after).isEqualTo(seeds(fresh, 0));
      assertThat(after.subList(1, 31)).allSatisfy(seed -> assertThat(seed).isBetween(100, 1_000));
    }
  }

  @Test
  void theEpisodeSeedIsTheSeedTheCurrentEpisodeStartedAt() {
    try (VectorEnv env = recorders(2, 1)) {
      env.setAutoresetSeeds(1_000, 2_000);
      env.reset(new int[] {3, 4}, DECK, DECK);
      assertThat(env.episodeSeed(0)).isEqualTo(3);
      assertThat(env.episodeSeed(1)).isEqualTo(4);
      for (int step = 0; step < 10; step++) {
        env.step();
        for (int index = 0; index < 2; index++) {
          List<Integer> seen = seeds(env, index);
          assertThat(env.episodeSeed(index)).isEqualTo(seen.get(seen.size() - 1));
        }
      }
      env.resetEnv(1, -8);
      assertThat(env.episodeSeed(1)).isEqualTo(-8);
    }
  }

  @Test
  void anEmptyRangeIsRefusedAndTheRangeKept() {
    try (VectorEnv env = recorders(1, 1)) {
      env.setAutoresetSeeds(5, 10);
      assertThatThrownBy(() -> env.setAutoresetSeeds(10, 5))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("10")
          .hasMessageContaining("5");
      assertThat(env.autoresetSeedMin()).isEqualTo(5);
      assertThat(env.autoresetSeedMax()).isEqualTo(10);
    }
  }

  @Test
  void theMetadataNamesTheRange() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    try (VectorEnv env = recorders(1, 1)) {
      JsonNode plain = mapper.readTree(env.metadataJson()).get("autoreset_seeds");
      assertThat(plain.get("min").asLong()).isEqualTo(Integer.MIN_VALUE);
      assertThat(plain.get("max").asLong()).isEqualTo(Integer.MAX_VALUE);
      env.setAutoresetSeeds(10_000_000, Integer.MAX_VALUE);
      JsonNode range = mapper.readTree(env.metadataJson()).get("autoreset_seeds");
      assertThat(range.get("min").asLong()).isEqualTo(10_000_000);
      assertThat(range.get("max").asLong()).isEqualTo(Integer.MAX_VALUE);
    }
    // A tool's metadata without a vector has no autoreset.
    assertThat(VectorEnv.metadataJson(EnvConfig.DEFAULT, null)).doesNotContain("autoreset_seeds");
  }
}
