package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A game tag's bit in an entity's tag word is its row's index in the game tags table, for the tag a
 * row sets and for the code that tests it alike. A table that drops a tag ahead of others moves
 * every later tag down one bit, as the table of data version 16.402.18 drops NO_REFLECTED_ATTACK
 * (index 38 in 14.593.1) and appends four tags at its end.
 *
 * <p>The scene: the bottom side's Mini P.E.K.K.A. walks up the left lane and meets the top side's
 * Giant coming down it, the Giant's row altered to set one tag. With NO_DAMAGE the Mini
 * P.E.K.K.A.'s hits deal nothing; with UNTARGETABLE it never takes the Giant as its target. Either
 * way the Giant keeps every hit point, whichever order the tags table lists its rows in.
 */
class BattleTagBitsTest {

  /** The battle stream's seed of the scene. */
  private static final int SEED = 1131;

  /** The levels the cards are played at: the Mini P.E.K.K.A. strong, the Giant at its first. */
  private static final int MINI_PEKKA_LEVEL = 11;

  private static final int GIANT_LEVEL = 1;

  /** A tick after the one the Mini P.E.K.K.A.'s hits kill the unaltered Giant on. */
  private static final int AFTER_DEATH = 400;

  /** The tag the 16.402.18 table drops, and its index in the configured one. */
  private static final String DROPPED = "NO_REFLECTED_ATTACK";

  /** The tags the 16.402.18 table appends after its last one. */
  private static final List<String> APPENDED =
      List.of(
          "SKELETROOPER_HIT",
          "ABILITY_PENDING",
          "UNKILLABLE",
          "IGNORE_RANGE_EXTENSION_TO_KEEP_TARGET");

  @TempDir Path folder;

  /**
   * The configured tables copied into a folder, the Giant's row setting the given tags and, when
   * asked, the game tags listed as the 16.402.18 table lists them.
   *
   * @param folder the folder to copy them into
   * @param giantTags the GameTagsToSet the Giant's row is given
   * @param shifted whether the tags table drops NO_REFLECTED_ATTACK and appends four tags
   */
  private static GameTables tables(Path folder, String giantTags, boolean shifted)
      throws IOException {
    Path source = GameTables.configuredDirectory().orElseThrow();
    try (Stream<Path> files = Files.list(source)) {
      for (Path file : files.toList()) {
        Files.copy(file, folder.resolve(file.getFileName()));
      }
    }
    edit(
        folder,
        "characters",
        rows -> GameData.columns(rows, "Giant").put("GameTagsToSet", giantTags));
    if (shifted) {
      edit(folder, "game_tags", BattleTagBitsTest::dropAndAppend);
    }
    return GameTables.load(folder);
  }

  /** Alters one table's rows in place. */
  private static void edit(Path folder, String table, Consumer<ObjectNode> change)
      throws IOException {
    Path file = folder.resolve(table + ".json");
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode document = (ObjectNode) mapper.readTree(file.toFile());
    change.accept((ObjectNode) document.get("rows"));
    mapper.writeValue(file.toFile(), document);
  }

  /**
   * Drops NO_REFLECTED_ATTACK, moves every later tag down one index and appends the four new tags,
   * leaving the rows listed in index order.
   */
  private static void dropAndAppend(ObjectNode rows) {
    int dropped = rows.get(DROPPED).get("index").asInt();
    rows.remove(DROPPED);
    List<Map.Entry<String, ObjectNode>> kept = new ArrayList<>();
    for (Map.Entry<String, JsonNode> e : rows.properties()) {
      kept.add(Map.entry(e.getKey(), (ObjectNode) e.getValue()));
    }
    kept.sort(Comparator.comparingInt(e -> e.getValue().get("index").asInt()));
    rows.removeAll();
    for (Map.Entry<String, ObjectNode> entry : kept) {
      ObjectNode row = entry.getValue();
      int index = row.get("index").asInt();
      if (index > dropped) {
        row.put("index", index - 1);
      }
      rows.set(entry.getKey(), row);
    }
    int next = kept.size();
    for (String name : APPENDED) {
      ObjectNode row = kept.get(0).getValue().deepCopy();
      row.put("index", next++);
      ((ObjectNode) row.get("columns")).put("Name", name);
      rows.set(name, row);
    }
  }

  /** The towers at the first level, fighting; the Mini P.E.K.K.A. and the Giant played. */
  private static Standard1v1Battle scene(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, 1, true);
    match.getWorld().seed(SEED);
    // The cards come from the battle's own records, so the Giant is played with its altered row.
    BattleRecords records = match.getWorld().getRecords();
    match.play(220, records.card("MiniPekka"), MINI_PEKKA_LEVEL, 0, 3500, 14000, "P");
    match.play(240, records.card("Giant"), GIANT_LEVEL, 1, 3500, 21500, "G");
    return match;
  }

  /** The Giant's hit points lost by the given tick, from where they stood as it was placed. */
  private static int giantLoss(GameTables tables, int tick) {
    Standard1v1Battle match = scene(tables);
    while (match.getPlays().size() < 2) {
      match.getBattle().step();
    }
    CharacterEntity giant = match.getPlays().get(1).units().get(0);
    int placed = giant.getHitPoints().getHitPoints();
    while (match.getBattle().getTick() < tick) {
      match.getBattle().step();
    }
    return placed - giant.getHitPoints().getHitPoints();
  }

  @Test
  @DisplayName("the configured tables' Giant without a tag loses hit points to the Mini P.E.K.K.A.")
  void theUntaggedGiantIsHit() throws IOException {
    assertThat(giantLoss(tables(folder, "", false), AFTER_DEATH)).isPositive();
  }

  @Test
  @DisplayName("a row's NO_DAMAGE keeps the Giant whole on the configured tables")
  void noDamageHoldsOnTheConfiguredTables() throws IOException {
    assertThat(giantLoss(tables(folder, "NO_DAMAGE", false), AFTER_DEATH)).isZero();
  }

  @Test
  @DisplayName(
      "a row's NO_DAMAGE keeps the Giant whole when the tags table drops a tag ahead of it")
  void noDamageHoldsWhenTheTagsShift() throws IOException {
    assertThat(giantLoss(tables(folder, "NO_DAMAGE", true), AFTER_DEATH)).isZero();
  }

  @Test
  @DisplayName(
      "a row's UNTARGETABLE keeps the Giant whole when the tags table drops a tag ahead of it")
  void untargetableHoldsWhenTheTagsShift() throws IOException {
    assertThat(giantLoss(tables(folder, "UNTARGETABLE", true), AFTER_DEATH)).isZero();
  }
}
