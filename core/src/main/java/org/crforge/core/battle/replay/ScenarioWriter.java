/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.replay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTable;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.data.GameVersions;
import org.crforge.core.battle.match.EvolutionItem;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.match.MirrorItem;
import org.crforge.core.battle.match.VariantItem;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.Standard1v1Battle;

/**
 * Writes a battle as a replay of the tables' data version, the document {@link ReplayScenario}
 * reads ({@link ScenarioShape#REPLAY}): reading it back and building its battle with {@link
 * ReplayBattle#build} gives the same battle, step for step. It is the reader's inverse and holds
 * nothing but the format.
 *
 * <p>{@link #plan} takes a battle played as a Ladder match (its towers, seed, players' data and
 * words, decks with their slot flags, and every player's command queued on it, {@link
 * Standard1v1Battle#getCommands}) with each player's arena its caller names as a translated
 * scenario; {@link #write} writes a translated scenario as the document. Each play carries the
 * packed item its player's client built: for a play that has run, the item the battle built as it
 * ran; for one that has not, the item its side builds now, which the replay's run checks as it runs
 * ({@link ReplayBattle#checkItems}). So a battle written before its last commands run is read back
 * whole and plays the same up to the tick it was written at.
 *
 * <p>Refused, with the reason: a battle outside a match; a side with no arena named, or one named
 * that is no row of the arenas table (the battle holds no arena: a replay's avatar names its
 * player's arena row, so the caller of a fresh battle names it, and a replay's plan keeps its own);
 * a deck card played at two levels, as a replay gives each deck card one level; a Mirror whose item
 * repeats nothing; a variant play whose option the battle has not picked yet; an ability command
 * naming a unit no play made; and any input the version's replays cannot carry (a tower selection
 * no support card places, a level below the rarity's first, players' data other than one choice
 * each, a data version whose command types are not established).
 *
 * <p>The writer fills the fields a replay must hold and the battle does not read with the values
 * the reader pins, or with neutral ones where the reader carries them: the time and the battle
 * header's arena 0, no events, no emotes listed in each player's data, and each player named by its
 * side. Each avatar's arena is the plan's, a row of the arenas table, by its data id.
 */
public final class ScenarioWriter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The tables of the card rows a deck holds: the characters', buildings' and others' cards. */
  private static final List<String> DECK_CARD_TABLES =
      List.of("spells_characters", "spells_buildings", "spells_other");

  /** The tables a Mirror's repeated card is looked for in, after the deck card tables. */
  private static final List<String> REPEATED_CARD_TABLES =
      List.of("spells_hero_form", "spells_evolved");

  /** The table of the tower selections, the support cards. */
  private static final String SUPPORT_CARDS = "support_cards";

  /** The table of the locations. */
  private static final String LOCATIONS = "locations";

  /** The table of the arenas, whose row each player's avatar names. */
  public static final String ARENAS = "arenas";

  private final GameTables tables;

  /**
   * @param tables the game tables the battle is played on, whose data version's replay fields and
   *     command types the document is written in
   */
  public ScenarioWriter(GameTables tables) {
    this.tables = tables;
  }

  /**
   * A battle played as a Ladder match, as a translated scenario: its seed, towers, players' data,
   * decks and slot flags, each side's account (the side as the high word, its player's word as the
   * low word) and arena, and every player's command queued on it, in the order queued, each named
   * {@code cmd<index>} by its place.
   *
   * @param battle the battle, its match started
   * @param level the level of a deck card no command plays, counted from 1: a replay gives every
   *     deck card a level, and the battle holds only the levels its plays were given at
   * @param arenas each side's arena, a row name of the arenas table: the battle holds none, and a
   *     replay's avatar names its player's arena, so its caller names it
   * @return the scenario, as {@link ReplayScenario#translate} would read it
   * @throws IllegalArgumentException when a side has no arena named, or one that is no row of the
   *     arenas table
   * @throws UnsupportedOperationException for a battle the version's replays cannot hold, naming
   *     why
   */
  public ScenarioPlan plan(Standard1v1Battle battle, int level, List<String> arenas) {
    List<String> named = arenas(arenas);
    LadderMatch match = battle.getMatch();
    if (match == null) {
      throw new UnsupportedOperationException(
          "a battle outside a match, which no replay holds: a replay plays a Ladder match");
    }
    List<List<String>> decks = new ArrayList<>();
    List<int[]> deckLevels = new ArrayList<>();
    List<int[]> slotFlags = new ArrayList<>();
    List<int[]> accounts = new ArrayList<>();
    int[] words = battle.getPlayerWords();
    for (int side = 0; side < 2; side++) {
      MatchSide own = match.side(side);
      List<String> names = own.deck().stream().map(MatchCard::name).toList();
      int[] flags = new int[names.size()];
      for (int index = 0; index < names.size(); index++) {
        flags[index] = own.slotFlags(index);
      }
      decks.add(names);
      deckLevels.add(new int[names.size()]);
      slotFlags.add(flags);
      accounts.add(new int[] {side, words[side]});
    }
    // Each deck card's level is the one its plays were given at.
    for (Standard1v1Battle.Command command : battle.getCommands()) {
      if (command.kind() == Standard1v1Battle.CommandKind.ABILITY) {
        continue;
      }
      int index = match.deckIndex(command.side(), command.card());
      int[] levels = deckLevels.get(command.side());
      if (levels[index] != 0 && levels[index] != command.level()) {
        throw new UnsupportedOperationException(
            "side "
                + command.side()
                + " plays "
                + command.card()
                + " at levels "
                + levels[index]
                + " and "
                + command.level()
                + ", where a replay gives each deck card one level");
      }
      levels[index] = command.level();
    }
    for (int[] levels : deckLevels) {
      for (int index = 0; index < levels.length; index++) {
        levels[index] = levels[index] == 0 ? level : levels[index];
      }
    }
    List<ScenarioPlan.Play> plays = new ArrayList<>();
    List<ScenarioPlan.Ability> abilities = new ArrayList<>();
    List<Standard1v1Battle.Command> commands = battle.getCommands();
    for (int index = 0; index < commands.size(); index++) {
      Standard1v1Battle.Command command = commands.get(index);
      if (command.kind() == Standard1v1Battle.CommandKind.ABILITY) {
        abilities.add(
            new ScenarioPlan.Ability(
                index, command.given(), command.tick(), command.side(), objectId(battle, command)));
      } else {
        plays.add(play(battle, command, index, slotFlags.get(command.side())));
      }
    }
    return new ScenarioPlan(
        battle.getWorld().getSeed(),
        battle.getTowers(),
        decks,
        deckLevels,
        slotFlags,
        accounts,
        named,
        List.copyOf(battle.getPlayerDataChoices()),
        plays,
        abilities);
  }

  /** A play's command with the packed item its client built. */
  private ScenarioPlan.Play play(
      Standard1v1Battle battle, Standard1v1Battle.Command command, int index, int[] slotFlags) {
    LadderMatch match = battle.getMatch();
    int side = command.side();
    int deckIndex = match.deckIndex(side, command.card());
    Standard1v1Battle.Play ran = ran(battle, command.name());
    // The parts of the item its deck card decides: the level field, the slot flags, the deck index.
    int fromDeck =
        ((command.level() - 1) << ReplayScenario.ITEM_LEVEL_SHIFT)
            | ((deckIndex + 1) << ReplayScenario.ITEM_INDEX_SHIFT);
    ScenarioPlan.Repeated repeats = null;
    ScenarioPlan.Option option = null;
    int item;
    switch (command.kind()) {
      case MIRROR -> {
        MirrorItem built =
            ran != null && ran.mirror() != null
                ? ran.mirror()
                : match.mirrorItem(side, deckIndex, command.level());
        if (built.repeats() == null) {
          throw new UnsupportedOperationException(
              command.name()
                  + ": a Mirror whose item repeats nothing, which a replay's Mirror item cannot"
                  + " carry");
        }
        item =
            (built.levelField() << ReplayScenario.ITEM_LEVEL_SHIFT)
                | ((built.index() + 1) << ReplayScenario.ITEM_INDEX_SHIFT)
                | (built.cost() << ReplayScenario.ITEM_COST_SHIFT);
        String name = built.repeats().name();
        repeats = new ScenarioPlan.Repeated(repeatedId(name), name);
      }
      case VARIANT -> {
        VariantItem built = ran != null ? ran.variant() : null;
        int picked = command.option();
        if (picked < 0 && built != null) {
          picked = built.option();
        }
        if (picked < 0) {
          throw new UnsupportedOperationException(
              command.name()
                  + ": a variant play whose option the battle has not picked yet, which its item"
                  + " carries");
        }
        if (built == null) {
          built = match.variantItem(side, deckIndex, picked);
        }
        item =
            fromDeck
                | ((picked + 1) << ReplayScenario.ITEM_OPTION_SHIFT)
                | (built.cost() << ReplayScenario.ITEM_COST_SHIFT);
        option = new ScenarioPlan.Option(picked, built.spell(), built.cost());
      }
      default -> {
        EvolutionItem built =
            ran != null && ran.evolution() != null ? ran.evolution() : match.item(side, deckIndex);
        int flags = slotFlags[deckIndex];
        // The count field holds an evolution slot's count plus 1, and nothing for any other card.
        int count = (flags & MatchSide.EVOLUTION_SLOT) != 0 ? built.count() + 1 : 0;
        item =
            fromDeck
                | (flags << ReplayScenario.ITEM_FLAGS_SHIFT)
                | built.field()
                | (count << ReplayScenario.ITEM_COUNT_SHIFT)
                | (built.cost() << ReplayScenario.ITEM_COST_SHIFT);
      }
    }
    return new ScenarioPlan.Play(
        index,
        command.given(),
        command.tick(),
        side,
        command.card(),
        command.level(),
        command.x(),
        command.y(),
        item,
        repeats,
        option);
  }

  /** The play run under a name, or null while it has not run. */
  private static Standard1v1Battle.Play ran(Standard1v1Battle battle, String name) {
    for (Standard1v1Battle.Play play : battle.getPlays()) {
      if (play.name().equals(name)) {
        return play;
      }
    }
    return null;
  }

  /**
   * The game object id of the unit an ability command names: its own, or that of the unit a play
   * made under the name it gives.
   */
  private static int objectId(Standard1v1Battle battle, Standard1v1Battle.Command command) {
    if (command.objectId() >= 0) {
      return command.objectId();
    }
    for (Standard1v1Battle.Play play : battle.getPlays()) {
      for (CharacterEntity unit : play.units()) {
        if (unit.name().equals(command.unit()) && unit.side() == command.side()) {
          return unit.getId();
        }
      }
    }
    throw new UnsupportedOperationException(
        command.name()
            + " names "
            + command.unit()
            + ", which no play of side "
            + command.side()
            + " made: a replay's ability command names its unit by game object id");
  }

  /**
   * Writes a translated scenario as a replay of the tables' data version, with no last tick.
   *
   * @param plan the scenario
   * @return the replay document
   */
  public ObjectNode write(ScenarioPlan plan) {
    return write(plan, -1);
  }

  /**
   * The game client that runs a data version, as a capture block names it, or null when none of the
   * clients the simulator knows runs it.
   */
  private static String clientOf(String dataVersion) {
    if (GameVersions.CLIENT_16_635_1_DATA.contains(dataVersion)) {
      return GameVersions.CLIENT_16_635_1;
    }
    if (GameVersions.CLIENT_16_402_17_DATA.contains(dataVersion)) {
      return GameVersions.CLIENT_16_402_17;
    }
    return null;
  }

  /**
   * Writes a translated scenario as a replay of the tables' data version: every field the version's
   * replays hold, a capture block naming the tables' data when the client of their data version is
   * known, and the last tick, where a viewer stops, when one is given.
   *
   * @param plan the scenario
   * @param endTick the replay's last tick, or -1 for none
   * @return the replay document
   * @throws UnsupportedOperationException for a scenario the version's replays cannot hold
   */
  public ObjectNode write(ScenarioPlan plan, int endTick) {
    CommandTypes types =
        CommandTypes.of(tables.version())
            .orElseThrow(
                () ->
                    new UnsupportedOperationException(
                        "a replay of data version "
                            + tables.version()
                            + ", whose command types are not established"));
    ReplayFormat format = ReplayFormat.orShared(tables.version());
    ObjectNode document = MAPPER.createObjectNode();
    document.put("rndSeed", plan.seed());
    document.put("time", 0);
    if (endTick >= 0) {
      document.put("endTick", endTick);
    }
    ObjectNode battle = document.putObject("battle");
    Map<String, String> header = ReplayScenario.LADDER_HEADER;
    battle.put("gmt", Integer.parseInt(header.get("gmt")));
    battle.put("plt", 1);
    battle.put("t1s", 0);
    battle.put("t2s", 0);
    battle.put("gamemode", id(tables.table(ReplayScenario.GAME_MODES), LadderMatch.GAME_MODE));
    battle.put("hm", false);
    battle.put("lvlcap", Integer.parseInt(header.get("lvlcap")));
    battle.put("stp", 0);
    battle.put("trail", Integer.parseInt(header.get("trail")));
    battle.put("te", -1);
    battle.put("tps", 1);
    battle.put("arena", 0);
    if (format.ladderEvents()) {
      // No card level minimum: every card keeps its own level. Written where the formats that pin
      // it write it, first of the pinned fields.
      battle.put(ReplayFormat.CARD_LEVEL_MIN_FIELD, 0);
    }
    pins(battle, format.battlePins());
    for (int side = 0; side < 2; side++) {
      battle.set("deck" + side, deck(plan, side, format));
      ObjectNode avatar = battle.putObject("avatar" + side);
      avatar.put("accountID.hi", plan.accounts().get(side)[0]);
      avatar.put("accountID.lo", plan.accounts().get(side)[1]);
      pins(avatar, format.avatarPins());
      avatar.put("name", "Side " + side);
      avatar.put("arena", arenaId(plan.arenas(), side));
    }
    battle.put("location", standardLocation());
    battle.set("hbd", playerData(plan, format));
    ArrayNode commands = document.putArray("cmd");
    List<Object> ordered = new ArrayList<>(plan.plays());
    ordered.addAll(plan.abilities());
    ordered.sort(Comparator.comparingInt(ScenarioWriter::commandIndex));
    for (Object command : ordered) {
      if (command instanceof ScenarioPlan.Play play) {
        commands.add(play(play, plan, types));
      } else {
        commands.add(ability((ScenarioPlan.Ability) command, plan, types));
      }
    }
    document.putArray("evt");
    pins(document, format.rootPins());
    String client = clientOf(tables.version());
    if (tables.contentSha() != null && client != null) {
      ObjectNode capture = document.putObject(ReplayCapture.FIELD);
      capture.put(ReplayCapture.CLIENT_VERSION, client);
      capture.put(ReplayCapture.CONTENT_VERSION, tables.version());
      capture.put(ReplayCapture.CONTENT_SHA, tables.contentSha());
    }
    return document;
  }

  private static int commandIndex(Object command) {
    return command instanceof ScenarioPlan.Play play
        ? play.index()
        : ((ScenarioPlan.Ability) command).index();
  }

  /** A side's deck: its cards with their level indices and slot flags, and its towers. */
  private ObjectNode deck(ScenarioPlan plan, int side, ReplayFormat format) {
    ObjectNode deck = MAPPER.createObjectNode();
    ArrayNode cards = deck.putArray("sp");
    List<String> names = plan.decks().get(side);
    for (int index = 0; index < names.size(); index++) {
      GameRow card = deckCard(names.get(index));
      int level = plan.deckLevels().get(side)[index];
      int levelIndex =
          level - tables.table("rarities").row(card.string("Rarity")).intValue("RelativeLevel") - 1;
      if (levelIndex < 0) {
        throw new UnsupportedOperationException(
            "side "
                + side
                + "'s "
                + card.name()
                + " at level "
                + level
                + ", below its rarity's first level, which a replay's level index cannot carry");
      }
      ObjectNode entry = cards.addObject();
      entry.put("d", deckCardId(card.name()));
      entry.put("l", levelIndex);
      int flags = plan.slotFlags().get(side)[index];
      if (flags != 0) {
        entry.put("el", flags);
      }
    }
    Standard1v1Battle.Towers towers = plan.towers().get(side);
    GameRow selection = null;
    for (GameRow row : tables.table(SUPPORT_CARDS).rows()) {
      if (ReplayScenario.BUILT_TOWER_SELECTIONS.contains(row.name())
          && towers.spawnGroup().equals(row.string("SpawnGroup"))) {
        selection = row;
        break;
      }
    }
    if (selection == null) {
      throw new UnsupportedOperationException(
          "side "
              + side
              + "'s towers of spawn group "
              + towers.spawnGroup()
              + ", which no tower"
              + " selection a replay can name places");
    }
    GameRow rarity = tables.table(ReplayScenario.SUPPORT_RARITIES).row(selection.string("Rarity"));
    int levelIndex = towers.level() - rarity.intValue("RelativeLevel") - 1;
    if (levelIndex < 0 || levelIndex >= rarity.intValue("LevelCount")) {
      throw new UnsupportedOperationException(
          "side "
              + side
              + "'s towers at level "
              + towers.level()
              + ", outside the levels of the tower selection's rarity "
              + rarity.name());
    }
    ObjectNode tower = deck.putArray("sc").addObject();
    tower.put("d", id(tables.table(SUPPORT_CARDS), selection));
    tower.put("l", levelIndex);
    pins(tower, format.selectionPins());
    return deck;
  }

  /**
   * The players' data: in a format that gives each side's king level there, one entry per side
   * holding it beside an emote list that lists none, as the game's replays give every entry its
   * emote list (an entry without one is not an entry the game writes); otherwise one entry with no
   * emotes listed per player's data, whose kings stand at the first level.
   */
  private ArrayNode playerData(ScenarioPlan plan, ReplayFormat format) {
    for (int choices : plan.playerDataChoices()) {
      if (choices != ReplayScenario.NO_EMOTES_CHOICES) {
        throw new UnsupportedOperationException(
            "a player's data of "
                + choices
                + " choices, where a replay's data of a player lists "
                + ReplayScenario.NO_EMOTES_CHOICES);
      }
    }
    ArrayNode entries = MAPPER.createArrayNode();
    if (format.kingLevelFromPlayerData()) {
      if (plan.playerDataChoices().size() != 2) {
        throw new UnsupportedOperationException(
            plan.playerDataChoices().size()
                + " players' data, where a replay of data version "
                + tables.version()
                + " holds one per side");
      }
      for (int side = 0; side < 2; side++) {
        // The entry with no emotes listed, its king level written beside its emotes.
        ObjectNode entry = (ObjectNode) json(ReplayScenario.NO_EMOTES);
        entries
            .addObject()
            .put(ReplayFormat.KING_LEVEL_FIELD, plan.towers().get(side).kingLevel())
            .setAll(entry);
      }
      return entries;
    }
    for (int side = 0; side < 2; side++) {
      if (plan.towers().get(side).kingLevel() != ReplayScenario.KING_LEVEL) {
        throw new UnsupportedOperationException(
            "side "
                + side
                + "'s king at level "
                + plan.towers().get(side).kingLevel()
                + ", where a replay of data version "
                + tables.version()
                + " stands it at "
                + ReplayScenario.KING_LEVEL);
      }
    }
    for (int i = 0; i < plan.playerDataChoices().size(); i++) {
      entries.add(json(ReplayScenario.NO_EMOTES));
    }
    return entries;
  }

  private ObjectNode play(ScenarioPlan.Play play, ScenarioPlan plan, CommandTypes types) {
    ObjectNode command = MAPPER.createObjectNode();
    command.put("ct", types.play());
    ObjectNode body = command.putObject("c");
    body.put("t", play.givenTick());
    body.put("t2", play.runTick());
    body.put("idHi", plan.accounts().get(play.side())[0]);
    body.put("idLo", plan.accounts().get(play.side())[1]);
    body.put("px", play.x());
    body.put("py", play.y());
    body.put("sid", -1);
    ObjectNode item = body.putObject("sel");
    item.put("os", deckCardId(play.card()));
    if (play.repeats() != null) {
      item.put("fs", play.repeats().id());
    }
    item.put("pd", play.item());
    return command;
  }

  private static ObjectNode ability(
      ScenarioPlan.Ability ability, ScenarioPlan plan, CommandTypes types) {
    ObjectNode command = MAPPER.createObjectNode();
    command.put("ct", types.ability());
    ObjectNode body = command.putObject("c");
    body.put("t", ability.givenTick());
    body.put("t2", ability.runTick());
    body.put("idHi", plan.accounts().get(ability.side())[0]);
    body.put("idLo", plan.accounts().get(ability.side())[1]);
    body.put("cgid", ability.objectId());
    return command;
  }

  /**
   * Each side's arena, checked: one name per side, each a row of the arenas table.
   *
   * @throws IllegalArgumentException when a side has none, or one the table does not hold
   * @throws UnsupportedOperationException when the tables hold no arenas table
   */
  private List<String> arenas(List<String> arenas) {
    if (arenas == null || arenas.size() != 2) {
      throw new IllegalArgumentException(
          "no arena named for each side"
              + (arenas == null ? "" : " (" + arenas.size() + " named)")
              + ": a replay's avatar names its player's arena, a row of the "
              + ARENAS
              + " table, which the battle does not hold, so the caller names it");
    }
    if (!tables.tableNames().contains(ARENAS)) {
      throw new UnsupportedOperationException(
          "the tables of data version "
              + tables.version()
              + " hold no "
              + ARENAS
              + " table, whose row a replay's avatar names");
    }
    for (int side = 0; side < 2; side++) {
      String name = arenas.get(side);
      if (name == null) {
        throw new IllegalArgumentException(
            "no arena named for side "
                + side
                + ": a replay's avatar names its player's arena, a row of the "
                + ARENAS
                + " table");
      }
      if (!tables.table(ARENAS).has(name)) {
        throw new IllegalArgumentException(
            "side "
                + side
                + "'s arena "
                + name
                + " is no row of the "
                + ARENAS
                + " table of data version "
                + tables.version());
      }
    }
    return List.copyOf(arenas);
  }

  /** A side's arena's data id, the plan's arena row checked. */
  private int arenaId(List<String> arenas, int side) {
    return id(tables.table(ARENAS), arenas(arenas).get(side));
  }

  /** The first location whose map is the standard arena's. */
  private int standardLocation() {
    GameTable locations = tables.table(LOCATIONS);
    for (GameRow row : locations.rows()) {
      if (ReplayScenario.STANDARD_TILE_MAP.equals(row.string("TileDataFileName"))) {
        return id(locations, row);
      }
    }
    throw new UnsupportedOperationException(
        "no location of the tables is on the map " + ReplayScenario.STANDARD_TILE_MAP);
  }

  /** A deck card's row, from the card tables a deck holds. */
  private GameRow deckCard(String name) {
    for (String table : DECK_CARD_TABLES) {
      if (tables.table(table).has(name)) {
        return tables.table(table).row(name);
      }
    }
    throw new IllegalArgumentException("no deck card row " + name);
  }

  /** The data id of the card row a Mirror's item repeats, from the card tables. */
  private int repeatedId(String name) {
    List<String> names = new ArrayList<>(DECK_CARD_TABLES);
    names.addAll(REPEATED_CARD_TABLES);
    for (String table : names) {
      if (tables.tableNames().contains(table) && tables.table(table).has(name)) {
        return id(tables.table(table), tables.table(table).row(name));
      }
    }
    throw new IllegalArgumentException("no card row " + name);
  }

  /** A deck card's data id. */
  private int deckCardId(String name) {
    for (String table : DECK_CARD_TABLES) {
      if (tables.table(table).has(name)) {
        return id(tables.table(table), tables.table(table).row(name));
      }
    }
    throw new IllegalArgumentException("no deck card row " + name);
  }

  private static int id(GameTable table, String row) {
    return id(table, table.row(row));
  }

  /** A row's data id: its table's id times a million plus its index. */
  private static int id(GameTable table, GameRow row) {
    return Integer.parseInt(table.id()) * ReplayScenario.IDS_PER_TABLE + row.index();
  }

  /** Writes pinned fields, each its one value. */
  private static void pins(ObjectNode node, Map<String, String> pins) {
    pins.forEach((field, value) -> node.set(field, json(value)));
  }

  private static JsonNode json(String text) {
    try {
      return MAPPER.readTree(text);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("a pinned value that is not JSON: " + text, e);
    }
  }
}
