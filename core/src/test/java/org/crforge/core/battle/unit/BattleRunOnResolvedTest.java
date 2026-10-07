package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A newer data version's ActionRunActionOnResolvedGameObjects, as the Ice Wizard hero's ability
 * looks for the enemy it has slowed: a Global resolver collects what its filter lets through, asked
 * by the owner, the closest strategy keeps the nearest, and the action runs on it with the owner as
 * its cause and the context the search carried; when nothing is found the self action runs on the
 * owner. The filter's FilterIfNotBuffedByChecker keeps only an object carrying a listed buff the
 * owner applied, through a projectile it launched too.
 *
 * <p>In each scene the altered tables give one row a starting interval that searches every step and
 * writes TestVariable: 1 on what it finds, 5 on itself when it finds nothing. The enemies are Mini
 * Pekkas, which do not search.
 */
class BattleRunOnResolvedTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String START = "Test_search_start";

  private static final String SEARCH = "Test_search";

  private static final String RESOLVER = "Test_resolver";

  private static final String FILTER = "Test_filter";

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

  /**
   * The configured tables with the searcher's starting interval, the resolver (Global, closest) and
   * its filter: enemy characters, no buildings or towers, and with a checker the buffs the searcher
   * must have applied.
   *
   * @param searcher the character row that searches
   * @param checker the FilterIfNotBuffedByChecker buff, or null for none
   * @param rows more action rows, or null
   */
  private static GameTables search(
      Path folder, String searcher, String checker, Consumer<ObjectNode> rows) throws IOException {
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
          if (rows != null) {
            rows.accept(actions);
          }
        });
    alter(
        folder,
        "game_object_filters",
        filters -> {
          ObjectNode filter =
              GameData.columns(filters, "Enemy_Characters_No_Buildings_Or_Towers").deepCopy();
          if (checker != null) {
            filter.putArray("FilterIfNotBuffedByChecker").add(checker);
          }
          ObjectNode row = filters.putObject(FILTER);
          row.put("index", filters.size());
          row.put("class", "LogicGameObjectFilterData");
          row.set("columns", filter);
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
          columns.put("Shape", "MegaMinion_hero_shape");
          columns.putArray("StrategyList").add("RESOLVER_STRATEGY_CLOSEST_TARGET");
        });
    alter(
        folder,
        "characters",
        characters -> GameData.columns(characters, searcher).put("OnStartingAction", START));
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
  @DisplayName("the closest enemy is the one found, and the action runs on it alone")
  void theClosestEnemyIsFound(@TempDir Path folder) throws IOException {
    Standard1v1Battle battle =
        new Standard1v1Battle(search(folder, "Knight", null, null), LEVEL, false);
    play(battle, "Knight", 0, 3500, 12000);
    play(battle, "MiniPekka", 1, 3500, 20000);
    play(battle, "MiniPekka", 1, 12000, 18000);
    stepTo(battle, 40);

    assertThat(variable(battle, at(battle, "MiniPekka", 1, 3500, 20000))).isEqualTo(1);
    assertThat(variable(battle, at(battle, "MiniPekka", 1, 12000, 18000))).isZero();
  }

  @Test
  @DisplayName("with nothing to find the self action runs on the searcher")
  void nothingFoundRunsTheSelfAction(@TempDir Path folder) throws IOException {
    Standard1v1Battle battle =
        new Standard1v1Battle(search(folder, "Knight", null, null), LEVEL, false);
    play(battle, "Knight", 0, 3500, 12000);
    stepTo(battle, 40);

    assertThat(variable(battle, at(battle, "Knight", 0, 3500, 12000))).isEqualTo(5);
  }

  @Test
  @DisplayName(
      "the buff checker keeps an enemy the searcher's own projectile slowed: the Ice Wizard finds"
          + " the Mini Pekka it hit")
  void theCheckerKeepsWhatTheSearcherSlowed(@TempDir Path folder) throws IOException {
    Standard1v1Battle battle =
        new Standard1v1Battle(search(folder, "IceWizard", "IceWizardSlowDown", null), LEVEL, false);
    play(battle, "IceWizard", 0, 3500, 14000);
    play(battle, "MiniPekka", 1, 3500, 19000);
    stepTo(battle, 120);

    assertThat(variable(battle, at(battle, "MiniPekka", 1, 3500, 19000))).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "the buff checker drops an enemy another unit slowed: the Knight finds nothing though the"
          + " Ice Wizard beside it slowed the Mini Pekka")
  void theCheckerDropsWhatAnotherSlowed(@TempDir Path folder) throws IOException {
    Standard1v1Battle battle =
        new Standard1v1Battle(search(folder, "Knight", "IceWizardSlowDown", null), LEVEL, false);
    play(battle, "Knight", 0, 3500, 12000);
    play(battle, "IceWizard", 0, 6000, 14000);
    play(battle, "MiniPekka", 1, 3500, 19000);
    stepTo(battle, 120);

    CharacterEntity enemy = at(battle, "MiniPekka", 1, 3500, 19000);
    assertThat(enemy.getBuffs().items())
        .anySatisfy(buff -> assertThat(buff.getBuff().name()).isEqualTo("IceWizardSlowDown"));
    assertThat(variable(battle, enemy)).isZero();
    assertThat(variable(battle, at(battle, "Knight", 0, 3500, 12000))).isEqualTo(5);
  }

  @Test
  @DisplayName(
      "the context goes on: the found enemy writes its id (self) and length into the search's"
          + " context, a wait reads it and hands it to a filter, which checks the point with"
          + " is_valid_position and writes it on the searcher")
  void theContextCarriesThroughWaitAndFilter(@TempDir Path folder) throws IOException {
    Consumer<ObjectNode> rows =
        actions -> {
          ObjectNode start = (ObjectNode) actions.get(START).get("fields");
          actions.remove(START);
          ObjectNode group = action(actions, START, "ActionGroup");
          group.put("ContextMode", "Create");
          ArrayNode groupParts = group.putArray("SubActions");
          groupParts.addObject().put("action", "Test_interval");
          groupParts.addObject().put("action", "Test_wait");
          group.putArray("SubActionsDelay").add(0).add(0);
          ObjectNode interval = action(actions, "Test_interval", "ActionInterval");
          interval.setAll(start);
          interval.put("ClassType", "ActionInterval");
          ObjectNode found = action(actions, "Test_found", "ActionGroup");
          found.put("ContextMode", "Inherit");
          ArrayNode foundParts = found.putArray("SubActions");
          foundParts.addObject().put("action", "Test_write_id");
          foundParts.addObject().put("action", "Test_write_y");
          ObjectNode id = action(actions, "Test_write_id", "ActionBlackboardSetInt");
          id.put("Key", "FoundId");
          id.put("Value", "self");
          ObjectNode y = action(actions, "Test_write_y", "ActionBlackboardSetInt");
          y.put("Key", "FoundY");
          y.put("Value", "y");
          ObjectNode wait = action(actions, "Test_wait", "ActionWaitToActivate");
          wait.put("Condition", "as_int(#FoundId) != -1");
          wait.putObject("OnActivateAction").put("action", "Test_branch");
          ObjectNode branch = action(actions, "Test_branch", "ActionFilter");
          branch.put("Condition", "is_valid_position(3500, as_int(#FoundY, 0)) > 0");
          branch.putObject("OnTrueAction").put("action", "Test_from_context");
          branch.putObject("OnFalseAction").put("action", "Test_none");
          write(actions, "Test_from_context", "as_int(#FoundId, 0)");
        };
    Standard1v1Battle battle =
        new Standard1v1Battle(search(folder, "Knight", null, rows), LEVEL, false);
    play(battle, "Knight", 0, 3500, 12000);
    play(battle, "MiniPekka", 1, 3500, 20000);
    stepTo(battle, 40);

    CharacterEntity enemy = at(battle, "MiniPekka", 1, 3500, 20000);
    assertThat(variable(battle, at(battle, "Knight", 0, 3500, 12000))).isEqualTo(enemy.getId());
  }

  @Test
  @DisplayName("is_valid_position answers 0 on a water cell and off the map, 1 on land")
  void validPositionReadsTheMap(@TempDir Path folder) throws IOException {
    Consumer<ObjectNode> rows =
        actions -> {
          ObjectNode start = action(actions, "Test_points", "ActionGroup");
          List<String> names = List.of("Test_land", "Test_water", "Test_off");
          ArrayNode parts = start.putArray("SubActions");
          names.forEach(name -> parts.addObject().put("action", name));
          start.putArray("SubActionsDelay").add(0).add(0).add(0);
        };
    TileMap map = TileMap.standard1v1();
    int waterRow = -1;
    for (int row = 0; row < map.height() && waterRow < 0; row++) {
      if (map.isWater(7, row)) {
        waterRow = row;
      }
    }
    int waterY = waterRow * TileMap.CELL_UNITS + 250;
    Consumer<ObjectNode> points =
        rows.andThen(
            actions -> {
              writeTo(actions, "Test_land", "is_valid_position(3500, 12000)", "LandValid");
              writeTo(
                  actions, "Test_water", "is_valid_position(3500, " + waterY + ")", "WaterValid");
              writeTo(actions, "Test_off", "is_valid_position(-1, 12000)", "OffValid");
            });
    GameTables tables = search(folder, "Knight", null, points);
    alter(
        folder,
        "variables",
        variables -> {
          for (String name : List.of("LandValid", "WaterValid", "OffValid")) {
            ObjectNode row = variables.putObject(name);
            row.put("index", variables.size());
            row.put("class", "LogicVariableData");
            row.putObject("columns");
          }
        });
    alter(
        folder,
        "characters",
        characters ->
            GameData.columns(characters, "Knight").put("OnStartingAction", "Test_points"));
    tables = GameTables.load(folder);
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, false);
    play(battle, "Knight", 0, 3500, 12000);
    stepTo(battle, 40);

    CharacterEntity knight = at(battle, "Knight", 0, 3500, 12000);
    BattleWorld world = battle.getWorld();
    assertThat(knight.variable(world.variableKey("LandValid"))).isEqualTo(1);
    assertThat(knight.variable(world.variableKey("WaterValid"))).isZero();
    assertThat(knight.variable(world.variableKey("OffValid"))).isZero();
  }

  /** A write of an expression into a named variable. */
  private static void writeTo(ObjectNode rows, String name, String value, String variable) {
    ObjectNode fields = action(rows, name, "ActionSetVariable");
    fields.put("Variable", variable);
    fields.put("Value", value);
  }
}
