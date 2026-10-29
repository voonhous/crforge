/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.replay.ScenarioWriter;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.TowerEntity;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.desktop.battle.BattleDecks;
import org.crforge.desktop.battle.BattleSession;

/**
 * The example replay the repository ships ({@code examples/replays/}): a whole Ladder battle the
 * battle core plays between the debug screen's two decks ({@link BattleDecks}), with a fixed script
 * for both sides, written as a replay of the tables' data version ({@link ScenarioWriter}).
 *
 * <p>It is made by the battle core alone, so it holds no battle recorded in the game and no
 * player's data. The script: each side waits until it has {@link #PLAY_AT} elixir, then plays the
 * first card of its hand it may play now. A card that may be placed in the other side's half (a
 * spell, the Miner) is aimed at the other side's tower nearest the side's lane; any other card is
 * placed in the side's own lane, {@link #LANE_OFFSET} in front of its princess tower there. Both
 * sides use the same lane, the one at the low end of the arena's width, so their plays meet. A
 * hero's ability is used as soon as it may be. The battle runs until its match is over. Both
 * players' avatars name the arena {@link #ARENA}.
 *
 * <p>Run it with {@code ./gradlew :desktop:exampleReplay}, which writes the file for the data
 * version the build makes the tables of (the lock's by default). A data version bump rewrites it:
 * {@code ExampleReplayTest} fails while the shipped file is not the one the script plays on the
 * lock's tables.
 */
public final class ExampleReplay {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The elixir a side waits for before it plays. */
  static final int PLAY_AT = 9;

  /** How far in front of a side's princess tower its own cards are placed, in game units. */
  static final int LANE_OFFSET = 3000;

  /**
   * The arena both players' avatars name, a row of the arenas table: the first trophy arena, where
   * every player's Ladder begins, and not the training camp. A replay's avatar names its player's
   * arena, and the battle holds none, so the example names it.
   */
  static final String ARENA = "Arena1";

  /** The most steps the battle is run for: ten minutes of game time, past any Ladder match. */
  static final int MAX_STEPS = 12_000;

  private ExampleReplay() {
    // Utility class
  }

  /**
   * Writes the example replay of the configured tables (the {@code crforge.gameTables} folder) to
   * the file the first argument names.
   *
   * @param args the file to write
   * @throws IOException when the file cannot be written
   */
  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      throw new IllegalArgumentException("usage: ExampleReplay <file to write>");
    }
    GameTables tables = GameTables.loadConfigured();
    Path file = Paths.get(args[0]);
    Files.createDirectories(file.toAbsolutePath().getParent());
    Files.writeString(file, text(play(tables)));
    System.out.println(
        "Wrote the example replay of data version " + tables.version() + " to " + file);
  }

  /**
   * Plays the scripted battle on the tables and writes it as a replay.
   *
   * @param tables the game tables
   * @return the replay document
   * @throws IllegalStateException when the battle core stops the battle before its match is over
   */
  public static ObjectNode play(GameTables tables) {
    // A Ladder battle as a player's game builds one: each player's data handed over with one
    // choice (a replay holds one per side), then the decks dealt with both players' words 0.
    Standard1v1Battle battle = new Standard1v1Battle(tables, BattleSession.LEVEL);
    battle.addPlayerData(1);
    battle.addPlayerData(1);
    battle.startLadderMatch(
        BattleDecks.BLUE, BattleDecks.RED, 0, 0, BattleDecks.blueSlots(), BattleDecks.redSlots());
    BattleSession session = BattleSession.of(battle, Map.of());
    for (int step = 0; step < MAX_STEPS && !session.isOver(); step++) {
      for (int side = 0; side < 2; side++) {
        useAbilities(session, side);
        playOne(session, side);
      }
      if (!session.step()) {
        throw new IllegalStateException(
            "the example battle stopped on tick " + session.tick() + ": " + session.getHalted());
      }
    }
    if (!session.isOver()) {
      throw new IllegalStateException(
          "the example battle is not over after " + MAX_STEPS + " steps");
    }
    ScenarioWriter writer = new ScenarioWriter(tables);
    return writer.write(
        writer.plan(session.getBattle(), BattleSession.LEVEL, List.of(ARENA, ARENA)),
        session.tick());
  }

  /**
   * The replay document as the file holds it: indented, one field a line, with a final newline, so
   * a rewrite shows only the values that moved.
   *
   * @param document the replay document
   * @return the file's text
   * @throws IOException when the document cannot be written as JSON
   */
  public static String text(ObjectNode document) throws IOException {
    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n";
  }

  /** Uses every champion slot ability of a side that may be used now. */
  private static void useAbilities(BattleSession session, int side) {
    for (int slot = 0; slot < BattleSession.ABILITY_SLOTS; slot++) {
      if (session.abilityUnavailableReason(side, slot) == null) {
        session.useAbility(side, slot);
      }
    }
  }

  /** Plays the first hand card the side may play now, once it has {@link #PLAY_AT} elixir. */
  private static void playOne(BattleSession session, int side) {
    if (session.match().side(side).wholeElixir() < PLAY_AT) {
      return;
    }
    for (int slot = 0; slot < 4; slot++) {
      if (session.cardUnavailableReason(side, slot) != null) {
        continue;
      }
      int[] point = point(session, side, session.handCard(side, slot));
      if (point != null && session.play(side, slot, point[0], point[1])) {
        return;
      }
    }
  }

  /**
   * Where the script places a card: the other side's tower nearest the side's lane for a card that
   * may be placed in the other side's half, else in front of the side's own princess tower in the
   * lane; null when the side has no tower left to aim from.
   */
  private static int[] point(BattleSession session, int side, MatchCard card) {
    List<TowerEntity> towers =
        session.getBattle().getWorld().present().stream()
            .filter(TowerEntity.class::isInstance)
            .map(TowerEntity.class::cast)
            .toList();
    // The side's lane runs through its tower lowest on the width: its princess tower at the low
    // end, or its king once that tower is down (a king stands on the middle of the width).
    TowerEntity own =
        towers.stream()
            .filter(tower -> tower.side() == side)
            .min(Comparator.comparingInt(WorldEntity::x))
            .orElse(null);
    if (own == null) {
      return null;
    }
    int forward = side == 0 ? LANE_OFFSET : -LANE_OFFSET;
    int[] lane = {own.x(), own.y() + forward};
    DeployCard row = session.deployCard(card);
    if (row == null || !row.canDeployOnEnemySide()) {
      return lane;
    }
    return towers.stream()
        .filter(tower -> tower.side() != side)
        .min(Comparator.comparingLong(tower -> squaredDistance(tower, lane)))
        .map(tower -> new int[] {tower.x(), tower.y()})
        .orElse(lane);
  }

  private static long squaredDistance(WorldEntity entity, int[] point) {
    long dx = entity.x() - point[0];
    long dy = entity.y() - point[1];
    return dx * dx + dy * dy;
  }
}
