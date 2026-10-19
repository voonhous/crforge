/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.env;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.PlayerCommands;
import org.crforge.core.battle.unit.Standard1v1Battle;

/**
 * A Ladder environment's battle stepped one tick at a time, for a seat that acts once a decision,
 * such as a trained model, against a player who plays on any tick, such as a person at the debug
 * visualizer.
 *
 * <p>The battle is the {@link LadderEnv}'s, built and observed as in training: the same seed,
 * player data and slot flags at its reset, every tick followed as {@link LadderEnv#step} follows
 * it, and each seat's observation and mask written after each decision of {@link
 * EnvConfig#decisionTicks} ticks. A decision opens before its first tick: a seat's action of it
 * ({@link #act}) is given then, through the player's commands, as {@link LadderEnv#step} gives it,
 * so it runs {@link Schema#PLAY_DELAY_TICKS} after its stamp. A player's play ({@link #play}) is
 * given between any two ticks through the same commands and kept by the environment as its own
 * plays are, so the seat's observation reads it as it reads an opponent's play in training.
 */
public final class LiveBattle {

  private final LadderEnv env;
  private final EnvConfig config;
  private final IntBuffer observations;
  private final ByteBuffer masks;

  /** Ticks stepped in the decision under way; 0 before its first. */
  private int ticksIntoDecision;

  /** Whether the decision under way has been opened ({@link LadderEnv#beginDecision}). */
  private boolean decisionOpen;

  /** Whether an episode has been reset. */
  private boolean started;

  /**
   * A live battle on a data version's tables.
   *
   * @param tables the game tables
   * @param vocabulary the vocabulary of those tables
   * @param config how the battle is played: the decision interval, the levels
   */
  public LiveBattle(GameTables tables, Vocabulary vocabulary, EnvConfig config) {
    this.config = config;
    this.env = new LadderEnv(tables, vocabulary, config);
    observations = IntBuffer.allocate(2 * Schema.SEAT_INTS);
    masks = ByteBuffer.allocate(2 * Schema.ACTIONS).order(ByteOrder.nativeOrder());
    env.bind(observations, masks, FloatBuffer.allocate(2), IntBuffer.allocate(Schema.INFO_INTS));
  }

  /**
   * Starts a battle, as a vector's reset starts an episode.
   *
   * @param seed the battle's seed
   * @param deck0 side 0's deck, by card row name
   * @param deck1 side 1's deck, by card row name
   * @param slots0 side 0's slot flags by deck index
   * @param slots1 side 1's slot flags by deck index
   * @throws IllegalArgumentException for a deck the tables do not hold or bad slot flags
   */
  public void reset(int seed, List<String> deck0, List<String> deck1, int[] slots0, int[] slots1) {
    env.checkDeck(deck0, slots0);
    env.checkDeck(deck1, slots1);
    env.reset(seed, deck0, deck1, slots0, slots1);
    ticksIntoDecision = 0;
    decisionOpen = false;
    started = true;
  }

  /** The battle. */
  public Standard1v1Battle battle() {
    return env.battle();
  }

  /**
   * The player's commands of the battle, the ones every play of this battle goes through, so that a
   * screen that offers plays reads the same promises (plays waiting to run, elixir held back).
   */
  public PlayerCommands commands() {
    return env.commands();
  }

  /** How the battle is played. */
  public EnvConfig config() {
    return config;
  }

  /** The battle's count of completed steps. */
  public int tick() {
    return env.battle().getBattle().getTick();
  }

  /** Whether the episode is over: the match ended, or the tick limit cut it. */
  public boolean done() {
    return env.done();
  }

  /**
   * Whether a decision opens before the next tick: the seats' observations are those of the
   * decision before, and an action given now ({@link #act}) is the decision's.
   */
  public boolean atDecision() {
    return started && !env.done() && ticksIntoDecision == 0;
  }

  /**
   * A seat's observation as the environment last wrote it, after the last decision: {@link
   * Schema#SEAT_INTS} integers in the seat's frame.
   *
   * @param seat 0 or 1
   */
  public int[] observation(int seat) {
    int[] copy = new int[Schema.SEAT_INTS];
    observations.get(seat * Schema.SEAT_INTS, copy);
    return copy;
  }

  /**
   * A seat's legality mask as the environment last wrote it: {@link Schema#ACTIONS} values, 1 for
   * an action the seat may take.
   *
   * @param seat 0 or 1
   */
  public byte[] mask(int seat) {
    byte[] copy = new byte[Schema.ACTIONS];
    masks.get(seat * Schema.ACTIONS, copy);
    return copy;
  }

  /**
   * Gives a seat's action of the decision that opens now, as {@link LadderEnv#step} gives it: a
   * wait, a play of a hand slot's card on a tile of the seat's frame, or a champion slot's ability.
   * An action the seat's mask closed, or one the player's commands refuse, is taken as a wait and
   * shown in the seat's next observation as illegal, with its refusal.
   *
   * @param seat 0 or 1
   * @param action the action, 0 to {@link Schema#ACTIONS} - 1
   * @return the seat's refusal code, {@link Schema#REFUSAL_NONE} for an action given or a wait
   * @throws IllegalStateException when no decision opens now ({@link #atDecision})
   */
  public int act(int seat, int action) {
    if (!atDecision()) {
      throw new IllegalStateException(
          "a seat acts as a decision opens, every "
              + config.decisionTicks()
              + " ticks; tick "
              + tick()
              + " is "
              + ticksIntoDecision
              + " into one");
    }
    openDecision();
    env.apply(seat, action);
    return env.lastRefusal(seat);
  }

  /**
   * Gives a play of a hand slot's card dropped at a point, between two ticks, as a player does:
   * through the battle's player's commands, at the card's level. The environment keeps a play given
   * as it keeps its seats' plays.
   *
   * @param side the playing side
   * @param slot the hand slot, 0 to 3
   * @param x the dropped point in game units
   * @param y the dropped point in game units
   * @param name the play's name, new to the battle
   * @return what the giving came to
   * @throws UnsupportedOperationException for a giving the battle core does not model
   */
  public PlayerCommands.Giving play(int side, int slot, int x, int y, String name) {
    if (env.done()) {
      throw new IllegalStateException("the battle is over");
    }
    return env.givePlay(side, slot, x, y, name);
  }

  /**
   * Steps the battle one tick. The tick that opens a decision opens it (when no seat acted in it)
   * and the tick that ends it, or ends the match, writes the seats' observations after it.
   *
   * @return true when the tick ended a decision
   * @throws IllegalStateException when the episode is over
   */
  public boolean step() {
    if (!started || env.done()) {
      throw new IllegalStateException("the battle is over: reset it");
    }
    openDecision();
    env.advance();
    ticksIntoDecision++;
    if (ticksIntoDecision >= config.decisionTicks() || env.over()) {
      env.endDecision();
      ticksIntoDecision = 0;
      decisionOpen = false;
      return true;
    }
    return false;
  }

  private void openDecision() {
    if (!decisionOpen) {
      env.beginDecision();
      decisionOpen = true;
    }
  }
}
