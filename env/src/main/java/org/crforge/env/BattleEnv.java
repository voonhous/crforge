/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;

/**
 * One environment of a {@link VectorEnv}: a battle between two seats, written into its share of the
 * vector's buffers. An environment owns no buffer: the vector hands it views at {@link #bind} and
 * reads the results back from them. Nothing an environment does touches another, so the vector may
 * step its environments on several threads at once.
 *
 * <p>The vector calls {@link #reset} and {@link #step} inside its fault boundary: an exception from
 * either is caught there and handed back through {@link #fail}, which ends the episode with no
 * result, so one environment's fault never stops the others.
 */
public interface BattleEnv {

  /**
   * Gives the environment its views of the vector's buffers, before anything else.
   *
   * @param observations both seats' observations, {@link Schema#SEAT_INTS} each, seat 0 first
   * @param masks both seats' legality masks, {@link Schema#ACTIONS} bytes each, seat 0 first
   * @param rewards both seats' rewards
   * @param info the environment's info record, {@link Schema#INFO_INTS} integers
   */
  void bind(IntBuffer observations, ByteBuffer masks, FloatBuffer rewards, IntBuffer info);

  /**
   * Refuses a deck this environment cannot play, before any environment of the vector is reset: the
   * check touches no state.
   *
   * @param deck a side's deck, by card row name
   * @param slots its slot flags, one per card ({@link Schema#SLOT_EVOLUTION}, {@link
   *     Schema#SLOT_HERO}, both or neither)
   * @throws IllegalArgumentException naming what is wrong with it
   */
  void checkDeck(List<String> deck, int[] slots);

  /**
   * Starts an episode and writes its first observation.
   *
   * @param seed the battle's random seed
   * @param deck0 side 0's deck, by card row name
   * @param deck1 side 1's deck, by card row name
   * @param slots0 side 0's slot flags, one per card
   * @param slots1 side 1's slot flags, one per card
   */
  void reset(int seed, List<String> deck0, List<String> deck1, int[] slots0, int[] slots1);

  /**
   * Takes both seats' actions, advances the battle one decision and writes the observation after
   * it, with the rewards and the info record.
   *
   * @param action0 seat 0's action
   * @param action1 seat 1's action
   */
  void step(int action0, int action1);

  /** Whether the episode is over, after a step or a reset. */
  boolean done();

  /**
   * Ends the episode for a fault caught at the vector's boundary: done with no result, truncated,
   * the fault's kind in the info record, both masks the wait alone and the observations cleared.
   *
   * @param thrown the exception caught
   */
  void fail(Throwable thrown);

  /** The fault that ended the current episode, or null. */
  EnvFault fault();
}
