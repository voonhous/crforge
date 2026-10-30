/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.data;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.crforge.core.battle.filter.GameObjectFilter;

/** The game object filters of the battle, built from the game object filters table. */
final class FilterRecords extends RowReader {

  private static final String GAME_OBJECT_FILTERS = "game_object_filters";

  /** The columns of a game object filter's row that only show something: the text it shows. */
  private static final Set<String> PRESENTATION_FILTER_COLUMNS = Set.of("FilterDescriptionTID");

  /**
   * The kinds of object a game object filter's Filters list may name, each with the switch of the
   * same test: a filter that lists a kind leaves out what the switch leaves out. The shipped rows
   * write the kinds as this list in place of the switches; the filter's test reads each listed kind
   * as the very check the switch asks (the same object queries, and the same character row columns
   * for the pushback and dash checks). The kinds the switches have no test for are {@link
   * #LIST_ONLY_FILTER_KINDS}; any other kind is refused.
   */
  private static final Map<String, String> FILTER_LIST_SWITCHES =
      Map.ofEntries(
          Map.entry("Hidden", "FilterHidden"),
          Map.entry("Invisible", "FilterInvisible"),
          Map.entry("Underground", "FilterUnderground"),
          Map.entry("Buildings", "FilterBuildings"),
          Map.entry("Towers", "FilterTowers"),
          Map.entry("Summoner", "FilterSummoner"),
          Map.entry("Flying", "FilterFlying"),
          Map.entry("Jumping", "FilterJumping"),
          Map.entry("DashImmune", "FilterDashImmune"),
          Map.entry("Dragging", "FilterDragging"),
          Map.entry("Cloning", "FilterCloning"),
          Map.entry("NoHitpointComponent", "FilterIfNoHitpointComponent"),
          Map.entry("PushbackIgnore", "FilterPushbackIgnore"),
          Map.entry("SameObjects", "FilterSameObjects"),
          Map.entry("PrincessTowers", "FilterPrincessTowers"),
          Map.entry("Clones", "FilterClones"));

  /** The switch columns of {@link #FILTER_LIST_SWITCHES}, each with the kind that names it. */
  private static final Map<String, String> FILTER_SWITCH_KINDS =
      FILTER_LIST_SWITCHES.entrySet().stream()
          .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

  /**
   * The kinds a Filters list may name that no switch tests: Self, the asker itself; Kamikaze, a
   * character whose row sets Kamikaze; IgnoreResurrect, a character whose row sets IgnoreResurrect.
   */
  private static final Set<String> LIST_ONLY_FILTER_KINDS =
      Set.of("Self", "Kamikaze", "IgnoreResurrect");

  FilterRecords(GameTables tables, BattleRecords records) {
    super(tables, records);
  }

  /**
   * A game object filter as the battle reads it, from the game object filters table. Every column
   * is its field of the same name; the dead are filtered unless the row says not, and the dash
   * immunity exclusion counts the immunity left after a dash unless the row writes
   * RespectDodgingDamageCooldown off (the 16.635.1 loader's default is on); the tags it excludes,
   * written as names separated by commas, are the bits the game tags table gives them; the text the
   * game shows for it is not read. The kinds of object it leaves out are its Filter switches, or
   * the names of its Filters list, which the shipped rows write in their place and which may also
   * name three kinds no switch tests (Self, Kamikaze and IgnoreResurrect); MatchSelf passes only
   * the object that asks, and its two buff checkers keep or drop an object by the buffs the asker
   * applied to it. A row that sets any other column, as a filter it builds on, is refused: read
   * without it, the filter would match what the row does not.
   *
   * <p>A name the game object filters table does not hold is refused too, naming the row. The
   * game's own data may define a filter row outside the files the tables are made from; read as no
   * filter, the action that names it would find nothing where the game finds its objects.
   *
   * @param name the row's name
   * @throws UnsupportedOperationException for a row the tables do not hold, or one that sets a
   *     column not modelled
   */
  GameObjectFilter filter(String name) {
    GameTable table = tables.table(GAME_OBJECT_FILTERS);
    if (!table.has(name)) {
      throw new UnsupportedOperationException(
          "the game object filter " + name + ", which the game tables do not hold");
    }
    GameRow row = table.row(name).tracking();
    GameObjectFilter filter = filterOf(row);
    boolean baseResolved = baseResolved(table, row);
    List<String> unread = new ArrayList<>();
    for (String column : row.columns().keySet()) {
      if (!row.read().contains(column)
          && !PRESENTATION_FILTER_COLUMNS.contains(column)
          && !(column.equals("Base") && baseResolved)
          && sets(row, column)) {
        unread.add(column);
      }
    }
    if (!unread.isEmpty()) {
      Collections.sort(unread);
      throw new UnsupportedOperationException(
          "the game object filter " + name + " sets columns not modelled: " + unread);
    }
    return filter;
  }

  /**
   * Whether a filter row's Base, written "FILTER.name", is already resolved in the row: the base
   * row is a filter of this table and every column it sets the row sets to the same value, so the
   * row read alone is the filter the base and the row make together. Any other Base is left unread,
   * and refused.
   */
  private static boolean baseResolved(GameTable table, GameRow row) {
    // Read from the raw columns, so that a Base not resolved stays unread and is refused.
    JsonNode value = row.columns().get("Base");
    if (value == null || !value.isTextual() || !value.asText().startsWith("FILTER.")) {
      return false;
    }
    String baseName = value.asText().substring("FILTER.".length());
    if (!table.has(baseName)) {
      return false;
    }
    for (Map.Entry<String, JsonNode> column : table.row(baseName).columns().entrySet()) {
      if (!column.getValue().equals(row.columns().get(column.getKey()))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether a game object filter leaves out the kind of object a switch names: the switch's own
   * column, or, for a row that writes the kinds as a Filters list, the list naming the kind. A row
   * writes one form or the other; one that writes both, or lists a kind no switch tests, is
   * refused.
   *
   * @param row the filter's row
   * @param listed the kinds its Filters list names, or null for a row without the list
   * @param column the switch's column
   */
  private static boolean filterSwitch(GameRow row, Set<String> listed, String column) {
    if (listed == null) {
      return row.bool(column);
    }
    if (row.bool(column)) {
      throw new UnsupportedOperationException(
          "the game object filter "
              + row.name()
              + " writes both a Filters list and the switch "
              + column
              + ", which no data version writes together");
    }
    return listed.contains(FILTER_SWITCH_KINDS.get(column));
  }

  /**
   * Whether a game object filter's Filters list names a kind no switch tests; false for a row
   * without the list.
   *
   * @param listed the kinds its Filters list names, or null for a row without the list
   * @param kind one of {@link #LIST_ONLY_FILTER_KINDS}
   */
  private static boolean filterListOnly(Set<String> listed, String kind) {
    return listed != null && listed.contains(kind);
  }

  /**
   * The kinds a filter's Filters list names, or null for a row without the list. A row may write
   * the list as a single text, which the game reads as a list of that one kind.
   *
   * @throws UnsupportedOperationException for a kind neither a switch nor the filter's own test of
   *     a listed kind reads
   */
  private static Set<String> listedKinds(GameRow row) {
    if (!row.has("Filters")) {
      return null;
    }
    List<String> kinds =
        row.value("Filters").isTextual() ? List.of(row.string("Filters")) : row.strings("Filters");
    Set<String> listed = new HashSet<>();
    for (String kind : kinds) {
      if (!FILTER_LIST_SWITCHES.containsKey(kind) && !LIST_ONLY_FILTER_KINDS.contains(kind)) {
        throw new UnsupportedOperationException(
            "the game object filter "
                + row.name()
                + " lists the kind "
                + kind
                + " in Filters, which is not modelled");
      }
      listed.add(kind);
    }
    return listed;
  }

  /**
   * A game object filter's fields, read from its row's columns of the same names, or, for the kinds
   * it leaves out, from its Filters list.
   */
  private GameObjectFilter filterOf(GameRow row) {
    Set<String> listed = listedKinds(row);
    return GameObjectFilter.builder()
        .matchTeamOwn(row.bool("MatchTeamOwn"))
        .matchTeamEnemy(row.bool("MatchTeamEnemy"))
        .matchTypeCharacters(row.bool("MatchTypeCharacters"))
        .matchTypeBuildings(row.bool("MatchTypeBuildings"))
        .matchTypeProjectiles(row.bool("MatchTypeProjectiles"))
        .matchTypeAoe(row.bool("MatchTypeAoe"))
        .matchTypeGoblinRef(row.bool("MatchTypeGoblinRef"))
        .matchTowers(row.bool("MatchTowers"))
        .filterHidden(filterSwitch(row, listed, "FilterHidden"))
        .filterInvisible(filterSwitch(row, listed, "FilterInvisible"))
        .filterUnderground(filterSwitch(row, listed, "FilterUnderground"))
        .filterBuildings(filterSwitch(row, listed, "FilterBuildings"))
        .filterTowers(filterSwitch(row, listed, "FilterTowers"))
        .filterSummoner(filterSwitch(row, listed, "FilterSummoner"))
        .filterFlying(filterSwitch(row, listed, "FilterFlying"))
        .filterJumping(filterSwitch(row, listed, "FilterJumping"))
        .filterDashImmune(filterSwitch(row, listed, "FilterDashImmune"))
        .filterDragging(filterSwitch(row, listed, "FilterDragging"))
        .filterCloning(filterSwitch(row, listed, "FilterCloning"))
        .filterIfNoHitpointComponent(filterSwitch(row, listed, "FilterIfNoHitpointComponent"))
        .filterPushbackIgnore(filterSwitch(row, listed, "FilterPushbackIgnore"))
        .matchAttachedChildren(row.bool("MatchAttachedChildren"))
        .filterSameObjects(filterSwitch(row, listed, "FilterSameObjects"))
        .filterTags(tagBits(row.string("FilterTags")))
        .filterPrincessTowers(filterSwitch(row, listed, "FilterPrincessTowers"))
        .filterDead(!row.has("FilterDead") || row.bool("FilterDead"))
        // A row that leaves it out takes the class default of the client the battle follows:
        // client 16.635.1 loads it on. A row that writes it off turns it off.
        .respectDodgingDamageCooldown(
            !row.has("RespectDodgingDamageCooldown") || row.bool("RespectDodgingDamageCooldown"))
        .filterClones(filterSwitch(row, listed, "FilterClones"))
        .matchSelf(row.bool("MatchSelf"))
        .filterSelf(filterListOnly(listed, "Self"))
        .filterKamikaze(filterListOnly(listed, "Kamikaze"))
        .filterIgnoreResurrect(filterListOnly(listed, "IgnoreResurrect"))
        .includeCharactersWithData(Set.copyOf(row.strings("IncludeCharactersWithData")))
        .excludeCharactersWithData(Set.copyOf(row.strings("ExcludeCharactersWithData")))
        .requireBuffsFromAsker(Set.copyOf(row.strings("FilterIfNotBuffedByChecker")))
        .refuseBuffsFromAsker(Set.copyOf(row.strings("FilterIfBuffedByChecker")))
        .build();
  }
}
