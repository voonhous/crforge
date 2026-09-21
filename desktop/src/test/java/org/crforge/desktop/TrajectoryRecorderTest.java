package org.crforge.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.crforge.core.card.Card;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.match.PathfindingMode;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.LevelConfig;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The trajectory recorder samples ground troops inside the visualizer's tick loop and writes one
 * file per troop. These tests drive a real engine so they exercise the same call shape the screen
 * uses, and then read the written files back.
 */
class TrajectoryRecorderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final int LEVEL = 11;

  @Test
  @DisplayName("a troop's first sample is tick 0 whenever it appeared")
  void tickNumberingStartsAtTheTroopsFirstTick() {
    GameEngine engine = engine();
    TrajectoryRecorder recorder = new TrajectoryRecorder();

    spawnKnight(engine, 3500, 10000);
    for (int i = 0; i < 5; i++) {
      engine.tick();
      recorder.sample(engine);
    }
    spawnKnight(engine, 14500, 10000);
    for (int i = 0; i < 4; i++) {
      engine.tick();
      recorder.sample(engine);
    }

    List<TrajectoryRecorder.Track> tracks = recorder.tracks();
    assertThat(tracks).hasSize(2);
    assertThat(tracks.get(0).samples()).hasSize(9);
    assertThat(tracks.get(0).samples().get(0).tick()).isZero();
    assertThat(tracks.get(0).samples().get(8).tick()).isEqualTo(8);
    // The second Knight appeared five ticks later but still starts its own count at zero.
    assertThat(tracks.get(1).samples()).hasSize(4);
    assertThat(tracks.get(1).samples().get(0).tick()).isZero();
    assertThat(tracks.get(1).samples().get(3).tick()).isEqualTo(3);
  }

  @Test
  @DisplayName("sampling twice within one tick records the tick once")
  void samplingIsPerTickNotPerFrame() {
    GameEngine engine = engine();
    TrajectoryRecorder recorder = new TrajectoryRecorder();

    spawnKnight(engine, 3500, 10000);
    engine.tick();
    recorder.sample(engine);
    recorder.sample(engine);
    recorder.sample(engine);
    engine.tick();
    recorder.sample(engine);

    assertThat(recorder.tracks()).hasSize(1);
    assertThat(recorder.tracks().get(0).samples()).hasSize(2);
  }

  @Test
  @DisplayName("the deploy position is the troop's first sampled position")
  void deployIsTheFirstSample() {
    GameEngine engine = engine();
    TrajectoryRecorder recorder = new TrajectoryRecorder();

    spawnKnight(engine, 3500, 10000);
    engine.tick();
    recorder.sample(engine);

    TrajectoryRecorder.Track track = recorder.tracks().get(0);
    assertThat(track.card()).isEqualTo("Knight");
    assertThat(track.side()).isZero();
    assertThat(track.deployX()).isEqualTo(3500);
    assertThat(track.deployY()).isEqualTo(10000);
  }

  @Test
  @DisplayName("an exported file carries exactly the four documented keys")
  void exportedFormat(@TempDir Path directory) throws IOException {
    GameEngine engine = engine();
    TrajectoryRecorder recorder = new TrajectoryRecorder();

    spawnKnight(engine, 3500, 10000);
    for (int i = 0; i < 3; i++) {
      engine.tick();
      recorder.sample(engine);
    }

    List<Path> written = recorder.export(directory.resolve("trajectories"));

    assertThat(written).hasSize(1);
    Path file = written.get(0);
    assertThat(file.getFileName().toString()).matches("Knight_blue_\\d+_\\d{8}_\\d{6}\\.json");

    JsonNode root = MAPPER.readTree(Files.readString(file));
    List<String> keys = new ArrayList<>();
    root.fieldNames().forEachRemaining(keys::add);
    assertThat(keys).containsExactly("card", "deploy", "side", "samples");
    assertThat(root.get("card").asText()).isEqualTo("Knight");
    assertThat(root.get("deploy").get(0).asInt()).isEqualTo(3500);
    assertThat(root.get("deploy").get(1).asInt()).isEqualTo(10000);
    assertThat(root.get("side").asInt()).isZero();
    assertThat(root.get("samples")).hasSize(3);

    List<String> sampleKeys = new ArrayList<>();
    root.get("samples").get(0).fieldNames().forEachRemaining(sampleKeys::add);
    assertThat(sampleKeys).containsExactly("tick", "x", "y");
    assertThat(root.get("samples").get(0).get("tick").asInt()).isZero();
    assertThat(root.get("samples").get(2).get("tick")).isNotNull();
  }

  @Test
  @DisplayName("a red troop is written with side 1")
  void redSide(@TempDir Path directory) throws IOException {
    GameEngine engine = engine();
    TrajectoryRecorder recorder = new TrajectoryRecorder();

    Card knight = Objects.requireNonNull(CardRegistry.get("knight"), "knight not found");
    engine
        .getSpawnerSystem()
        .spawnUnit(
            9000,
            25000,
            Team.RED,
            knight.getUnitStats(),
            LEVEL,
            knight.getUnitStats().getDeployTime());
    engine.tick();
    recorder.sample(engine);

    List<Path> written = recorder.export(directory);

    assertThat(written.get(0).getFileName().toString()).startsWith("Knight_red_");
    assertThat(MAPPER.readTree(Files.readString(written.get(0))).get("side").asInt()).isEqualTo(1);
  }

  @Test
  @DisplayName("clearing drops every track")
  void clearDropsEveryTrack() {
    GameEngine engine = engine();
    TrajectoryRecorder recorder = new TrajectoryRecorder();

    spawnKnight(engine, 3500, 10000);
    engine.tick();
    recorder.sample(engine);
    assertThat(recorder.tracks()).hasSize(1);

    recorder.clear();

    assertThat(recorder.tracks()).isEmpty();
  }

  @Test
  @DisplayName("exporting with nothing recorded writes no file")
  void exportWithoutSamples(@TempDir Path directory) throws IOException {
    assertThat(new TrajectoryRecorder().export(directory)).isEmpty();
  }

  private static void spawnKnight(GameEngine engine, int x, int y) {
    Card knight = Objects.requireNonNull(CardRegistry.get("knight"), "knight not found");
    engine
        .getSpawnerSystem()
        .spawnUnit(
            x, y, Team.BLUE, knight.getUnitStats(), LEVEL, knight.getUnitStats().getDeployTime());
  }

  private static GameEngine engine() {
    AbstractEntity.resetIdCounter();
    List<Card> cards = new ArrayList<>();
    for (String id :
        List.of(
            "knight", "giant", "musketeer", "archer", "goblins", "valkyrie", "bomber", "minions")) {
      cards.add(Objects.requireNonNull(CardRegistry.get(id), id + " not found"));
    }
    Standard1v1Match match = new Standard1v1Match(LEVEL, PathfindingMode.GRID);
    match.addPlayer(new Player(Team.BLUE, new Deck(cards), false, new LevelConfig(LEVEL)));
    match.addPlayer(
        new Player(Team.RED, new Deck(new ArrayList<>(cards)), false, new LevelConfig(LEVEL)));
    GameEngine engine = new GameEngine();
    engine.setMatch(match);
    engine.initMatch();
    return engine;
  }
}
