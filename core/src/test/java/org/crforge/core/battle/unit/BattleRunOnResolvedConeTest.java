package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A target resolver with a Cone shape, as the Minion Giant looks behind itself for a friendly
 * Minion: the circle query around the owner keeps a building by its square and anything else
 * strictly within the radius plus its collision radius, then the cone keeps what lies within half
 * its angle of the direction the owner faces turned by AngleOffset, or close enough to the cone's
 * edge for its collision radius to reach across it.
 *
 * <p>In each scene the altered tables give the Mini Pekka a starting interval that searches every
 * step through a resolver with the Minion Giant's cone (Angle 83, AngleOffset 180, Radius 2500,
 * UseGameObjectDirection) and a filter of friendly Knights, and writes TestVariable: 1 on what it
 * finds, 5 on itself when it finds nothing. Every unit is still deploying when the scene is read,
 * so none has moved and each faces up the arena for the bottom side, down it for the top side.
 */
class BattleRunOnResolvedConeTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String START = "Test_search_start";

  private static final String SEARCH = "Test_search";

  private static final String RESOLVER = "Test_resolver";

  private static final String FILTER = "Test_friendly_knights";

  private static final String SHAPE = "Test_cone";

  /** An action row of a class, its fields to fill. */
  private static ObjectNode action(ObjectNode rows, String name, String type) {
    ObjectNode row = rows.putObject(name);
    row.put("class", "Logic" + type + "Data");
    row.put("ClassType", type);
    ObjectNode fields = row.putObject("fields");
    fields.put("ClassType", type);
    return fields;
  }

  /** A variable write of a value expression on whoever it runs on. */
  private static void write(ObjectNode rows, String name, String value) {
    ObjectNode fields = action(rows, name, "ActionSetVariable");
    fields.put("Variable", "TestVariable");
    fields.put("Value", value);
  }

  /** Alters one more table of the folder the configured tables were copied into. */
  private static void alter(Path folder, String table, Consumer<ObjectNode> edit)
      throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    Path file = folder.resolve(table + ".json");
    ObjectNode document = (ObjectNode) mapper.readTree(file.toFile());
    edit.accept((ObjectNode) document.get("rows"));
    mapper.writeValue(file.toFile(), document);
  }

  /** The configured tables with the Mini Pekka's cone search, its resolver, filter and shape. */
  private static GameTables coneSearch(Path folder) throws IOException {
    GameData.altered(
        folder,
        "actions",
        actions -> {
          ObjectNode interval = action(actions, START, "ActionInterval");
          interval.put("Interval", 50);
          interval.putObject("ActionToExecute").put("action", SEARCH);
          ObjectNode run = action(actions, SEARCH, "ActionRunActionOnResolvedGameObjects");
          run.put("Resolver", RESOLVER);
          run.put("Amount", 1);
          run.putObject("Action").put("action", "Test_found");
          run.putObject("ActionToRunOnSelfIfNoObjectsFound").put("action", "Test_none");
          write(actions, "Test_found", "1");
          write(actions, "Test_none", "5");
        });
    alter(
        folder,
        "game_object_filters",
        filters -> {
          ObjectNode row = filters.putObject(FILTER);
          row.put("index", filters.size());
          row.put("class", "LogicGameObjectFilterData");
          ObjectNode columns = row.putObject("columns");
          columns.put("MatchTeamOwn", true);
          columns.put("MatchTypeCharacters", true);
          columns.put("FilterBuildings", true);
          columns.putArray("IncludeCharactersWithData").add("Knight");
        });
    alter(
        folder,
        "shapes",
        shapes -> {
          ObjectNode row = shapes.putObject(SHAPE);
          row.put("index", shapes.size());
          row.put("class", "LogicConeShapeData");
          ObjectNode columns = row.putObject("columns");
          columns.put("ClassType", "Cone");
          columns.put("Angle", 83);
          columns.put("AngleOffset", 180);
          columns.put("Radius", 2500);
          columns.put("UseGameObjectDirection", true);
        });
    alter(
        folder,
        "target_resolvers",
        resolvers -> {
          ObjectNode row = resolvers.putObject(RESOLVER);
          row.put("index", resolvers.size());
          row.put("class", "LogicGameObjectResolverData");
          ObjectNode columns = row.putObject("columns");
          columns.put("Filter", FILTER);
          columns.put("Shape", SHAPE);
          columns.putArray("StrategyList").add("RESOLVER_STRATEGY_CLOSEST_TARGET");
        });
    alter(
        folder,
        "characters",
        characters -> GameData.columns(characters, "MiniPekka").put("OnStartingAction", START));
    GameData.addTestVariable(folder);
    return GameTables.load(folder);
  }

  /** Plays a card for a side at a point, on tick 1. */
  private static void play(Standard1v1Battle battle, String card, int side, int x, int y) {
    battle.play(1, battle.getWorld().getRecords().card(card), LEVEL, side, x, y, card + x + y);
  }

  private static void stepTo(Standard1v1Battle battle, int tick) {
    while (battle.getBattle().getTick() <= tick) {
      battle.getBattle().step();
    }
  }

  /** The character of a row and side that stands nearest a point. */
  private static CharacterEntity at(Standard1v1Battle battle, String row, int side, int x, int y) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(CharacterEntity.class::isInstance)
        .map(CharacterEntity.class::cast)
        .filter(unit -> unit.getData().name().equals(row) && unit.side() == side)
        .min(
            (a, b) ->
                Long.compare(
                    distance(a.getView().getX() - x, a.getView().getY() - y),
                    distance(b.getView().getX() - x, b.getView().getY() - y)))
        .orElseThrow();
  }

  private static long distance(long dx, long dy) {
    return dx * dx + dy * dy;
  }

  private static int variable(Standard1v1Battle battle, CharacterEntity unit) {
    return unit.variable(battle.getWorld().variableKey("TestVariable"));
  }

  @Test
  @DisplayName(
      "the cone looks behind the searcher: the Knight behind it is found though the one ahead is"
          + " closer")
  void theConeFindsWhatStandsBehind(@TempDir Path folder) throws IOException {
    Standard1v1Battle battle = new Standard1v1Battle(coneSearch(folder), LEVEL, false);
    play(battle, "MiniPekka", 0, 3500, 12000);
    play(battle, "Knight", 0, 3500, 13200);
    play(battle, "Knight", 0, 3500, 10400);
    stepTo(battle, 15);

    assertThat(variable(battle, at(battle, "Knight", 0, 3500, 10400))).isEqualTo(1);
    assertThat(variable(battle, at(battle, "Knight", 0, 3500, 13200))).isZero();
  }

  @Test
  @DisplayName("the cone turns with the searcher: for the top side behind is up the arena")
  void theConeTurnsWithTheFacing(@TempDir Path folder) throws IOException {
    Standard1v1Battle battle = new Standard1v1Battle(coneSearch(folder), LEVEL, false);
    play(battle, "MiniPekka", 1, 3500, 20000);
    play(battle, "Knight", 1, 3500, 18800);
    play(battle, "Knight", 1, 3500, 21600);
    stepTo(battle, 15);

    assertThat(variable(battle, at(battle, "Knight", 1, 3500, 21600))).isEqualTo(1);
    assertThat(variable(battle, at(battle, "Knight", 1, 3500, 18800))).isZero();
  }

  @Test
  @DisplayName(
      "a Knight 4 degrees past the cone's edge is found: its collision radius reaches across the"
          + " edge")
  void theEdgeReachesByTheCollisionRadius(@TempDir Path folder) throws IOException {
    Standard1v1Battle battle = new Standard1v1Battle(coneSearch(folder), LEVEL, false);
    play(battle, "MiniPekka", 0, 3500, 12000);
    play(battle, "Knight", 0, 4500, 11000);
    stepTo(battle, 15);

    assertThat(variable(battle, at(battle, "Knight", 0, 4500, 11000))).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "with a Knight ahead and one beside it, far past the edge, nothing is found and the self"
          + " action runs on the searcher")
  void nothingInTheConeRunsTheSelfAction(@TempDir Path folder) throws IOException {
    Standard1v1Battle battle = new Standard1v1Battle(coneSearch(folder), LEVEL, false);
    play(battle, "MiniPekka", 0, 3500, 12000);
    play(battle, "Knight", 0, 3500, 13200);
    play(battle, "Knight", 0, 5000, 11700);
    stepTo(battle, 15);

    assertThat(variable(battle, at(battle, "MiniPekka", 0, 3500, 12000))).isEqualTo(5);
    assertThat(variable(battle, at(battle, "Knight", 0, 3500, 13200))).isZero();
    assertThat(variable(battle, at(battle, "Knight", 0, 5000, 11700))).isZero();
  }

  @Test
  @DisplayName("past the radius plus the collision radius a Knight behind is not found")
  void beyondTheRadiusNothingIsFound(@TempDir Path folder) throws IOException {
    Standard1v1Battle battle = new Standard1v1Battle(coneSearch(folder), LEVEL, false);
    play(battle, "MiniPekka", 0, 3500, 12000);
    play(battle, "Knight", 0, 3500, 9000);
    stepTo(battle, 15);

    assertThat(variable(battle, at(battle, "MiniPekka", 0, 3500, 12000))).isEqualTo(5);
    assertThat(variable(battle, at(battle, "Knight", 0, 3500, 9000))).isZero();
  }
}
