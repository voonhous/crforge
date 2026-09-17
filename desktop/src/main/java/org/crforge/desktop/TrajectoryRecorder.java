package org.crforge.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;

/**
 * Records the per-tick position of every ground troop in the match and writes one file per troop.
 *
 * <p>Sampling happens inside the visualizer's tick loop rather than once a frame, because a frame
 * covers several ticks at high simulation speeds and none at low ones. {@link #sample(GameEngine)}
 * is therefore safe to call more than once for the same tick: it records a tick once and ignores
 * every later call for it.
 *
 * <p>A troop's ticks are counted from the tick it first appeared in, so its first sample is tick 0
 * whether it was deployed at the start of the match or five minutes into it. Its deploy position is
 * the position of that first sample.
 *
 * <p>This is not a renderer and holds no graphics resources.
 */
public final class TrajectoryRecorder {

  /** The timestamp suffix of a written file, which is what keeps two exports apart. */
  private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * One sampled tick.
   *
   * @param tick ticks since the troop first appeared, starting at 0
   * @param x position along the arena's width in game units
   * @param y position along the arena's length in game units
   */
  public record Sample(int tick, int x, int y) {}

  /** One troop's whole recording. */
  public static final class Track {

    private final long entityId;
    private final String card;
    private final int side;
    private final int deployX;
    private final int deployY;
    private final int firstFrame;
    private final List<Sample> samples = new ArrayList<>();

    private Track(long entityId, String card, Team team, int x, int y, int frameCount) {
      this.entityId = entityId;
      this.card = card;
      this.side = team == Team.BLUE ? 0 : 1;
      this.deployX = x;
      this.deployY = y;
      this.firstFrame = frameCount;
    }

    /** The id of the troop this track follows. */
    public long entityId() {
      return entityId;
    }

    /** The name of the card the troop was deployed from. */
    public String card() {
      return card;
    }

    /** 0 for the blue player, 1 for the red one. */
    public int side() {
      return side;
    }

    /** The troop's position on its first sampled tick, along the arena's width. */
    public int deployX() {
      return deployX;
    }

    /** The troop's position on its first sampled tick, along the arena's length. */
    public int deployY() {
      return deployY;
    }

    /** The samples in tick order, the first of which is tick 0. */
    public List<Sample> samples() {
      return List.copyOf(samples);
    }

    /** The file name this track is written to, without a directory. */
    private String fileName(String timestamp) {
      return card + "_" + (side == 0 ? "blue" : "red") + "_" + entityId + "_" + timestamp + ".json";
    }

    /** The whole file contents, with the keys in the order they are documented in. */
    private Map<String, Object> document() {
      Map<String, Object> root = new LinkedHashMap<>();
      root.put("card", card);
      root.put("deploy", List.of(deployX, deployY));
      root.put("side", side);
      List<Map<String, Object>> rows = new ArrayList<>(samples.size());
      for (Sample sample : samples) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tick", sample.tick());
        row.put("x", sample.x());
        row.put("y", sample.y());
        rows.add(row);
      }
      root.put("samples", rows);
      return root;
    }
  }

  /** One track per troop seen, in the order the troops first appeared. */
  private final Map<Long, Track> tracks = new LinkedHashMap<>();

  /** The engine tick count the last sample was taken at, so one tick is never sampled twice. */
  private int lastSampledFrame = -1;

  /** Forgets every track. Called when the match is reset or a new scenario starts. */
  public void clear() {
    tracks.clear();
    lastSampledFrame = -1;
  }

  /** The tracks recorded so far, in the order the troops first appeared. */
  public List<Track> tracks() {
    return List.copyOf(tracks.values());
  }

  /**
   * Records one tick for every alive ground troop.
   *
   * <p>Called immediately after each {@code tick()} inside the visualizer's tick loop. A second
   * call for the same tick does nothing, which is what keeps the recording per tick rather than per
   * frame.
   */
  public void sample(GameEngine engine) {
    int frameCount = engine.getGameState().getFrameCount();
    if (frameCount == lastSampledFrame) {
      return;
    }
    lastSampledFrame = frameCount;
    for (Entity entity : engine.getGameState().getAliveEntities()) {
      if (!(entity instanceof Troop troop) || troop.getMovementType() != MovementType.GROUND) {
        continue;
      }
      int x = troop.getPosition().getX();
      int y = troop.getPosition().getY();
      Track track =
          tracks.computeIfAbsent(
              troop.getId(),
              id -> new Track(id, troop.getName(), troop.getTeam(), x, y, frameCount));
      track.samples.add(new Sample(frameCount - track.firstFrame, x, y));
    }
  }

  /**
   * Writes one file per recorded troop, creating the directory if it does not exist.
   *
   * @param directory the directory the files are written into
   * @return the paths written, in the order the troops first appeared
   * @throws IOException if the directory or one of the files cannot be written
   */
  public List<Path> export(Path directory) throws IOException {
    if (tracks.isEmpty()) {
      return List.of();
    }
    Files.createDirectories(directory);
    String timestamp = LocalDateTime.now().format(TIMESTAMP);
    List<Path> written = new ArrayList<>(tracks.size());
    for (Track track : tracks.values()) {
      Path file = directory.resolve(track.fileName(timestamp));
      Files.writeString(file, MAPPER.writeValueAsString(track.document()));
      written.add(file);
    }
    return written;
  }
}
