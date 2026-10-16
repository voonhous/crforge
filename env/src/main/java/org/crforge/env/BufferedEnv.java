/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * What every environment shares: its views of the vector's buffers, the scratch arrays it builds a
 * seat's observation and mask in, and the end of an episode by a fault.
 *
 * <p>An environment writes a seat's observation and mask into its scratch arrays, {@link
 * #seatScratch} and {@link #maskScratch}, and copies each whole into the buffers with one bulk put
 * ({@link #flushSeat}, {@link #flushMask}). A direct buffer's element put is a bounds-checked
 * memory access each; an array store and one copy of ten kilobytes cost a fraction of that.
 */
public abstract class BufferedEnv implements BattleEnv {

  /** Both seats' observations, {@link Schema#SEAT_INTS} each, seat 0 first. */
  protected IntBuffer observations;

  /** Both seats' legality masks, {@link Schema#ACTIONS} bytes each, seat 0 first. */
  protected ByteBuffer masks;

  /** Both seats' rewards, as floats. */
  protected FloatBuffer rewards;

  /** The environment's info record, {@link Schema#INFO_INTS} integers. */
  protected IntBuffer info;

  /** One seat's observation under construction, zeroed by {@link #clearSeatScratch}. */
  protected final int[] seatScratch = new int[Schema.SEAT_INTS];

  /** One seat's mask under construction, zeroed by {@link #clearMaskScratch}. */
  protected final byte[] maskScratch = new byte[Schema.ACTIONS];

  /** The fault that ended the current episode, or null. */
  private EnvFault fault;

  @Override
  public final void bind(
      IntBuffer observations, ByteBuffer masks, FloatBuffer rewards, IntBuffer info) {
    this.observations = observations;
    this.masks = masks;
    this.rewards = rewards;
    this.info = info;
  }

  @Override
  public final EnvFault fault() {
    return fault;
  }

  /** Forgets the last episode's fault, as a reset starts. */
  protected final void clearFault() {
    fault = null;
  }

  @Override
  public final void fail(Throwable thrown) {
    int tick = faultTick();
    int step = faultEpisodeStep();
    fault = EnvFault.of(thrown, tick);
    ended();
    clear();
    for (int seat = 0; seat < 2; seat++) {
      masks.put(seat * Schema.ACTIONS + Schema.WAIT, (byte) 1);
    }
    info.put(Schema.I_TICK, Math.max(tick, 0));
    info.put(Schema.I_EPISODE_STEP, step);
    info.put(Schema.I_WINNER, Schema.WINNER_NONE);
    info.put(Schema.I_FAULT, fault.kind().code());
    info.put(Schema.I_TRUNCATED, 1);
  }

  /** The battle's tick counter for a fault's record, or -1 when there is no battle. */
  protected abstract int faultTick();

  /** The decisions taken in the episode, for a fault's record. */
  protected abstract int faultEpisodeStep();

  /** Marks the episode over, for a fault: {@link #done()} answers true from then on. */
  protected abstract void ended();

  /** Zeroes the seat scratch. */
  protected final void clearSeatScratch() {
    Arrays.fill(seatScratch, 0);
  }

  /** Zeroes the mask scratch. */
  protected final void clearMaskScratch() {
    Arrays.fill(maskScratch, (byte) 0);
  }

  /** Copies the seat scratch into a seat's observation. */
  protected final void flushSeat(int seat) {
    observations.put(seat * Schema.SEAT_INTS, seatScratch, 0, Schema.SEAT_INTS);
  }

  /** Copies the mask scratch into a seat's mask. */
  protected final void flushMask(int seat) {
    masks.put(seat * Schema.ACTIONS, maskScratch, 0, Schema.ACTIONS);
  }

  /** Whether a seat's mask, as last flushed, has an action open. */
  protected final boolean open(int seat, int action) {
    return masks.get(seat * Schema.ACTIONS + action) != 0;
  }

  /** Clears everything the environment writes: the observations, masks, rewards and info. */
  protected final void clear() {
    clearSeatScratch();
    clearMaskScratch();
    for (int seat = 0; seat < 2; seat++) {
      flushSeat(seat);
      flushMask(seat);
      rewards.put(seat, 0f);
    }
    for (int i = 0; i < info.limit(); i++) {
      info.put(i, 0);
    }
  }
}
