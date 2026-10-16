/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import java.util.List;
import java.util.SplittableRandom;
import org.crforge.env.EnvFault.CoreFailure;

/**
 * An environment that is no battle: it writes observations and masks of the schema's full size from
 * a pseudo-random source, so the bridge between the vector environment and Python can be measured
 * and tested without the game tables. An episode is a fixed number of decisions; the rewards are 0
 * until the last, where seat 0 wins. Nothing it writes means anything about the game.
 *
 * <p>It may be told to fail at a decision of every episode with a fault of a given kind, so the
 * vector's fault boundary can be shown to work.
 */
public final class SyntheticEnv extends BufferedEnv {

  /** The vocabulary size the synthetic ids are drawn below. */
  public static final int VOCABULARY_SIZE = 512;

  private final int entities;
  private final int episodeSteps;
  private final int faultAtStep;
  private final EnvFault.Kind faultKind;
  private SplittableRandom random = new SplittableRandom(0);
  private int step;
  private boolean done;

  /**
   * A synthetic environment that never fails.
   *
   * @param entities entity rows written each step, at most {@link Schema#MAX_ENTITIES}
   * @param episodeSteps decisions in an episode
   */
  public SyntheticEnv(int entities, int episodeSteps) {
    this(entities, episodeSteps, -1, EnvFault.Kind.BUG);
  }

  /**
   * A synthetic environment that fails at one decision of every episode.
   *
   * @param entities entity rows written each step, at most {@link Schema#MAX_ENTITIES}
   * @param episodeSteps decisions in an episode
   * @param faultAtStep the decision, counted from 1, whose step throws; -1 for none
   * @param faultKind the kind of fault it throws: as the battle core's refusal, as its bug, or as
   *     the environment's own
   */
  public SyntheticEnv(int entities, int episodeSteps, int faultAtStep, EnvFault.Kind faultKind) {
    if (entities < 0 || entities > Schema.MAX_ENTITIES) {
      throw new IllegalArgumentException("entity rows: " + entities);
    }
    if (episodeSteps < 1) {
      throw new IllegalArgumentException("an episode is at least one decision");
    }
    this.entities = entities;
    this.episodeSteps = episodeSteps;
    this.faultAtStep = faultAtStep;
    this.faultKind = faultKind;
  }

  @Override
  public void checkDeck(List<String> deck) {
    if (deck.size() != Schema.DECK_SIZE) {
      throw new IllegalArgumentException(
          "a deck of " + Schema.DECK_SIZE + " cards, got " + deck.size());
    }
    if (deck.contains(null)) {
      throw new IllegalArgumentException("a deck with a card of no name");
    }
  }

  @Override
  public void reset(int seed, List<String> deck0, List<String> deck1) {
    clearFault();
    random = new SplittableRandom(seed);
    step = 0;
    done = false;
    clear();
    observe();
  }

  @Override
  public void step(int action0, int action1) {
    if (done) {
      throw new IllegalStateException("the episode is over: reset the environment");
    }
    step++;
    if (step == faultAtStep) {
      throw switch (faultKind) {
        case REFUSAL ->
            new CoreFailure(new UnsupportedOperationException("a forced refusal at " + step));
        case BUG -> new CoreFailure(new IllegalStateException("a forced bug at " + step));
        case ENV_BUG -> new IllegalStateException("a forced environment bug at " + step);
      };
    }
    done = step >= episodeSteps;
    observe();
    info.put(Schema.I_ILLEGAL_0, action0 < 0 || action0 >= Schema.ACTIONS ? 1 : 0);
    info.put(Schema.I_ILLEGAL_1, action1 < 0 || action1 >= Schema.ACTIONS ? 1 : 0);
  }

  @Override
  public boolean done() {
    return done;
  }

  @Override
  protected int faultTick() {
    return step * 5;
  }

  @Override
  protected int faultEpisodeStep() {
    return step;
  }

  @Override
  protected void ended() {
    done = true;
  }

  private void observe() {
    for (int seat = 0; seat < 2; seat++) {
      int[] o = seatScratch;
      clearSeatScratch();
      o[Schema.H_TICK] = step * 5;
      o[Schema.H_RESULT] = done ? (seat == 0 ? Schema.WON : Schema.LOST) : Schema.RUNNING;
      o[Schema.H_ELIXIR] = random.nextInt(100_001);
      o[Schema.H_ENTITY_COUNT] = entities;
      o[Schema.H_SEAT] = seat;
      o[Schema.H_EPISODE_STEP] = step;
      for (int k = 0; k < Schema.HAND_SLOTS; k++) {
        o[Schema.H_HAND_CARD + k] = random.nextInt(VOCABULARY_SIZE);
      }
      // One draw per row, its bits spread over the fields: the cost is the writing, as in a battle,
      // not the drawing.
      for (int e = 0; e < entities; e++) {
        int at = Schema.HEADER_INTS + e * Schema.ENTITY_INTS;
        long bits = random.nextLong();
        o[at + Schema.E_ID] = e + 1;
        o[at + Schema.E_KIND] = 1 + (int) (bits & 3);
        o[at + Schema.E_SIDE] = (int) ((bits >>> 2) & 1);
        o[at + Schema.E_ROW] = (int) ((bits >>> 3) & (VOCABULARY_SIZE - 1));
        o[at + Schema.E_X] = (int) ((bits >>> 12) & 0x3fff) % Schema.WIDTH_UNITS;
        o[at + Schema.E_Y] = (int) ((bits >>> 26) & 0x7fff) % Schema.HEIGHT_UNITS;
        o[at + Schema.E_HP] = (int) ((bits >>> 41) & 0xfff);
        o[at + Schema.E_MAX_HP] = 5000;
        o[at + Schema.E_SHIELD] = 0;
        o[at + Schema.E_STATE] = (int) ((bits >>> 53) & 7);
        o[at + Schema.E_FLAGS] = Schema.F_ALIVE;
      }
      flushSeat(seat);
      // About a quarter of the actions open, 32 of them decided by one draw of two bits each.
      byte[] m = maskScratch;
      m[Schema.WAIT] = 1;
      long bits = 0;
      for (int a = 1; a < Schema.ACTIONS; a++) {
        if ((a & 31) == 1) {
          bits = random.nextLong();
        }
        int pair = (int) (bits >>> (2 * ((a - 1) & 31))) & 3;
        m[a] = (byte) (pair == 0 ? 1 : 0);
      }
      flushMask(seat);
      rewards.put(seat, done ? (seat == 0 ? 1f : -1f) : 0f);
    }
    info.put(Schema.I_TICK, step * 5);
    info.put(Schema.I_EPISODE_STEP, step);
    info.put(Schema.I_WINNER, done ? 0 : Schema.WINNER_NONE);
    info.put(Schema.I_FAULT, Schema.FAULT_NONE);
    info.put(Schema.I_RESET, 0);
    info.put(Schema.I_TRUNCATED, 0);
  }
}
