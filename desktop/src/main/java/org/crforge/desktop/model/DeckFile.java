/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.crforge.env.Schema;

/**
 * A deck file: named decks, each its card row names and its slot flags, read from a path at run
 * time ({@code --deck-file}), so the model and the screen's side can each be given a deck by name.
 *
 * <p>The file is a JSON object whose {@code decks} list holds objects with a {@code name}, {@code
 * cards} ({@value Schema#DECK_SIZE} card row names) and {@code slots} (one flag a card: evolution
 * 1, hero 2, both 3, or 0; all 0 when left out); other keys are ignored. It is the layout the
 * trainer's deck files use, so a model plays the deck it was trained on, with its evolution and
 * hero slots.
 */
public final class DeckFile {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * A deck of the file.
   *
   * @param name its name
   * @param cards its card row names, by deck index
   * @param slots its slot flags, by deck index
   */
  public record Deck(String name, List<String> cards, int[] slots) {}

  private final Path file;
  private final Map<String, Deck> decks;

  private DeckFile(Path file, Map<String, Deck> decks) {
    this.file = file;
    this.decks = decks;
  }

  /**
   * Reads a deck file.
   *
   * @param file the file
   * @return its decks
   * @throws IOException when it cannot be read
   * @throws IllegalArgumentException when it is not a deck file of this layout
   */
  public static DeckFile read(Path file) throws IOException {
    JsonNode root = JSON.readTree(Files.readAllBytes(file));
    JsonNode list = root == null ? null : root.get("decks");
    if (list == null || !list.isArray() || list.isEmpty()) {
      throw new IllegalArgumentException(file + " holds no 'decks' list");
    }
    Map<String, Deck> decks = new LinkedHashMap<>();
    for (JsonNode entry : list) {
      String name = entry.path("name").asText("");
      if (name.isBlank()) {
        throw new IllegalArgumentException(file + " has a deck without a name");
      }
      JsonNode cards = entry.get("cards");
      if (cards == null || !cards.isArray() || cards.size() != Schema.DECK_SIZE) {
        throw new IllegalArgumentException(
            file + ", deck " + name + ": 'cards' is " + Schema.DECK_SIZE + " card row names");
      }
      List<String> names = new ArrayList<>();
      cards.forEach(card -> names.add(card.asText()));
      int[] slots = new int[Schema.DECK_SIZE];
      JsonNode flags = entry.get("slots");
      if (flags != null && !flags.isNull()) {
        if (!flags.isArray() || flags.size() != Schema.DECK_SIZE) {
          throw new IllegalArgumentException(
              file + ", deck " + name + ": 'slots' is " + Schema.DECK_SIZE + " flags");
        }
        for (int i = 0; i < slots.length; i++) {
          slots[i] = flags.get(i).asInt();
        }
      }
      if (decks.putIfAbsent(name, new Deck(name, List.copyOf(names), slots)) != null) {
        throw new IllegalArgumentException(file + " names deck " + name + " twice");
      }
    }
    return new DeckFile(file, Map.copyOf(decks));
  }

  /**
   * A deck by name.
   *
   * @throws IllegalArgumentException when the file has no deck of that name
   */
  public Deck deck(String name) {
    Deck deck = decks.get(name);
    if (deck == null) {
      throw new IllegalArgumentException(file.getFileName() + " has no deck named " + name);
    }
    return deck;
  }
}
