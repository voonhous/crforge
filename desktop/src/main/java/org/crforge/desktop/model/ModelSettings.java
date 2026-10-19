/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.model;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
 *   <li>{@code --deck-file <file>}: a deck file ({@link DeckFile}) the two decks below are named
 *       from; read at run time only
 *   <li>{@code --model-deck <deck>}: the model's deck, a deck of the deck file by name with its
 *       slot flags, or 8 card row names separated by commas (with {@code --model-slots <8 flags>},
 *       evolution 1, hero 2; all plain by default). By default the deck the model file names, if
 *       any. Play the deck the model was trained on
 *   <li>{@code --my-deck <deck>}: the screen's side's deck, the same way (with {@code --my-slots}).
 *       By default the model's deck (a mirror match) when a deck file is given, else the
 *       visualizer's deck of that side, every card plain
 *   <li>{@code --level <n>} and {@code --tower-level <n>}: the card and tower levels of both sides,
 *       {@value #LEVEL} by default (the tower level defaults to the card level), the levels the
 *       models are trained and evaluated at
 *   <li>{@code --model-sample}: draw each choice from the policy's probabilities rather than take
 *       the most likely
 *   <li>{@code --seed <n>}: the first battle's seed, 0 by default; each new battle ({@code R})
 *       takes the next
 * </ul>
 *
 * @param model the model file
 * @param side the side the model plays
 * @param deckFile the deck file, or null
 * @param modelDeck the model's deck as given, a deck name or cards separated by commas, or null
 * @param modelSlots the model's slot flags for a deck of cards given, or null
 * @param myDeck the screen's side's deck as given, or null
 * @param mySlots the screen's side's slot flags for a deck of cards given, or null
 * @param level the card level
 * @param towerLevel the tower level
 * @param sample whether the choices are drawn rather than the most likely taken
 * @param seed the first battle's seed
 */
public record ModelSettings(
    Path model,
    int side,
    Path deckFile,
    String modelDeck,
    int[] modelSlots,
    String myDeck,
    int[] mySlots,
    int level,
    int towerLevel,
    boolean sample,
    int seed) {

  /** The argument that names the model file. */
  public static final String MODEL = "--model";

  /** The card and tower level by default: the level the models are trained and evaluated at. */
  public static final int LEVEL = 16;

  /** The arguments of a model opponent besides {@link #MODEL}. */
  private static final List<String> NAMED =
      List.of(
          "--model-side",
          "--deck-file",
          "--model-deck",
          "--model-slots",
          "--my-deck",
          "--my-slots",
          "--level",
          "--tower-level",
          "--model-sample",
          "--seed");

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
    Path deckFile = null;
    String modelDeck = null;
    int[] modelSlots = null;
    String myDeck = null;
    int[] mySlots = null;
    int level = LEVEL;
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
        case "--deck-file" -> deckFile = Paths.get(value(args, ++i, name));
        case "--model-deck" -> modelDeck = value(args, ++i, name);
        case "--model-slots" -> modelSlots = flags(value(args, ++i, name), name);
        case "--my-deck" -> myDeck = value(args, ++i, name);
        case "--my-slots" -> mySlots = flags(value(args, ++i, name), name);
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
      for (String arg : args) {
        if (NAMED.contains(arg)) {
          throw new IllegalArgumentException(arg + " plays a model opponent: name it with --model");
        }
      }
      return Optional.empty();
    }
    checkSlots(modelDeck, modelSlots, "--model");
    checkSlots(myDeck, mySlots, "--my");
    return Optional.of(
        new ModelSettings(
            model,
            side,
            deckFile,
            modelDeck,
            modelSlots,
            myDeck,
            mySlots,
            level,
            towerLevel == 0 ? level : towerLevel,
            sample,
            seed));
  }

  /** Slot flags go with a deck of cards given, not with a deck of the file (which has its own). */
  private static void checkSlots(String deck, int[] slots, String prefix) {
    if (slots != null && (deck == null || !isCards(deck))) {
      throw new IllegalArgumentException(
          prefix
              + "-slots goes with "
              + prefix
              + "-deck given as card row names; a deck of the deck file has its own slots");
    }
  }

  /** Whether a deck as given is card row names rather than a deck file's deck name. */
  static boolean isCards(String deck) {
    return deck.contains(",");
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
}
