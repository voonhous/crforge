/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.IntBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The vector environment's buffers, resets and fault boundary, on synthetic environments. */
class SyntheticVectorEnvTest {

  private static final List<String> DECK = List.of("a", "b", "c", "d", "e", "f", "g", "h");

  private static int header(VectorEnv env, int index, int seat, int field) {
    IntBuffer ints = env.observations().asIntBuffer();
    return ints.get((index * 2 + seat) * Schema.SEAT_INTS + field);
  }

  private static int info(VectorEnv env, int index, int field) {
    return env.infos().asIntBuffer().get(index * Schema.INFO_INTS + field);
  }

  /** A vector of synthetic environments, one of which fails at a decision with a fault kind. */
  private static VectorEnv withFault(
      int count, int threads, int faulty, int atStep, EnvFault.Kind kind) {
    int[] made = {0};
    return new VectorEnv(
        () -> {
          int index = made[0]++;
          return index == faulty
              ? new SyntheticEnv(10, 50, atStep, kind)
              : new SyntheticEnv(10, 50);
        },
        count,
        threads);
  }

  @Test
  void theBuffersAreLaidOutEnvironmentAfterEnvironmentAndSeatAfterSeat() {
    try (VectorEnv env = VectorEnv.synthetic(3, 1, 20, 4)) {
      assertThat(env.observations().capacity()).isEqualTo(3 * 2 * Schema.SEAT_INTS * 4);
      assertThat(env.masks().capacity()).isEqualTo(3 * 2 * Schema.ACTIONS);
      assertThat(env.actions().capacity()).isEqualTo(3 * 2 * 4);
      assertThat(env.rewards().capacity()).isEqualTo(3 * 2 * 4);
      assertThat(env.dones().capacity()).isEqualTo(3);
      assertThat(env.infos().capacity()).isEqualTo(3 * Schema.INFO_INTS * 4);
      assertThat(env.observations().isDirect()).isTrue();
      env.reset(new int[] {1, 2, 3}, DECK, DECK);
      for (int index = 0; index < 3; index++) {
        for (int seat = 0; seat < 2; seat++) {
          assertThat(header(env, index, seat, Schema.H_SEAT)).isEqualTo(seat);
          assertThat(header(env, index, seat, Schema.H_ENTITY_COUNT)).isEqualTo(20);
          assertThat(header(env, index, seat, Schema.H_EPISODE_STEP)).isEqualTo(0);
          assertThat(env.masks().get((index * 2 + seat) * Schema.ACTIONS + Schema.WAIT))
              .isEqualTo((byte) 1);
        }
        assertThat(env.dones().get(index)).isEqualTo((byte) 0);
      }
    }
  }

  @Test
  void anEpisodeEndsWithDoneAndTheNextStepResetsIt() {
    try (VectorEnv env = VectorEnv.synthetic(2, 2, 10, 3)) {
      env.reset(new int[] {7, 8}, DECK, DECK);
      for (int step = 1; step <= 3; step++) {
        env.step();
        assertThat(header(env, 0, 0, Schema.H_EPISODE_STEP)).isEqualTo(step);
        assertThat(info(env, 1, Schema.I_EPISODE_STEP)).isEqualTo(step);
      }
      assertThat(env.dones().get(0)).isEqualTo((byte) 1);
      assertThat(env.dones().get(1)).isEqualTo((byte) 1);
      assertThat(env.rewards().asFloatBuffer().get(0)).isEqualTo(1f);
      assertThat(env.rewards().asFloatBuffer().get(1)).isEqualTo(-1f);
      assertThat(header(env, 0, 0, Schema.H_RESULT)).isEqualTo(Schema.WON);
      assertThat(header(env, 0, 1, Schema.H_RESULT)).isEqualTo(Schema.LOST);
      // The next step resets instead of stepping.
      env.step();
      assertThat(env.dones().get(0)).isEqualTo((byte) 0);
      assertThat(info(env, 0, Schema.I_RESET)).isEqualTo(1);
      assertThat(header(env, 0, 0, Schema.H_EPISODE_STEP)).isEqualTo(0);
      assertThat(env.rewards().asFloatBuffer().get(0)).isEqualTo(0f);
      env.step();
      assertThat(info(env, 0, Schema.I_RESET)).isEqualTo(0);
      assertThat(header(env, 0, 0, Schema.H_EPISODE_STEP)).isEqualTo(1);
    }
  }

  @Test
  void theSameSeedsGiveTheSameObservationsOnOneThreadOrFour() {
    int[] seeds = {11, 12, 13, 14, 15};
    try (VectorEnv one = VectorEnv.synthetic(5, 1, 30, 50);
        VectorEnv four = VectorEnv.synthetic(5, 4, 30, 50)) {
      one.reset(seeds, DECK, DECK);
      four.reset(seeds, DECK, DECK);
      for (int step = 0; step < 60; step++) {
        one.step();
        four.step();
      }
      assertThat(four.observations()).isEqualTo(one.observations());
      assertThat(four.masks()).isEqualTo(one.masks());
      assertThat(four.dones()).isEqualTo(one.dones());
    }
  }

  @Test
  void withoutAutoresetAFinishedEnvironmentRefusesToStep() {
    try (VectorEnv env = VectorEnv.synthetic(1, 1, 5, 1)) {
      env.setAutoreset(false);
      env.reset(new int[] {1}, DECK, DECK);
      env.step();
      assertThat(env.dones().get(0)).isEqualTo((byte) 1);
      assertThatThrownBy(env::step).isInstanceOf(IllegalStateException.class);
      env.resetEnv(0, 2);
      assertThat(env.dones().get(0)).isEqualTo((byte) 0);
      env.step();
    }
  }

  @Test
  void aStepBeforeTheFirstResetIsRefused() {
    try (VectorEnv env = VectorEnv.synthetic(1, 1, 5, 1)) {
      assertThatThrownBy(env::step).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> env.reset(new int[] {1, 2}, DECK, DECK))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void aBadDeckRefusesTheResetBeforeAnyEnvironmentIsTouched() {
    try (VectorEnv env = VectorEnv.synthetic(2, 1, 5, 10)) {
      env.reset(new int[] {1, 2}, DECK, DECK);
      env.step();
      byte[] before = new byte[env.observations().capacity()];
      env.observations().get(0, before);
      String[] decks0 = new String[16];
      String[] decks1 = new String[16];
      for (int k = 0; k < 16; k++) {
        decks0[k] = DECK.get(k % 8);
        decks1[k] = DECK.get(k % 8);
      }
      // The second environment's side 1 deck has a card with no name.
      decks1[12] = null;
      assertThatThrownBy(() -> env.reset(new int[] {3, 4}, decks0, decks1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("environment 1, side 1");
      byte[] after = new byte[env.observations().capacity()];
      env.observations().get(0, after);
      assertThat(after).isEqualTo(before);
      assertThat(header(env, 0, 0, Schema.H_EPISODE_STEP)).isEqualTo(1);
    }
  }

  @Test
  void aFaultInOneEnvironmentEndsThatEnvironmentAloneOnEveryThread() {
    for (EnvFault.Kind kind : EnvFault.Kind.values()) {
      for (int threads : new int[] {1, 2, 4}) {
        try (VectorEnv env = withFault(4, threads, 1, 3, kind)) {
          env.reset(new int[] {1, 2, 3, 4}, DECK, DECK);
          for (int step = 1; step <= 3; step++) {
            env.step();
          }
          // The faulty environment ended at its third decision, truncated with its fault's kind.
          assertThat(env.dones().get(1)).isEqualTo((byte) 1);
          assertThat(info(env, 1, Schema.I_FAULT)).isEqualTo(kind.code());
          assertThat(info(env, 1, Schema.I_TRUNCATED)).isEqualTo(1);
          assertThat(info(env, 1, Schema.I_WINNER)).isEqualTo(Schema.WINNER_NONE);
          assertThat(env.rewards().asFloatBuffer().get(2)).isEqualTo(0f);
          assertThat(env.masks().get(2 * Schema.ACTIONS + Schema.WAIT)).isEqualTo((byte) 1);
          assertThat(env.masks().get(2 * Schema.ACTIONS + 1)).isEqualTo((byte) 0);
          assertThat(env.fault(1).kind()).isEqualTo(kind);
          assertThat(env.fault(1).signature()).startsWith(kind.name().toLowerCase() + " ");
          // The others, before and after it in its thread's share, stepped on.
          for (int other : new int[] {0, 2, 3}) {
            assertThat(env.dones().get(other)).isEqualTo((byte) 0);
            assertThat(info(env, other, Schema.I_FAULT)).isZero();
            assertThat(header(env, other, 0, Schema.H_EPISODE_STEP)).isEqualTo(3);
            assertThat(env.fault(other)).isNull();
          }
          assertThat(env.faultCounts()).hasSize(1).containsValue(1);
          assertThat(env.faultKindCounts()).containsEntry(kind, 1);
          // The next step resets it; the one after steps it again.
          env.step();
          assertThat(info(env, 1, Schema.I_RESET)).isEqualTo(1);
          assertThat(env.fault(1)).isNull();
          assertThat(header(env, 0, 0, Schema.H_EPISODE_STEP)).isEqualTo(4);
          env.step();
          assertThat(header(env, 1, 0, Schema.H_EPISODE_STEP)).isEqualTo(1);
          // A second fault of the same cause counts under the same signature.
          env.step();
          env.step();
          assertThat(env.faultCounts()).hasSize(1).containsValue(2);
        }
      }
    }
  }
}
