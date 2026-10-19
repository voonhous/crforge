/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.env.Schema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * A battle against a model, headless: the model acts as each decision opens through the
 * environment, the screen's side plays between ticks, and the battle runs on without a refusal.
 */
class ModelOpponentTest {

  private static final List<String> BASIC =
      List.of("Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Zap");

  private static final List<String> OTHER =
      List.of(
          "Knight", "Archer", "Musketeer", "Giant", "MiniPekka", "Fireball", "Arrows", "Goblins");

  /** The system property naming an exported model to play a whole battle against. */
  static final String MODEL_FILE = "crforge.modelFile";

  private static GameTables tables;
  private static ModelPolicy fixture;

  @BeforeAll
  static void load() throws IOException {
    tables = GameData.tables();
    fixture = ModelPolicyTest.fixturePolicy();
  }

  @AfterAll
  static void close() {
    fixture.close();
  }

  private static ModelSettings settings(int side, boolean sample, List<String> deck) {
    return new ModelSettings(
        Paths.get("model.onnx"),
        side,
        null,
        deck == null ? null : String.join(",", deck),
        null,
        null,
        null,
        ModelSettings.LEVEL,
        ModelSettings.LEVEL,
        sample ? 1.0 : 0.0,
        ModelPolicy.TemperatureMode.ALL,
        0);
  }

  /**
   * Steps a session for some ticks, the screen's side trying a play of each hand slot every 15
   * ticks at a point of its own half.
   *
   * @return the plays the screen's side gave
   */
  private static int play(BattleSession session, int screenSide, int ticks) {
    int given = 0;
    for (int t = 0; t < ticks && !session.isOver(); t++) {
      if (session.tick() % 15 == 0) {
        for (int k = 0; k < 4; k++) {
          if (session.cardUnavailableReason(screenSide, k) == null
              && session.play(
                  screenSide,
                  k,
                  Schema.gameX(screenSide, 4 + 3 * k),
                  Schema.gameY(screenSide, 7))) {
            given++;
            break;
          }
        }
      }
      assertThat(session.step()).as("tick %d: %s", t, session.getHalted()).isTrue();
    }
    return given;
  }

  @Test
  void theModelActsEveryDecisionAndTheScreenPlaysBetween() {
    for (int side = 0; side < 2; side++) {
      ModelOpponent opponent =
          ModelOpponent.start(fixture, settings(side, true, BASIC), null, tables, 0, false);
      BattleSession session = BattleSession.withModel(opponent);
      int given = play(session, 1 - side, 1200);
      assertThat(session.getHalted()).isNull();
      assertThat(given).isGreaterThan(3);
      assertThat(opponent.decisions()).isEqualTo((1200 + 4) / 5);
      assertThat(opponent.plays()).isGreaterThan(3);
      assertThat(opponent.refused()).isZero();
      // The screen does not play the model's side.
      assertThat(session.play(side, 0, Schema.gameX(side, 9), Schema.gameY(side, 4))).isFalse();
      assertThat(session.messages()).anyMatch(m -> m.contains("the model plays"));
    }
  }

  @Test
  void theModelsMostLikelyChoicesPlayTheSameBattleTwice() {
    int[] ticks = new int[2];
    long[] commands = new long[2];
    for (int run = 0; run < 2; run++) {
      ModelOpponent opponent =
          ModelOpponent.start(fixture, settings(1, false, BASIC), null, tables, 3, false);
      BattleSession session = BattleSession.withModel(opponent);
      play(session, 0, 600);
      ticks[run] = session.tick();
      commands[run] =
          session.getBattle().getCommands().stream()
              .mapToLong(c -> 31L * c.tick() + c.x() * 7L + c.y())
              .sum();
    }
    assertThat(ticks[1]).isEqualTo(ticks[0]);
    assertThat(commands[1]).isEqualTo(commands[0]);
  }

  @Test
  void decksAreNamedFromADeckFileWithTheirSlotsAndTheScreensSideMirrorsTheModel(
      @TempDir Path folder) throws IOException {
    Path file = folder.resolve("decks.json");
    Files.writeString(
        file,
        "{\"decks\": [{\"name\": \"first\", \"cards\": "
            + cardsJson(BASIC)
            + ", \"slots\": [0, 0, 0, 0, 0, 0, 0, 0], \"share\": 0.5},"
            + " {\"name\": \"second\", \"cards\": "
            + cardsJson(OTHER)
            + "}]}");
    DeckFile decks = DeckFile.read(file);
    ModelSettings mirror =
        ModelSettings.parse(
                new String[] {
                  "--model", "m.onnx", "--deck-file", file.toString(), "--model-deck", "first"
                })
            .orElseThrow();
    ModelOpponent opponent = ModelOpponent.start(fixture, mirror, decks, tables, 0, false);
    assertThat(opponent.deck()).isEqualTo(BASIC);
    assertThat(deckOf(opponent, 0)).isEqualTo(BASIC);
    assertThat(opponent.live().config().level()).isEqualTo(ModelSettings.LEVEL);
    assertThat(opponent.live().config().towerLevel()).isEqualTo(ModelSettings.LEVEL);

    ModelSettings chosen =
        ModelSettings.parse(
                new String[] {
                  "--model",
                  "m.onnx",
                  "--deck-file",
                  file.toString(),
                  "--model-deck",
                  "first",
                  "--my-deck",
                  "second",
                  "--model-side",
                  "0"
                })
            .orElseThrow();
    opponent = ModelOpponent.start(fixture, chosen, decks, tables, 0, false);
    assertThat(deckOf(opponent, 0)).isEqualTo(BASIC);
    assertThat(deckOf(opponent, 1)).isEqualTo(OTHER);

    assertThatThrownBy(() -> decks.deck("third")).hasMessageContaining("no deck named third");
    ModelSettings noFile =
        ModelSettings.parse(new String[] {"--model", "m.onnx", "--model-deck", "first"})
            .orElseThrow();
    assertThatThrownBy(() -> ModelOpponent.start(fixture, noFile, null, tables, 0, false))
        .hasMessageContaining("--deck-file");
  }

  @Test
  void aDeckFilesSlotFlagsAreRead(@TempDir Path folder) throws IOException {
    Path file = folder.resolve("decks.json");
    Files.writeString(
        file,
        "{\"decks\": [{\"name\": \"marked\", \"cards\": "
            + cardsJson(BASIC)
            + ", \"slots\": [1, 0, 0, 0, 0, 0, 0, 0]}]}");
    DeckFile decks = DeckFile.read(file);
    assertThat(decks.deck("marked").slots()).containsExactly(1, 0, 0, 0, 0, 0, 0, 0);
    Path bad = folder.resolve("bad.json");
    Files.writeString(bad, "{\"decks\": [{\"name\": \"short\", \"cards\": [\"Knight\"]}]}");
    assertThatThrownBy(() -> DeckFile.read(bad)).hasMessageContaining("8 card row names");
  }

  private static String cardsJson(List<String> cards) {
    return "[\"" + String.join("\", \"", cards) + "\"]";
  }

  private static List<String> deckOf(ModelOpponent opponent, int side) {
    return opponent.live().battle().getMatch().side(side).deck().stream()
        .map(card -> card.name())
        .toList();
  }

  @Test
  void aModelThatNamesNoDeckAsksForOne() {
    assertThatThrownBy(
            () -> ModelOpponent.start(fixture, settings(1, false, null), null, tables, 0, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--model-deck");
  }

  /**
   * A whole battle against a model exported from a checkpoint, named by the system property {@value
   * #MODEL_FILE} (on the tables of the data version it was trained on: {@code
   * -Pcrforge.dataVersion=<v>}); its deck and levels are the model file's, its choices drawn as the
   * trainer's evaluations draw them (a cloned model's most likely choice is nearly always the
   * wait). Skipped without one: a trained model is never part of this repository.
   */
  @Test
  @EnabledIfSystemProperty(named = MODEL_FILE, matches = ".+")
  void aTrainedModelPlaysAWholeBattle() throws IOException {
    try (ModelPolicy policy = ModelPolicy.load(Paths.get(System.getProperty(MODEL_FILE)))) {
      ModelSettings settings =
          ModelSettings.parse(
                  new String[] {"--model", System.getProperty(MODEL_FILE), "--model-sample"})
              .orElseThrow();
      ModelOpponent opponent = ModelOpponent.start(policy, settings, null, tables, 0);
      BattleSession session = BattleSession.withModel(opponent);
      long started = System.nanoTime();
      play(session, 0, 6000);
      long millis = (System.nanoTime() - started) / 1_000_000;
      System.out.printf(
          "model %s: %d ticks, over %s, %d decisions, %d plays, %d refused, %d ms (%.2f ms a"
              + " decision with the battle), crowns %d-%d%n",
          policy.property("name"),
          session.tick(),
          session.isOver(),
          opponent.decisions(),
          opponent.plays(),
          opponent.refused(),
          millis,
          millis / (double) Math.max(1, opponent.decisions()),
          session.match().crowns(0),
          session.match().crowns(1));
      assertThat(session.getHalted()).isNull();
      assertThat(opponent.refused()).isZero();
      assertThat(opponent.plays()).isGreaterThan(5);
    }
  }
}
