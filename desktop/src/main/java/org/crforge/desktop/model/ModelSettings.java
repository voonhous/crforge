/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.model;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.crforge.env.Schema;

/**
 * How the debug visualizer plays a model opponent, read from the program's arguments.
 *
 * <ul>
 *   <li>{@code --model <file>}: the policy, an ONNX file exported from a checkpoint; it is passed
 *       at run time only and never kept in this repository
 *   <li>{@code --model-side 0|1}: the side the model plays, 1 (red, the top) by default
 *   <li>{@code --model-deck <8 card row names, comma separated>} and {@code --model-slots <8
 *       flags>}: the model's deck and its slot flags (evolution 1, hero 2), by default the deck the
 *       model file names, if any. Play the deck the model was trained on
 *   <li>{@code --player-deck} and {@code --player-slots}: the other side's, by default the
 *       visualizer's deck of that side, every card plain
 *   <li>{@code --level <n>} and {@code --tower-level <n>}: the card and tower levels of both sides,
 *       by default those the model file names
 *   <li>{@code --model-sample}: draw each choice from the policy's probabilities rather than take
 *       the most likely
 *   <li>{@code --seed <n>}: the first battle's seed, 0 by default; each new battle ({@code R})
 *       takes the next
 * </ul>
 *
 * @param model the model file
 * @param side the side the model plays
 * @param deck the model's deck, or null for the one the model file names
 * @param slots the model's slot flags, or null for those the model file names (or all plain)
 * @param playerDeck the other side's deck, or null for the visualizer's
 * @param playerSlots the other side's slot flags, or null for all plain
 * @param level the card level, or 0 for the model's
 * @param towerLevel the tower level, or 0 for the model's
 * @param sample whether the choices are drawn rather than the most likely taken
 * @param seed the first battle's seed
 */
public record ModelSettings(
    Path model,
    int side,
    List<String> deck,
    int[] slots,
    List<String> playerDeck,
    int[] playerSlots,
    int level,
    int towerLevel,
    boolean sample,
    int seed) {

  /** The argument that names the model file. */
  public static final String MODEL = "--model";

  /**
   * The settings the arguments give.
   *
   * @param args the program's arguments
   * @return the settings, or empty when no model is named
   * @throws IllegalArgumentException for an argument without its value, or a value out of range
   */
  public static Optional<ModelSettings> parse(String[] args) {
    Path model = null;
    int side = 1;
    List<String> deck = null;
    int[] slots = null;
    List<String> playerDeck = null;
    int[] playerSlots = null;
    int level = 0;
    int towerLevel = 0;
    boolean sample = false;
    int seed = 0;
    for (int i = 0; i < args.length; i++) {
      String name = args[i];
      switch (name) {
        case MODEL -> model = Paths.get(value(args, ++i, name));
        case "--model-side" -> {
          side = integer(args, ++i, name);
          if (side != 0 && side != 1) {
            throw new IllegalArgumentException(name + " is 0 or 1, got " + side);
          }
        }
        case "--model-deck" -> deck = cards(value(args, ++i, name), name);
        case "--model-slots" -> slots = flags(value(args, ++i, name), name);
        case "--player-deck" -> playerDeck = cards(value(args, ++i, name), name);
        case "--player-slots" -> playerSlots = flags(value(args, ++i, name), name);
        case "--level" -> level = positive(args, ++i, name);
        case "--tower-level" -> towerLevel = positive(args, ++i, name);
        case "--model-sample" -> sample = true;
        case "--seed" -> seed = integer(args, ++i, name);
        default -> {
          // another program argument, such as --replay
        }
      }
    }
    if (model == null) {
      List<String> named =
          List.of(
              "--model-side",
              "--model-deck",
              "--model-slots",
              "--player-deck",
              "--player-slots",
              "--level",
              "--tower-level",
              "--model-sample",
              "--seed");
      for (String arg : args) {
        if (named.contains(arg)) {
          throw new IllegalArgumentException(arg + " plays a model opponent: name it with --model");
        }
      }
      return Optional.empty();
    }
    return Optional.of(
        new ModelSettings(
            model, side, deck, slots, playerDeck, playerSlots, level, towerLevel, sample, seed));
  }

  private static String value(String[] args, int at, String name) {
    if (at >= args.length || args[at].isBlank() || args[at].startsWith("--")) {
      throw new IllegalArgumentException(name + " needs a value");
    }
    return args[at];
  }

  private static int integer(String[] args, int at, String name) {
    String text = value(args, at, name);
    try {
      return Integer.parseInt(text.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(name + " is a whole number, got " + text);
    }
  }

  private static int positive(String[] args, int at, String name) {
    int n = integer(args, at, name);
    if (n < 1) {
      throw new IllegalArgumentException(name + " is counted from 1, got " + n);
    }
    return n;
  }

  /** A deck of card row names separated by commas. */
  static List<String> cards(String text, String name) {
    List<String> cards = new ArrayList<>();
    for (String card : text.split(",")) {
      if (!card.isBlank()) {
        cards.add(card.trim());
      }
    }
    if (cards.size() != Schema.DECK_SIZE) {
      throw new IllegalArgumentException(
          name + " is " + Schema.DECK_SIZE + " card row names, got " + cards.size() + ": " + text);
    }
    return List.copyOf(cards);
  }

  /** A deck's slot flags separated by commas. */
  static int[] flags(String text, String name) {
    String[] parts = text.split(",");
    if (parts.length != Schema.DECK_SIZE) {
      throw new IllegalArgumentException(
          name + " is " + Schema.DECK_SIZE + " slot flags, got " + parts.length + ": " + text);
    }
    int[] flags = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      try {
        flags[i] = Integer.parseInt(parts[i].trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(name + " holds a flag that is not a number: " + text);
      }
    }
    return flags;
  }

  /** The settings without the model file's path, for the screen's status. */
  @Override
  public String toString() {
    return "model "
        + model.getFileName()
        + " on side "
        + side
        + (sample ? ", sampled" : ", most likely")
        + (deck == null ? "" : ", deck " + deck + " " + Arrays.toString(slots));
  }
}
