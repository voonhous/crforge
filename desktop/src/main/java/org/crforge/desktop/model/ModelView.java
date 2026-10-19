/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.crforge.core.util.GameUnits;
import org.crforge.env.Schema;

/**
 * What the screen shows of a model's last decision: the tile probabilities of one hand slot on the
 * battle's tiles, for a heatmap, and its first choice's probabilities and win estimate as lines.
 *
 * <p>The slot shown is the one the model played, or when it waited or used an ability its most
 * likely slot. The model's tiles are those of its seat's frame; each is drawn on the battle tile
 * its centre falls on, so the heatmap lies where the model's plays land on screen, whichever side
 * it plays.
 *
 * @param side the side the model plays
 * @param slot the hand slot shown, or -1 when no slot was open
 * @param card the card of that slot when the model decided, or null
 * @param played whether the model played that slot
 * @param tiles each battle tile's probability, {@code row * }{@link #COLS}{@code + col} in the
 *     battle's columns and rows; null when no slot was open
 * @param chosen the battle tile the model played on, or -1
 * @param lines the probabilities and the estimate as text
 */
public record ModelView(
    int side,
    int slot,
    String card,
    boolean played,
    float[] tiles,
    int chosen,
    List<String> lines) {

  /** The battle's tiles across. */
  public static final int COLS = Schema.COLS;

  /** The battle's tiles along. */
  public static final int ROWS = Schema.ROWS;

  /**
   * The view of a decision.
   *
   * @param decision the model's decision
   * @param side the side the model plays
   * @param hand the cards of the side's hand slots when it decided (null for an empty slot)
   * @param sideName the name the screen gives the side
   * @param readsOther whether the model's win estimate reads the other side's header (a privileged
   *     critic of training), which its player does not see
   */
  public static ModelView of(
      ModelPolicy.Decision decision, int side, String[] hand, String sideName, boolean readsOther) {
    int action = decision.action();
    float[] first = decision.first();
    boolean played = action != Schema.WAIT && !Schema.isAbility(action);
    int slot = played ? (action - 1) / Schema.TILES : mostLikelySlot(first);
    float[] tiles = null;
    int chosen = -1;
    if (slot >= 0) {
      tiles = new float[COLS * ROWS];
      float[] logp = decision.tiles()[slot];
      for (int t = 0; t < Schema.TILES; t++) {
        if (logp[t] > ModelPolicy.CLOSED) {
          tiles[battleTile(side, t)] += (float) Math.exp(logp[t]);
        }
      }
      if (played) {
        chosen = battleTile(side, (action - 1) % Schema.TILES);
      }
    }
    String card = slot >= 0 && slot < hand.length ? hand[slot] : null;
    return new ModelView(
        side,
        slot,
        card,
        played,
        tiles,
        chosen,
        lines(decision, hand, sideName, slot, card, played, readsOther));
  }

  /** The most likely hand slot of a first choice, or -1 when every slot is closed. */
  static int mostLikelySlot(float[] first) {
    int best = -1;
    for (int k = 0; k < Schema.HAND_SLOTS; k++) {
      float logp = first[1 + k];
      if (logp > ModelPolicy.CLOSED && (best < 0 || logp > first[1 + best])) {
        best = k;
      }
    }
    return best;
  }

  /** The battle tile a tile of a seat's frame has its centre on. */
  static int battleTile(int side, int seatTile) {
    int x = Schema.gameX(side, seatTile % Schema.COLS);
    int y = Schema.gameY(side, seatTile / Schema.COLS);
    return (y / GameUnits.UNITS_PER_TILE) * COLS + x / GameUnits.UNITS_PER_TILE;
  }

  /** A tile's probability, 0 for one off the battle or with no slot shown. */
  public float probability(int col, int row) {
    if (tiles == null || col < 0 || row < 0 || col >= COLS || row >= ROWS) {
      return 0f;
    }
    return tiles[row * COLS + col];
  }

  /** The largest tile probability, 0 with no slot shown. */
  public float largest() {
    float most = 0f;
    if (tiles != null) {
      for (float p : tiles) {
        most = Math.max(most, p);
      }
    }
    return most;
  }

  private static List<String> lines(
      ModelPolicy.Decision decision,
      String[] hand,
      String sideName,
      int slot,
      String card,
      boolean played,
      boolean readsOther) {
    float[] first = decision.first();
    List<String> lines = new ArrayList<>();
    StringBuilder choices =
        new StringBuilder("model (" + sideName + "): wait " + percent(first[0]));
    for (int k = 0; k < Schema.HAND_SLOTS; k++) {
      String name = k < hand.length && hand[k] != null ? hand[k] : "slot " + (k + 1);
      choices.append(", ").append(name).append(' ').append(percent(first[1 + k]));
    }
    for (int k = 0; k < Schema.ABILITY_SLOTS; k++) {
      int at = 1 + Schema.HAND_SLOTS + k;
      if (at < first.length && first[at] > ModelPolicy.CLOSED) {
        choices.append(", ability ").append(k + 1).append(' ').append(percent(first[at]));
      }
    }
    lines.add(choices.toString());
    if (Float.isNaN(decision.winLogit())) {
      lines.add(
          String.format(
              Locale.ROOT, "model value %.2f (the model has no win estimate)", decision.value()));
    } else {
      double win = 1.0 / (1.0 + Math.exp(-decision.winLogit()));
      lines.add(
          String.format(
              Locale.ROOT,
              "model win estimate %.0f%% (logit %.2f%s), value %.2f",
              100 * win,
              decision.winLogit(),
              readsOther ? ", reading both sides' hands" : "",
              decision.value()));
    }
    if (slot >= 0) {
      lines.add(
          "model tiles: "
              + (card == null ? "slot " + (slot + 1) : card)
              + (played ? ", played" : ", its most likely card")
              + " (K shows or hides)");
    }
    return List.copyOf(lines);
  }

  /** A log-probability as a percentage, "-" for a closed choice. */
  private static String percent(float logp) {
    if (logp <= ModelPolicy.CLOSED) {
      return "-";
    }
    return String.format(Locale.ROOT, "%.0f%%", 100 * Math.exp(logp));
  }
}
