/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.model;

import java.util.List;
import java.util.Random;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.Hand;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.desktop.battle.BattleDecks;
import org.crforge.env.EnvConfig;
import org.crforge.env.LiveBattle;
import org.crforge.env.Schema;
import org.crforge.env.Vocabulary;

/**
 * A trained model playing one side of a Ladder battle, the other side played from the screen.
 *
 * <p>The battle is the environment's ({@link LiveBattle}): set up as in training, with the model's
 * decision interval, mask rule and levels, and the model's observation and mask written by the
 * environment's own code after each decision, so the model reads exactly what it read in training.
 * As each decision opens (every {@link EnvConfig#decisionTicks} ticks, 5 for the models trained so
 * far, four a second), the model is run on its side's observation and mask ({@link ModelPolicy}),
 * and its action is given through the environment as in training: a play goes through the battle's
 * player's commands, waits {@link Schema#PLAY_DELAY_TICKS} ticks and runs against the battle as it
 * stands then, with the match's gates.
 */
public final class ModelOpponent {

  private final ModelPolicy policy;
  private final ModelSettings settings;
  private final LiveBattle live;
  private final Random random;
  private final List<String> modelDeck;

  /** The last decision the model took, or null before its first. */
  private ModelPolicy.Decision last;

  private int decisions;
  private int plays;
  private int refused;

  /**
   * What the model did as a decision opened.
   *
   * @param decision the policy's decision
   * @param card the card of the hand slot it played, or null for a wait or an ability
   * @param refusal the environment's refusal code, {@link Schema#REFUSAL_NONE} when given
   * @param command the battle's command it gave, or null
   */
  public record Turn(
      ModelPolicy.Decision decision, String card, int refusal, Standard1v1Battle.Command command) {}

  private ModelOpponent(
      ModelPolicy policy, ModelSettings settings, LiveBattle live, List<String> modelDeck) {
    this.policy = policy;
    this.settings = settings;
    this.live = live;
    this.modelDeck = modelDeck;
    this.random = settings.sample() ? new Random(settings.seed()) : null;
  }

  /**
   * A new battle against a model, on a data version's tables: the model checked against them
   * ({@link ModelPolicy#check}), the decks checked, and the battle reset.
   *
   * @param policy the model
   * @param settings how it is played
   * @param tables the tables
   * @param battleIndex counts the battles started, so each new one takes the next seed
   * @throws IllegalArgumentException when the model does not fit the tables or a deck is refused
   */
  public static ModelOpponent start(
      ModelPolicy policy, ModelSettings settings, GameTables tables, int battleIndex) {
    return start(policy, settings, tables, battleIndex, true);
  }

  /**
   * As {@link #start(ModelPolicy, ModelSettings, GameTables, int)}; {@code checked} false skips the
   * model's check against the tables, for a test's network that was trained on none.
   */
  static ModelOpponent start(
      ModelPolicy policy,
      ModelSettings settings,
      GameTables tables,
      int battleIndex,
      boolean checked) {
    Vocabulary vocabulary = new Vocabulary(tables);
    EnvConfig config = policy.config(settings.level(), settings.towerLevel());
    if (checked) {
      policy.check(vocabulary, config);
    }
    List<String> deck = settings.deck();
    int[] slots = settings.slots();
    if (deck == null) {
      String named = policy.property("deck");
      if (named == null) {
        throw new IllegalArgumentException(
            policy.source()
                + " names no deck: give the deck the model was trained on with --model-deck"
                + " (and its slot flags with --model-slots)");
      }
      deck = ModelSettings.cards(named, "the model file's deck");
      if (slots == null && policy.property("deck_slots") != null) {
        slots = ModelSettings.flags(policy.property("deck_slots"), "the model file's deck slots");
      }
    }
    if (slots == null) {
      slots = new int[Schema.DECK_SIZE];
    }
    int side = settings.side();
    List<String> player = settings.playerDeck();
    if (player == null) {
      player = 1 - side == 0 ? BattleDecks.BLUE : BattleDecks.RED;
    }
    int[] playerSlots =
        settings.playerSlots() == null ? new int[Schema.DECK_SIZE] : settings.playerSlots();
    LiveBattle live = new LiveBattle(tables, vocabulary, config);
    int seed = settings.seed() + battleIndex;
    if (side == 0) {
      live.reset(seed, deck, player, slots, playerSlots);
    } else {
      live.reset(seed, player, deck, playerSlots, slots);
    }
    return new ModelOpponent(policy, settings, live, deck);
  }

  /** The environment's battle. */
  public LiveBattle live() {
    return live;
  }

  /** The side the model plays. */
  public int side() {
    return settings.side();
  }

  /** The model. */
  public ModelPolicy policy() {
    return policy;
  }

  /** The model's deck. */
  public List<String> deck() {
    return modelDeck;
  }

  /** The last decision the model took, or null before its first. */
  public ModelPolicy.Decision last() {
    return last;
  }

  /** The decisions taken. */
  public int decisions() {
    return decisions;
  }

  /** The plays and abilities the model gave. */
  public int plays() {
    return plays;
  }

  /** The model's actions the environment refused (taken as waits). */
  public int refused() {
    return refused;
  }

  /** Whether a decision opens before the next tick. */
  public boolean atDecision() {
    return live.atDecision();
  }

  /**
   * Runs the model on its side's observation and mask as the decision opens, and gives its action.
   *
   * @return what it did
   * @throws IllegalStateException when no decision opens now
   */
  public Turn act() {
    int side = side();
    int[] other = live.observation(1 - side);
    ModelPolicy.Decision decision =
        policy.decide(live.observation(side), live.mask(side), other, random);
    last = decision;
    decisions++;
    int action = decision.action();
    String card = null;
    if (action != Schema.WAIT && !Schema.isAbility(action)) {
      int slot = (action - 1) / Schema.TILES;
      card = handCard(side, slot);
    }
    List<Standard1v1Battle.Command> before = live.battle().getCommands();
    int count = before.size();
    int refusal = live.act(side, action);
    List<Standard1v1Battle.Command> after = live.battle().getCommands();
    Standard1v1Battle.Command command = after.size() > count ? after.get(after.size() - 1) : null;
    if (action != Schema.WAIT) {
      if (refusal == Schema.REFUSAL_NONE) {
        plays++;
      } else {
        refused++;
      }
    }
    return new Turn(decision, card, refusal, command);
  }

  private String handCard(int side, int slot) {
    MatchSide own = live.battle().getMatch().side(side);
    int index = own.getHand().slots()[slot];
    return index == Hand.EMPTY ? null : own.deck().get(index).name();
  }
}
