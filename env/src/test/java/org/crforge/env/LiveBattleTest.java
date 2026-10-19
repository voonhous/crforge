/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.PlayerCommands;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A live battle is the environment's battle stepped tick by tick: a seat acting as each decision
 * opens sees what it sees under {@link LadderEnv#step}, and a play given between ticks is kept as
 * the environment keeps its own.
 */
class LiveBattleTest {

  private static final List<String> BASIC =
      List.of("Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap");

  private static final int[] PLAIN = new int[Schema.DECK_SIZE];

  private static GameTables tables;
  private static Vocabulary vocabulary;

  @BeforeAll
  static void loadTables() {
    tables = GameData.tables();
    vocabulary = VectorEnv.vocabulary(tables);
  }

  /**
   * A seat's action of a decision, from its mask alone: every seventh decision of the seat, the
   * open play of the lowest slot nearest the middle of its open tiles; else a wait.
   */
  private static int action(byte[] mask, int decision, int seat) {
    if ((decision + 3 * seat) % 7 != 0) {
      return Schema.WAIT;
    }
    for (int slot = 0; slot < Schema.HAND_SLOTS; slot++) {
      int from = 1 + slot * Schema.TILES;
      int[] open = new int[Schema.TILES];
      int count = 0;
      for (int t = 0; t < Schema.TILES; t++) {
        if (mask[from + t] != 0) {
          open[count++] = t;
        }
      }
      if (count > 0) {
        return from + open[count / 2];
      }
    }
    return Schema.WAIT;
  }

  private static byte[] mask(ByteBuffer masks, int seat) {
    byte[] copy = new byte[Schema.ACTIONS];
    masks.get(seat * Schema.ACTIONS, copy);
    return copy;
  }

  @Test
  void seatsActingAsDecisionsOpenSeeWhatTheySeeUnderStep() {
    EnvConfig config = EnvConfig.DEFAULT;
    IntBuffer observations = IntBuffer.allocate(2 * Schema.SEAT_INTS);
    ByteBuffer masks = ByteBuffer.allocate(2 * Schema.ACTIONS).order(ByteOrder.nativeOrder());
    LadderEnv stepped = new LadderEnv(tables, vocabulary, config);
    stepped.bind(
        observations, masks, FloatBuffer.allocate(2), IntBuffer.allocate(Schema.INFO_INTS));
    stepped.reset(7, BASIC, BASIC, PLAIN, PLAIN);
    LiveBattle live = new LiveBattle(tables, vocabulary, config);
    live.reset(7, BASIC, BASIC, PLAIN, PLAIN);

    int plays = 0;
    for (int decision = 0; decision < 400 && !stepped.done(); decision++) {
      for (int seat = 0; seat < 2; seat++) {
        int[] seen = new int[Schema.SEAT_INTS];
        observations.get(seat * Schema.SEAT_INTS, seen);
        assertThat(live.observation(seat))
            .as("seat %d, decision %d", seat, decision)
            .isEqualTo(seen);
        assertThat(live.mask(seat)).isEqualTo(mask(masks, seat));
      }
      int a0 = action(mask(masks, 0), decision, 0);
      int a1 = action(mask(masks, 1), decision, 1);
      plays += (a0 != Schema.WAIT ? 1 : 0) + (a1 != Schema.WAIT ? 1 : 0);
      stepped.step(a0, a1);
      // The side given first alternates by decision, as under step.
      assertThat(live.atDecision()).isTrue();
      if (decision % 2 == 0) {
        live.act(0, a0);
        live.act(1, a1);
      } else {
        live.act(1, a1);
        live.act(0, a0);
      }
      boolean ended = false;
      for (int t = 0; t < config.decisionTicks() && !ended; t++) {
        ended = live.step();
        assertThat(ended || !live.atDecision()).isTrue();
      }
      assertThat(ended).isTrue();
      assertThat(live.tick()).isEqualTo(stepped.battle().getBattle().getTick());
    }
    assertThat(plays).isGreaterThan(10);
  }

  @Test
  void aPlayGivenBetweenTicksIsKeptAsTheEnvironmentKeepsItsOwn() {
    LiveBattle live = new LiveBattle(tables, vocabulary, EnvConfig.DEFAULT);
    live.reset(3, BASIC, BASIC, PLAIN, PLAIN);
    int start = live.battle().getMatch().getStartCooldownTicks();
    // Past the start cooldown and two ticks into a decision.
    while (live.tick() < start || live.tick() % EnvConfig.DEFAULT.decisionTicks() != 2) {
      live.step();
    }
    int slot = -1;
    PlayerCommands.Giving giving = null;
    for (int k = 0; k < Schema.HAND_SLOTS && (giving == null || !giving.given()); k++) {
      giving = live.play(0, k, Schema.gameX(0, 9), Schema.gameY(0, 8), "screen" + k);
      slot = k;
    }
    assertThat(giving.given()).isTrue();
    assertThat(giving.runTick()).isEqualTo(live.tick() + Schema.PLAY_DELAY_TICKS);
    while (!live.atDecision()) {
      live.step();
    }
    int[] seat0 = live.observation(0);
    assertThat(seat0[Schema.H_PENDING_COUNT]).isEqualTo(1);
    assertThat(seat0[Schema.H_HAND_PENDING + slot]).isEqualTo(1);
    assertThat(seat0[Schema.H_PENDING]).isEqualTo(seat0[Schema.H_HAND_CARD + slot]);
  }

  @Test
  void aSeatActsOnlyAsADecisionOpens() {
    LiveBattle live = new LiveBattle(tables, vocabulary, EnvConfig.DEFAULT);
    assertThat(live.atDecision()).isFalse();
    live.reset(1, BASIC, BASIC, PLAIN, PLAIN);
    assertThat(live.atDecision()).isTrue();
    assertThat(live.act(1, Schema.WAIT)).isEqualTo(Schema.REFUSAL_NONE);
    live.step();
    assertThatThrownBy(() -> live.act(1, Schema.WAIT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("1 into one");
  }

  @Test
  void anActionTheMaskClosesIsRefusedAndShown() {
    LiveBattle live = new LiveBattle(tables, vocabulary, EnvConfig.DEFAULT);
    live.reset(1, BASIC, BASIC, PLAIN, PLAIN);
    // Before the start cooldown every play is closed.
    assertThat(live.mask(1)[1]).isZero();
    assertThat(live.act(1, 1)).isEqualTo(Schema.REFUSAL_MASKED);
    while (!live.step()) {
      // to the decision's end
    }
    assertThat(live.observation(1)[Schema.H_ILLEGAL]).isEqualTo(1);
    assertThat(live.observation(1)[Schema.H_LAST_REFUSAL]).isEqualTo(Schema.REFUSAL_MASKED);
  }

  @Test
  void aDeckTheTablesDoNotHoldIsRefused() {
    LiveBattle live = new LiveBattle(tables, vocabulary, EnvConfig.DEFAULT);
    List<String> unknown =
        Arrays.asList(
            "NoSuchCard", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap");
    assertThatThrownBy(() -> live.reset(1, unknown, BASIC, PLAIN, PLAIN))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NoSuchCard");
  }
}
