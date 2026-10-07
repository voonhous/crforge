package org.crforge.core.battle.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTable;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.EvolutionItem;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchSide;
import org.crforge.core.battle.match.MirrorItem;
import org.crforge.core.battle.match.SpellVariant;
import org.crforge.core.battle.match.VariantItem;
import org.crforge.core.battle.unit.Standard1v1Battle;

/**
 * Translates a replay scenario into the production simulator's inputs.
 *
 * <p>Every field of the scenario is one of three things, and {@link #mapping()} lists which:
 *
 * <ul>
 *   <li><b>consumed</b>: translated into a production input, by the rule the entry states;
 *   <li><b>pinned</b>: the production simulator has no input for it, so only the one value the
 *       mapping was established on is accepted, and any other value is reported unsupported rather
 *       than ignored;
 *   <li><b>carried</b>: presentation or identity only, kept in the run's artifacts.
 * </ul>
 *
 * <p>A data id is its table's id times a million plus the row's index in the table: 26000000 is row
 * 0 of table 26. A level in the scenario is the card's own level index, counted from 0 on its
 * rarity's first level; the simulator counts levels from 1 across all rarities, so a level is the
 * index plus the rarity's RelativeLevel plus 1.
 *
 * <p>A play's packed item ({@code pd}) is the word the player's client builds for it: the evolution
 * field in bits 0..3 (1 evolved, 2 the hero form), the option plus 1 in bits 4..6, the count plus 1
 * in bits 7..9 for a card in the deck's evolution slot, the level in bits 10..16, a cosmetic value
 * in bits 17..18, the deck card's slot flags in bits 19..21, the deck index plus 1 in bits 22..27
 * and the cost from bit 28. The parts the deck alone decides are checked as the scenario is read;
 * the evolution field, the count and the cost of a play whose item depends on the battle are
 * checked as the play runs, against the item the simulator builds ({@link #checkItem}). A Mirror's
 * item also names the card it repeats ({@code fs}); that card, the level field and the cost depend
 * on the battle, and are checked against the Mirror item the simulator builds ({@link
 * #checkMirrorItem}). A variant card's item names the option it is played as in the option field
 * and carries that option's cost; the option the player's client picks depends on the battle, and
 * is checked against the variant item the simulator builds ({@link #checkVariantItem}).
 *
 * <p>The command type numbers are the data version's ({@link CommandTypes}): a scenario read
 * against tables of a version whose command types are not established has every command refused.
 * The fields a later version's replays write beyond 14.593.1's, and the values a version pins, are
 * the data version's too ({@link ReplayFormat}): a replay of a version whose fields are not decided
 * is read by 14.593.1's. In 16.402.18's replays each side's king level is its player data's {@code
 * kt}, and the players' profiles, the cards' cosmetics and the replay's events are carried.
 *
 * <p>A replay may carry a capture block ({@link ReplayCapture}), written by the tool that saved it:
 * the client version, data version and content sha it was recorded on. It is no battle input, in
 * every version's format; a replay whose block names data other than the tables' is refused, naming
 * both.
 *
 * <p>The caller names the kind of scenario ({@link ScenarioShape}). A replay is read as above. A
 * generated case is read by the fields of the version's generated cases ({@link
 * ReplayFormat#generated}), which for 16.402.18 are 14.593.1's, with the version's command types; a
 * version whose generated cases' fields are not established has the whole case refused.
 *
 * <p>{@link #translate} stops at the first input it cannot map. {@link #survey} reads the same
 * scenario the same way and lists every refusal it meets instead: each field and pinned value it
 * refuses, and each command it cannot read, going on to the next. A refusal that leaves nothing to
 * read on with, such as a row the tables do not hold, ends the survey and is listed last.
 */
public final class ReplayScenario {

  /** Ids per table. */
  private static final int IDS_PER_TABLE = 1_000_000;

  /** The table of the tower selections, the support cards. */
  private static final int SUPPORT_CARD_TABLE = 159;

  /** The table of the support cards' rarities, which set a tower selection's levels. */
  private static final String SUPPORT_RARITIES = "support_rarities";

  /**
   * The tower selections the simulator builds, by support card: the princess towers, the cannoneer
   * towers, the Dagger Duchess's towers and the Royal Chef's towers, each placed from the card's
   * spawn group.
   */
  private static final Set<String> BUILT_TOWER_SELECTIONS =
      Set.of("King_PrincessTowers", "King_CannonTowers", "King_KnifeTowers", "King_ChefTowers");

  /** What the simulator does not model of each other tower selection, by support card. */
  private static final Map<String, String> UNBUILT_TOWER_SELECTIONS =
      Map.of(
          "GoblinQueen_SpawnAbility",
          "the Goblin Queen's towers, a selection the game data marks not in use");

  /**
   * The only avatar exp level the adapter accepts. The exp level table is not one of the game
   * tables, so another exp level has no production input.
   */
  private static final int EXP_LEVEL = 1;

  /**
   * The level a side's king row is created at, counted from 1: the summoner level of the avatar's
   * exp level, which for {@link #EXP_LEVEL} is the first. It does not depend on the side's tower
   * selection or its level index.
   */
  private static final int KING_LEVEL = 1;

  /** The map file of the standard arena, which {@link Standard1v1Battle} is built on. */
  private static final String STANDARD_TILE_MAP = "tilemaps/tilemap.csv";

  /**
   * How the battle header's and each avatar's {@code arena} are read: the players' trophy arena,
   * whose row holds trophy, chest, matchmaking and presentation columns only and is not among the
   * tables the battle reads. The battle's map is the location's.
   */
  static final String ARENA_CARRIED =
      "carried: the players' trophy arena, presentation; the map is the location's";

  /** The evolution field of a play's packed item: bits 0..3. */
  private static final int ITEM_FIELD_MASK = 0xf;

  /** The option field of a play's packed item, the option plus 1: three bits from bit 4. */
  private static final int ITEM_OPTION_SHIFT = 4;

  private static final int ITEM_OPTION_MASK = 0x7;

  /**
   * The count field of a play's packed item, an evolution slot's count plus 1: three bits from bit
   * 7.
   */
  private static final int ITEM_COUNT_SHIFT = 7;

  private static final int ITEM_COUNT_MASK = 0x7;

  /** The level field of a play's packed item: seven bits from bit 10. */
  private static final int ITEM_LEVEL_SHIFT = 10;

  private static final int ITEM_LEVEL_MASK = 0x7f;

  /** The cosmetic field of a play's packed item: two bits from bit 17. */
  private static final int ITEM_COSMETIC_SHIFT = 17;

  private static final int ITEM_COSMETIC_MASK = 0x3;

  /** The slot flags field of a play's packed item, the deck card's: three bits from bit 19. */
  private static final int ITEM_FLAGS_SHIFT = 19;

  private static final int ITEM_FLAGS_MASK = 0x7;

  /** The slot flags the simulator models: the evolution slot and the hero slot. */
  private static final int MODELLED_SLOT_FLAGS = MatchSide.EVOLUTION_SLOT | MatchSide.HERO_SLOT;

  /** The deck index field of a play's packed item, the index plus 1: six bits from bit 22. */
  private static final int ITEM_INDEX_SHIFT = 22;

  private static final int ITEM_INDEX_MASK = 0x3f;

  /** The cost field of a play's packed item: the bits from bit 28. */
  private static final int ITEM_COST_SHIFT = 28;

  /** The card row column that marks the Mirror, whose play repeats its side's last card. */
  private static final String MIRROR_COLUMN = "Mirror";

  /**
   * The card row column that names a card's custom class: a variant card's, played as one of its
   * options, is the only one a match models.
   */
  private static final String CUSTOM_CLASS_COLUMN = "CustomClassType";

  /** A player's data with no emotes listed. */
  private static final String NO_EMOTES = "{\"em\":{\"oe\":[],\"de\":[]}}";

  /** How many choices the game builds such data with. */
  private static final int NO_EMOTES_CHOICES = 1;

  private final GameTables tables;

  /** The battle's reading of the tables, which a variant card's options are read through. */
  private final BattleRecords records;

  private final Map<String, String> mapping = new LinkedHashMap<>();

  /** The command types of the tables' data version, or null when they are not established. */
  private final CommandTypes commandTypes;

  /**
   * The fields of the tables' data version's replays that differ from version to version ({@link
   * ReplayFormat#orShared}), or of its generated cases ({@link ReplayFormat#generated}); null for a
   * generated case of a version whose generated cases' fields are not established.
   */
  private final ReplayFormat format;

  /** The kind of scenario read: a replay or a generated case. */
  private final ScenarioShape shape;

  /**
   * Each side's king level, counted from 1, from its player data, in a format that gives it there;
   * else empty.
   */
  private final List<Integer> kingLevels = new ArrayList<>();

  /** The refusals a survey has met, or null outside a survey, when a refusal is thrown. */
  private List<Refusal> refusals;

  /**
   * One input a survey refused.
   *
   * @param feature the production feature the input needs, or why it cannot be read
   * @param input the scenario field and value
   */
  public record Refusal(String feature, String input) {}

  /**
   * A translator with the command types of the tables' data version ({@link CommandTypes#of}).
   *
   * @param tables the game tables the ids are resolved against
   */
  public ReplayScenario(GameTables tables) {
    this(tables, ScenarioShape.REPLAY);
  }

  /**
   * A translator of a kind of scenario, with the command types of the tables' data version ({@link
   * CommandTypes#of}).
   *
   * @param tables the game tables the ids are resolved against
   * @param shape the kind of scenario read, as the caller names it
   */
  public ReplayScenario(GameTables tables, ScenarioShape shape) {
    this(tables, CommandTypes.of(tables.version()).orElse(null), shape);
  }

  /**
   * A translator of replays.
   *
   * @param tables the game tables the ids are resolved against
   * @param commandTypes the command types the commands are read by, or null for none established
   */
  public ReplayScenario(GameTables tables, CommandTypes commandTypes) {
    this(tables, commandTypes, ScenarioShape.REPLAY);
  }

  /**
   * @param tables the game tables the ids are resolved against
   * @param commandTypes the command types the commands are read by, or null for none established
   * @param shape the kind of scenario read: a replay is read by the fields of the tables' data
   *     version's replays, a generated case by those of its generated cases
   */
  public ReplayScenario(GameTables tables, CommandTypes commandTypes, ScenarioShape shape) {
    this.tables = tables;
    this.records = new BattleRecords(tables);
    this.commandTypes = commandTypes;
    this.shape = shape;
    this.format =
        shape == ScenarioShape.GENERATED
            ? ReplayFormat.generated(tables.version()).orElse(null)
            : ReplayFormat.orShared(tables.version());
  }

  /** What became of each scenario field, in the order they were read. */
  public Map<String, String> mapping() {
    return mapping;
  }

  /**
   * Translates a scenario.
   *
   * @param scenario the scenario document
   * @return the production inputs
   * @throws UnsupportedScenarioException for an input the production simulator has no mapping for
   */
  public ScenarioPlan translate(JsonNode scenario) {
    if (format == null) {
      // Nothing to read the scenario by: this ends a survey too.
      throw new UnsupportedScenarioException(
          "a generated case of data version "
              + tables.version()
              + ", whose generated cases' fields no recorded battle has established",
          "the scenario shape " + shape.id());
    }
    capture(scenario);
    int seed = required(scenario, "rndSeed").asInt();
    mapping.put("rndSeed", "consumed: BattleWorld.seed, the battle stream's seed");
    mapping.put("time", "carried: the replay's wall clock, no battle input");
    if (scenario.has("endTick")) {
      mapping.put(
          "endTick", "carried: the replay's last tick, where a viewer stops; no battle input");
    }
    if (format.events()) {
      events(scenario);
    } else {
      pin(scenario, "evt", "[]");
    }
    format.rootPins().forEach((field, value) -> pin(scenario, field, value));
    onlyFields(
        scenario,
        "$",
        with(
            format.rootPins().keySet(),
            "rndSeed",
            "time",
            "endTick",
            "battle",
            "cmd",
            "evt",
            ReplayCapture.FIELD));
    JsonNode battle = required(scenario, "battle");
    onlyFields(
        battle,
        "battle",
        with(
            format.battlePins().keySet(),
            "gmt",
            "plt",
            "t1s",
            "t2s",
            "gamemode",
            "hm",
            "lvlcap",
            "stp",
            "trail",
            "te",
            "tps",
            "arena",
            "deck0",
            "avatar0",
            "deck1",
            "avatar1",
            "location",
            "hbd"));
    gameMode(battle);
    location(battle);
    for (String field : List.of("gmt", "plt", "tps")) {
      pin(battle, field, "1");
    }
    for (String field : List.of("t1s", "t2s", "lvlcap", "stp")) {
      pin(battle, field, "0");
    }
    pin(battle, "hm", "false");
    pin(battle, "trail", "170000000");
    pin(battle, "te", "-1");
    required(battle, "arena");
    mapping.put("battle.arena", ARENA_CARRIED);
    format.battlePins().forEach((field, value) -> pin(battle, field, value));
    List<Integer> playerDataChoices = playerData(battle);

    List<List<String>> decks = new ArrayList<>();
    List<int[]> deckLevels = new ArrayList<>();
    List<int[]> slotFlags = new ArrayList<>();
    List<int[]> accounts = new ArrayList<>();
    List<Standard1v1Battle.Towers> towers = new ArrayList<>();
    for (int side = 0; side < 2; side++) {
      JsonNode deck = required(battle, "deck" + side);
      List<String> names = new ArrayList<>();
      List<Integer> levels = new ArrayList<>();
      List<Integer> slots = new ArrayList<>();
      for (JsonNode entry : required(deck, "sp")) {
        GameRow card = cardRow(required(entry, "d").asInt(), "battle.deck" + side + ".sp");
        names.add(card.name());
        levels.add(level(required(entry, "l").asInt(), card));
        slots.add(slotFlags(entry, "battle.deck" + side + ".sp[" + slots.size() + "]"));
        onlyFields(entry, "battle.deck" + side + ".sp", with(format.cardCarried(), "d", "l", "el"));
      }
      decks.add(names);
      deckLevels.add(levels.stream().mapToInt(Integer::intValue).toArray());
      slotFlags.add(slots.stream().mapToInt(Integer::intValue).toArray());
      towers.add(towers(deck, side));
      onlyFields(deck, "battle.deck" + side, with(format.deckCarried(), "sp", "sc"));
      accounts.add(avatar(required(battle, "avatar" + side), side));
    }
    mapping.put(
        "battle.deckN.sp[i].d",
        "consumed: the card row, table id times a million plus row index; the deck's order kept");
    mapping.put(
        "battle.deckN.sp[i].l",
        "consumed: the card's level index, plus its rarity's RelativeLevel, plus 1");
    mapping.put(
        "battle.deckN.sp[i].el",
        "consumed: the deck card's slot flags, bit 0 the deck's evolution slot and bit 1 its hero"
            + " slot, Standard1v1Battle.startLadderMatch's slots; absent is 0, and any other bit is"
            + " unsupported");
    for (String field : format.cardCarried()) {
      mapping.put("battle.deckN.sp[i]." + field, "carried: the card's cosmetics, no battle input");
    }
    for (String field : format.deckCarried()) {
      mapping.put("battle.deckN." + field, "carried: no battle input");
    }
    if (accounts.get(0)[0] == accounts.get(1)[0] && accounts.get(0)[1] == accounts.get(1)[1]) {
      refuse("two sides of one account, whose commands name no side", "battle.avatarN.accountID");
    }
    List<ScenarioPlan.Play> plays = new ArrayList<>();
    List<ScenarioPlan.Ability> abilities = new ArrayList<>();
    int index = 0;
    for (JsonNode command : required(scenario, "cmd")) {
      try {
        command(command, index, decks, deckLevels, slotFlags, accounts, plays, abilities);
      } catch (UnsupportedScenarioException e) {
        // A survey lists a command it cannot read and goes on to the next one.
        if (refusals == null) {
          throw e;
        }
        refusals.add(new Refusal(e.feature(), e.input()));
      } catch (IllegalArgumentException e) {
        if (refusals == null) {
          throw e;
        }
        refusals.add(new Refusal(e.getMessage(), "cmd[" + index + "]"));
      }
      index++;
    }
    int play = commandTypes == null ? -1 : commandTypes.play();
    int ability = commandTypes == null ? -1 : commandTypes.ability();
    mapping.put(
        "cmd[i].ct",
        "consumed: "
            + play
            + ", a card play, or "
            + ability
            + ", an ability command; any other is unsupported. Both kinds run in the scenario's"
            + " order within a tick");
    mapping.put("cmd[i].c.t", "carried: the tick the command was given on");
    mapping.put(
        "cmd[i].c.t2",
        "consumed: the tick the command runs on, Standard1v1Battle.play's or useAbility's tick");
    mapping.put(
        "cmd[i].c.idHi/idLo",
        "consumed: the commanding side, the side whose avatar has this account id");
    mapping.put(
        "cmd[i].c.cgid",
        "consumed (ability command only): the game object id of the unit the command names,"
            + " Standard1v1Battle.useAbility's object id; the command names no row and no play");
    mapping.put("cmd[i].c.px/py", "consumed: the requested point in game units, as given");
    mapping.put("cmd[i].c.sid", "pinned: -1");
    mapping.put("cmd[i].c.sel.os", "consumed: the card row played, which must be in the deck");
    mapping.put(
        "cmd[i].c.sel.pd",
        "checked: the packed item's level field (bits 10..16) against the deck card's level index"
            + " plus its rarity's RelativeLevel, its deck index field (bits 22..27) against the"
            + " card's deck index plus 1, its slot flags field (bits 19..21) against the deck"
            + (format.cosmeticCarried()
                ? " card's, and its option field (bits 4..6) 0, its cosmetic field (bits 17..18)"
                    + " carried, the card's cosmetic, no battle input, on every play; its"
                : " card's, and its option (bits 4..6) and cosmetic (bits 17..18) fields 0; its")
            + " evolution field (bits 0..3), count field (bits 7..9) and cost field (bits 28..31)"
            + " against the item the simulator builds as the play runs, and as the scenario is"
            + " read for a card outside the evolution slot: the evolution field 2 for a hero"
            + " slot's card, else 0, no count, and a plain play's cost the card row's ManaCost. A"
            + " Mirror's: its deck index field as above, its slot flags, evolution, option, count"
            + " and cosmetic fields 0, and its level field (the Mirror's plus the level offset)"
            + " and cost (the Mirror's plus the repeated card's) against the Mirror item the"
            + " simulator builds as the play runs. A variant card's: its deck index and level"
            + " fields as above, its slot flags, evolution, count and cosmetic fields 0, its"
            + " option field one of the card's options plus 1 and its cost that option's"
            + " cost, and its option field and cost against the variant item the simulator"
            + " builds as the play runs, from the option its player picks; the play runs as"
            + " Standard1v1Battle.playVariant");
    mapping.put(
        "cmd[i].c.sel.fs",
        "checked (a Mirror's play only, where it is required): the card row the item repeats,"
            + " against the card the simulator's Mirror repeats as the play runs, its side's last"
            + " card; the play runs as Standard1v1Battle.playMirror. On any other play it is"
            + " unsupported");
    return new ScenarioPlan(
        seed, towers, decks, deckLevels, slotFlags, accounts, playerDataChoices, plays, abilities);
  }

  /**
   * Reads a scenario as {@link #translate} does, listing every refusal instead of stopping at the
   * first: each refused field and pinned value, and each command that cannot be read, going on to
   * the next. A refusal that leaves nothing to read on with ends the survey and is listed last.
   *
   * @param scenario the scenario document
   * @return the refusals in the order they were met; empty when {@link #translate} maps it all
   */
  public List<Refusal> survey(JsonNode scenario) {
    refusals = new ArrayList<>();
    try {
      translate(scenario);
    } catch (UnsupportedScenarioException e) {
      refusals.add(new Refusal(e.feature(), e.input()));
    } catch (RuntimeException e) {
      refusals.add(new Refusal(String.valueOf(e.getMessage()), "the reading stops here"));
    }
    List<Refusal> found = refusals;
    refusals = null;
    return found;
  }

  /**
   * Refuses an input: thrown as {@link UnsupportedScenarioException}, or in a survey listed, the
   * reading going on.
   */
  private void refuse(String feature, String input) {
    if (refusals == null) {
      throw new UnsupportedScenarioException(feature, input);
    }
    refusals.add(new Refusal(feature, input));
  }

  /** One command, read by its type: a play or an ability command, any other type refused. */
  private void command(
      JsonNode command,
      int index,
      List<List<String>> decks,
      List<int[]> deckLevels,
      List<int[]> slotFlags,
      List<int[]> accounts,
      List<ScenarioPlan.Play> plays,
      List<ScenarioPlan.Ability> abilities) {
    String field = "cmd[" + index + "]";
    int type = required(command, "ct").asInt();
    if (commandTypes == null) {
      throw new UnsupportedScenarioException(
          "the command type "
              + type
              + " of data version "
              + tables.version()
              + ", whose command types are not established",
          field + ".ct");
    }
    if (type == commandTypes.play()) {
      plays.add(play(command, index, decks, deckLevels, slotFlags, accounts));
    } else if (type == commandTypes.ability()) {
      abilities.add(ability(command, index, accounts));
    } else {
      throw new UnsupportedScenarioException("the command type " + type, field + ".ct");
    }
  }

  /**
   * The players' data, one entry each: only the entry with no emotes listed is known, whose data
   * the game builds with one choice.
   *
   * @return how many choices each entry's data lists
   */
  private List<Integer> playerData(JsonNode battle) {
    if (format.kingLevelFromPlayerData()) {
      return playerDataWithKingLevels(battle);
    }
    List<Integer> choices = new ArrayList<>();
    for (JsonNode entry : required(battle, "hbd")) {
      if (!entry.toString().equals(NO_EMOTES)) {
        refuse(
            "a player's data other than one with no emotes listed, whose number of choices is not"
                + " established",
            "battle.hbd=" + entry);
      }
      choices.add(NO_EMOTES_CHOICES);
    }
    mapping.put(
        "battle.hbd[i]",
        "consumed: Standard1v1Battle.addPlayerData, one entry each in order; only "
            + NO_EMOTES
            + ", whose data lists "
            + NO_EMOTES_CHOICES
            + " choice");
    return choices;
  }

  /**
   * The players' data of a format that gives each side's king level there ({@link
   * ReplayFormat#KING_LEVEL_FIELD}): one entry per side, each holding only the format's fields. The
   * king level is kept for the side's towers; the other fields are carried, and each entry is
   * handed over as data that lists one choice, as an entry with no emotes listed is.
   *
   * @return how many choices each entry's data lists
   */
  private List<Integer> playerDataWithKingLevels(JsonNode battle) {
    kingLevels.clear();
    JsonNode entries = required(battle, "hbd");
    if (entries.size() != 2) {
      throw new UnsupportedScenarioException(
          "players' data of " + entries.size() + " entries, not one per side", "battle.hbd");
    }
    List<Integer> choices = new ArrayList<>();
    int levelCount = tables.table("rarities").row("Common").intValue("LevelCount");
    for (int side = 0; side < 2; side++) {
      JsonNode entry = entries.get(side);
      String field = "battle.hbd[" + side + "]";
      onlyFields(entry, field, format.playerData().toArray(String[]::new));
      JsonNode level = required(entry, ReplayFormat.KING_LEVEL_FIELD);
      if (!level.isInt() || level.asInt() < 1 || level.asInt() > levelCount) {
        refuse(
            "a king level outside the " + levelCount + " levels of the king's Common rarity",
            field + "." + ReplayFormat.KING_LEVEL_FIELD + "=" + level);
      }
      kingLevels.add(level.asInt());
      choices.add(NO_EMOTES_CHOICES);
    }
    mapping.put(
        "battle.hbd[i]",
        "consumed: Standard1v1Battle.addPlayerData, one entry per side in order, each handed over"
            + " as data that lists "
            + NO_EMOTES_CHOICES
            + " choice, as one with no emotes listed; the fields other than "
            + ReplayFormat.KING_LEVEL_FIELD
            + " are carried, the emotes, skins, banner and profile, no battle input");
    mapping.put(
        "battle.hbd[i]." + ReplayFormat.KING_LEVEL_FIELD,
        "consumed: the side's king level, counted from 1, 1 to the Common rarity's LevelCount: the"
            + " level its king row is created at");
    return choices;
  }

  /**
   * A deck card's slot flags, its {@code el}: 0 when absent, and only the evolution and hero slots'
   * bits, which the simulator models.
   */
  private int slotFlags(JsonNode entry, String field) {
    JsonNode value = entry.get("el");
    if (value == null) {
      return 0;
    }
    if (!value.isInt() || (value.asInt() & ~MODELLED_SLOT_FLAGS) != 0) {
      refuse(
          "slot flags other than the evolution slot ("
              + MatchSide.EVOLUTION_SLOT
              + ") and the hero slot ("
              + MatchSide.HERO_SLOT
              + "), which the simulator has no input for",
          field + ".el=" + value);
      // A survey reads on with the bits the simulator models.
      return value.asInt() & MODELLED_SLOT_FLAGS;
    }
    return value.asInt();
  }

  /** The game mode: a Ladder match is the one mode the simulator plays. */
  private void gameMode(JsonNode battle) {
    GameRow mode = row(required(battle, "gamemode").asInt(), "battle.gamemode");
    if (!mode.name().equals(LadderMatch.GAME_MODE)) {
      refuse("the game mode " + mode.name(), "battle.gamemode=" + battle.get("gamemode"));
    }
    mapping.put(
        "battle.gamemode", "consumed: the game mode row, which must be " + LadderMatch.GAME_MODE);
  }

  /** The location: only one on the standard map is built. */
  private void location(JsonNode battle) {
    GameRow location = row(required(battle, "location").asInt(), "battle.location");
    String tileMap = location.string("TileDataFileName");
    if (!STANDARD_TILE_MAP.equals(tileMap)) {
      refuse(
          "the map " + tileMap + " of location " + location.name(),
          "battle.location=" + battle.get("location"));
    }
    mapping.put(
        "battle.location",
        "consumed: the location row, whose map must be "
            + STANDARD_TILE_MAP
            + ", the bundled standard arena");
  }

  /**
   * A deck's tower selection: a support card, whose spawn group places the side's towers, at a
   * level index counted from 0 on the card's rarity's first level.
   *
   * <p>The rows in the princess slots stand at the level index plus the support rarity's
   * RelativeLevel plus 1, as a Common row. The king row stands at the avatar's level, {@link
   * #KING_LEVEL}, whatever the selection and its level index are.
   *
   * @return the side's towers
   */
  private Standard1v1Battle.Towers towers(JsonNode deck, int side) {
    JsonNode selections = required(deck, "sc");
    String field = "battle.deck" + side + ".sc";
    if (selections.size() != 1) {
      throw new UnsupportedScenarioException(
          "a deck with " + selections.size() + " tower selections", field);
    }
    JsonNode selection = selections.get(0);
    int id = required(selection, "d").asInt();
    if (id / IDS_PER_TABLE != SUPPORT_CARD_TABLE) {
      throw new UnsupportedScenarioException(
          "a tower selection of table " + id / IDS_PER_TABLE, field + "[0].d=" + id);
    }
    GameRow card = row(id, field + "[0].d");
    if (!BUILT_TOWER_SELECTIONS.contains(card.name())) {
      refuse(
          "the tower selection "
              + card.name()
              + ", "
              + UNBUILT_TOWER_SELECTIONS.getOrDefault(card.name(), "which is not modelled"),
          field + "[0].d=" + id);
    }
    format.selectionPins().forEach((name, value) -> pin(selection, name, value));
    onlyFields(
        selection,
        field + "[0]",
        with(format.selectionCarried(), with(format.selectionPins().keySet(), "d", "l")));
    for (String name : format.selectionCarried()) {
      mapping.put(
          "battle.deckN.sc[0]." + name,
          "carried: the tower card's count and flags, no battle input");
    }
    GameRow rarity = tables.table(SUPPORT_RARITIES).row(card.string("Rarity"));
    int levelIndex = required(selection, "l").asInt();
    int levelCount = rarity.intValue("LevelCount");
    if (levelIndex < 0 || levelIndex >= levelCount) {
      refuse(
          "a tower level index outside the "
              + levelCount
              + " levels of the selection's rarity "
              + rarity.name(),
          field + "[0].l=" + levelIndex);
    }
    mapping.put(
        "battle.deckN.sc[0].d",
        "consumed: the tower selection, a support card (table "
            + SUPPORT_CARD_TABLE
            + "), whose spawn group places the side's towers; only "
            + String.join(" and ", BUILT_TOWER_SELECTIONS.stream().sorted().toList())
            + ", the others unsupported");
    mapping.put(
        "battle.deckN.sc[0].l",
        "consumed: the towers' level index, 0 to the support rarity's LevelCount less 1; the"
            + " princess slots' rows at the index plus the support rarity's RelativeLevel plus 1;"
            + " the king row does not use it");
    // The king row stands at the avatar's level, or where the format gives one, at the side's own
    // king level from its player data.
    int kingLevel = kingLevels.isEmpty() ? KING_LEVEL : kingLevels.get(side);
    return new Standard1v1Battle.Towers(
        card.string("SpawnGroup"), kingLevel, levelIndex + rarity.intValue("RelativeLevel") + 1);
  }

  /**
   * An avatar.
   *
   * @return its account id, high word then low word
   */
  private int[] avatar(JsonNode avatar, int side) {
    String field = "battle.avatar" + side;
    // The king tower is created at the summoner level of the avatar's exp level; only the first
    // exp level, whose king level is KING_LEVEL, has a production input.
    // A format whose king level is in the player data has no exp level pinned.
    format.avatarPins().forEach((name, value) -> pin(avatar, name, value));
    required(avatar, "arena");
    onlyFields(
        avatar,
        field,
        with(
            format.avatarCarried().keySet(),
            "accountID.hi",
            "accountID.lo",
            "expLevel",
            "name",
            "arena",
            "npc"));
    mapping.put(
        "battle.avatarN.accountID.hi/lo",
        "consumed: names the side of a command; the low word is the player's word that joins its"
            + " deck shuffle's draw"
            + (format.accountHighOptional()
                ? "; a high word left out is 0, as the side's commands give it"
                : ""));
    mapping.put("battle.avatarN.name", "carried: presentation");
    mapping.put("battle.avatarN.arena", ARENA_CARRIED);
    format
        .avatarCarried()
        .forEach((name, what) -> mapping.put("battle.avatarN." + name, "carried: " + what));
    JsonNode high =
        format.accountHighOptional() && !avatar.has("accountID.hi")
            ? IntNode.valueOf(0)
            : required(avatar, "accountID.hi");
    return new int[] {high.asInt(), required(avatar, "accountID.lo").asInt()};
  }

  private ScenarioPlan.Play play(
      JsonNode command,
      int index,
      List<List<String>> decks,
      List<int[]> deckLevels,
      List<int[]> slotFlags,
      List<int[]> accounts) {
    String field = "cmd[" + index + "]";
    JsonNode body = required(command, "c");
    onlyFields(command, field, "ct", "c");
    onlyFields(body, field + ".c", "t", "t2", "idHi", "idLo", "px", "py", "sid", "sel");
    pin(body, "sid", "-1");
    int side = side(body, field, accounts);
    JsonNode item = required(body, "sel");
    onlyFields(item, field + ".c.sel", "os", "fs", "pd");
    GameRow card = cardRow(required(item, "os").asInt(), field + ".c.sel.os");
    int deckIndex = decks.get(side).indexOf(card.name());
    if (deckIndex < 0) {
      throw new IllegalArgumentException(
          field + " plays " + card.name() + ", which is not in side " + side + "'s deck");
    }
    int level = deckLevels.get(side)[deckIndex];
    int slots = slotFlags.get(side)[deckIndex];
    int packed = required(item, "pd").asInt();
    if (card.bool(MIRROR_COLUMN)) {
      return mirrorPlay(body, item, index, side, card, deckIndex, level, slots, packed);
    }
    // Only a Mirror's item names a card it repeats.
    if (item.has("fs")) {
      throw new UnsupportedScenarioException(
          "a repeated card on a play of " + card.name() + ", which is not the Mirror",
          field + ".c.sel.fs=" + item.get("fs"));
    }
    // A card of a custom class is a variant card, which the match reads its options from.
    if (!card.string(CUSTOM_CLASS_COLUMN).isEmpty()) {
      SpellVariant variant = records.matchCard(card.name()).variant();
      return variantPlay(body, index, side, card, deckIndex, level, slots, packed, variant);
    }
    int packedField = packed & ITEM_FIELD_MASK;
    int packedOption = (packed >>> ITEM_OPTION_SHIFT) & ITEM_OPTION_MASK;
    int packedCount = (packed >>> ITEM_COUNT_SHIFT) & ITEM_COUNT_MASK;
    int packedLevel = (packed >>> ITEM_LEVEL_SHIFT) & ITEM_LEVEL_MASK;
    int packedCosmetic = (packed >>> ITEM_COSMETIC_SHIFT) & ITEM_COSMETIC_MASK;
    int packedFlags = (packed >>> ITEM_FLAGS_SHIFT) & ITEM_FLAGS_MASK;
    int packedIndex = (packed >>> ITEM_INDEX_SHIFT) & ITEM_INDEX_MASK;
    int packedCost = packed >>> ITEM_COST_SHIFT;
    // A card outside the evolution slot has no count, and its item is the same on every play: the
    // hero form for a hero slot's card, else plain. An evolution slot's card carries its count plus
    // 1 and is evolved once the count reaches its evolved row's cost, which the run checks.
    int formField = (slots & MatchSide.HERO_SLOT) != 0 ? EvolutionItem.HERO : 0;
    boolean evolutionSlot = (slots & MatchSide.EVOLUTION_SLOT) != 0;
    boolean fieldAllowed =
        evolutionSlot
            ? (packedField == formField || packedField == EvolutionItem.EVOLVED) && packedCount >= 1
            : packedField == formField && packedCount == 0;
    if (packedLevel != level - 1
        || packedIndex != deckIndex + 1
        || packedFlags != slots
        || packedOption != 0
        || (packedCosmetic != 0 && !format.cosmeticCarried())
        || !fieldAllowed
        || (packedField == 0 && packedCost != card.intValue("ManaCost"))) {
      throw new UnsupportedScenarioException(
          "a play whose packed item is not one its deck card can carry",
          field
              + ".c.sel.pd="
              + packed
              + " ("
              + describe(packed)
              + ") for "
              + card.name()
              + " at deck index "
              + deckIndex
              + ", slot flags "
              + slots
              + " and level "
              + level);
    }
    return new ScenarioPlan.Play(
        index,
        required(body, "t").asInt(),
        required(body, "t2").asInt(),
        side,
        card.name(),
        level,
        required(body, "px").asInt(),
        required(body, "py").asInt(),
        packed,
        null,
        null);
  }

  /**
   * A variant card's play. Its item is built by the player's client from the option it picks as it
   * gives the play: the option field is the option's index plus 1 and the cost is the option row's.
   * The option picked depends on the battle, so it is checked as the play runs ({@link
   * #checkVariantItem}); the parts the deck decides are checked here: the option field names one of
   * the card's options, the cost is that option's, the deck index and level fields are the card's,
   * and there are no slot flags and no evolution, count or cosmetic field.
   *
   * <p>A variant card in a deck's evolution or hero slot is in no reference, and is refused.
   */
  private ScenarioPlan.Play variantPlay(
      JsonNode body,
      int index,
      int side,
      GameRow card,
      int deckIndex,
      int level,
      int slots,
      int packed,
      SpellVariant variant) {
    String field = "cmd[" + index + "]";
    if (slots != 0) {
      throw new UnsupportedScenarioException(
          "a variant card in a deck's evolution or hero slot, whose item no reference holds",
          "battle.deck" + side + ".sp[" + deckIndex + "].el=" + slots);
    }
    int optionField = (packed >>> ITEM_OPTION_SHIFT) & ITEM_OPTION_MASK;
    if (optionField < 1 || optionField > variant.options().size()) {
      throw new UnsupportedScenarioException(
          "a variant play whose option field names none of its card's options",
          field
              + ".c.sel.pd="
              + packed
              + " ("
              + describe(packed)
              + ") for "
              + card.name()
              + ", which has "
              + variant.options().size()
              + " options");
    }
    SpellVariant.Option option = variant.options().get(optionField - 1);
    if ((packed & ITEM_FIELD_MASK) != 0
        || ((packed >>> ITEM_COUNT_SHIFT) & ITEM_COUNT_MASK) != 0
        || ((packed >>> ITEM_LEVEL_SHIFT) & ITEM_LEVEL_MASK) != level - 1
        || (((packed >>> ITEM_COSMETIC_SHIFT) & ITEM_COSMETIC_MASK) != 0
            && !format.cosmeticCarried())
        || ((packed >>> ITEM_FLAGS_SHIFT) & ITEM_FLAGS_MASK) != 0
        || ((packed >>> ITEM_INDEX_SHIFT) & ITEM_INDEX_MASK) != deckIndex + 1
        || packed >>> ITEM_COST_SHIFT != option.cost()) {
      throw new UnsupportedScenarioException(
          "a play whose packed item is not one its deck card can carry",
          field
              + ".c.sel.pd="
              + packed
              + " ("
              + describe(packed)
              + ") for "
              + card.name()
              + " at deck index "
              + deckIndex
              + ", slot flags "
              + slots
              + " and level "
              + level);
    }
    return new ScenarioPlan.Play(
        index,
        required(body, "t").asInt(),
        required(body, "t2").asInt(),
        side,
        card.name(),
        level,
        required(body, "px").asInt(),
        required(body, "py").asInt(),
        packed,
        null,
        new ScenarioPlan.Option(optionField - 1, option.spell(), option.cost()));
  }

  /**
   * A Mirror's play. Its item is built by the player's client from the side's last card: {@code fs}
   * names the card it repeats, and {@code pd} carries the Mirror's own deck index, its level field
   * plus the level offset and its cost plus the repeated card's. The repeated card, the level field
   * and the cost depend on the battle, so they are checked as the play runs ({@link
   * #checkMirrorItem}); the parts the deck decides are checked here: the deck index, no slot flags,
   * and no evolution, option, count or cosmetic field.
   *
   * <p>A Mirror item that names no repeated card, as one with nothing to repeat would be, and a
   * Mirror in a deck's evolution or hero slot are in no reference, and are refused.
   */
  private ScenarioPlan.Play mirrorPlay(
      JsonNode body,
      JsonNode item,
      int index,
      int side,
      GameRow card,
      int deckIndex,
      int level,
      int slots,
      int packed) {
    String field = "cmd[" + index + "]";
    if (slots != 0) {
      throw new UnsupportedScenarioException(
          "a Mirror in a deck's evolution or hero slot, whose item no reference holds",
          "battle.deck" + side + ".sp[" + deckIndex + "].el=" + slots);
    }
    JsonNode repeats = item.get("fs");
    if (repeats == null) {
      throw new UnsupportedScenarioException(
          "a Mirror play whose item names no repeated card, which no reference holds",
          field + ".c.sel");
    }
    GameRow repeated = row(repeats.asInt(), field + ".c.sel.fs");
    if ((packed & ITEM_FIELD_MASK) != 0
        || ((packed >>> ITEM_OPTION_SHIFT) & ITEM_OPTION_MASK) != 0
        || ((packed >>> ITEM_COUNT_SHIFT) & ITEM_COUNT_MASK) != 0
        || (((packed >>> ITEM_COSMETIC_SHIFT) & ITEM_COSMETIC_MASK) != 0
            && !format.cosmeticCarried())
        || ((packed >>> ITEM_FLAGS_SHIFT) & ITEM_FLAGS_MASK) != 0
        || ((packed >>> ITEM_INDEX_SHIFT) & ITEM_INDEX_MASK) != deckIndex + 1) {
      throw new UnsupportedScenarioException(
          "a play whose packed item is not one its deck card can carry",
          field
              + ".c.sel.pd="
              + packed
              + " ("
              + describe(packed)
              + ") for "
              + card.name()
              + " at deck index "
              + deckIndex
              + ", slot flags "
              + slots
              + " and level "
              + level);
    }
    return new ScenarioPlan.Play(
        index,
        required(body, "t").asInt(),
        required(body, "t2").asInt(),
        side,
        card.name(),
        level,
        required(body, "px").asInt(),
        required(body, "py").asInt(),
        packed,
        new ScenarioPlan.Repeated(repeats.asInt(), repeated.name()),
        null);
  }

  /**
   * One ability command: the tap on a champion's button. Its fields name the commanding side, by
   * account, and one unit, by its game object id ({@code cgid}); the command carries no row and no
   * play of the unit, so only the live unit with that id answers it.
   */
  private ScenarioPlan.Ability ability(JsonNode command, int index, List<int[]> accounts) {
    String field = "cmd[" + index + "]";
    JsonNode body = required(command, "c");
    onlyFields(command, field, "ct", "c");
    onlyFields(body, field + ".c", "t", "t2", "idHi", "idLo", "cgid");
    return new ScenarioPlan.Ability(
        index,
        required(body, "t").asInt(),
        required(body, "t2").asInt(),
        side(body, field, accounts),
        required(body, "cgid").asInt());
  }

  /** The side a command's account id names: the side whose avatar has it. */
  private static int side(JsonNode body, String field, List<int[]> accounts) {
    int hi = required(body, "idHi").asInt();
    int lo = required(body, "idLo").asInt();
    int side = -1;
    for (int s = 0; s < 2; s++) {
      if (accounts.get(s)[0] == hi && accounts.get(s)[1] == lo) {
        side = s;
      }
    }
    if (side < 0) {
      throw new IllegalArgumentException(field + " names the account " + hi + "/" + lo);
    }
    return side;
  }

  /**
   * Checks a play's packed item against the item the simulator built as the play ran: the evolution
   * field, the count field (the count plus 1 for an evolution slot's card, else 0) and the cost
   * must be the built item's. The simulator builds the item from its own count, so a play given
   * with another item is one it does not model.
   *
   * @param play the play as the scenario gives it
   * @param slotFlags the slot flags of the play's deck card
   * @param built the item the simulator built as the play ran, or null if it built none
   * @throws UnsupportedScenarioException for an item other than the built one
   */
  public static void checkItem(ScenarioPlan.Play play, int slotFlags, EvolutionItem built) {
    String field = "cmd[" + play.index() + "].c.sel.pd=" + play.item();
    if (built == null) {
      throw new UnsupportedScenarioException(
          "a play the simulator ran without building its deck card's item", field);
    }
    // The count field holds three bits of the count plus 1, as the client packs it.
    int count = (slotFlags & MatchSide.EVOLUTION_SLOT) != 0 ? built.count() + 1 : 0;
    // The parts the deck decides are as given, checked as the scenario was read.
    int fromDeck =
        play.item()
            & ~(ITEM_FIELD_MASK | (ITEM_COUNT_MASK << ITEM_COUNT_SHIFT) | (-1 << ITEM_COST_SHIFT));
    int expected =
        fromDeck
            | (built.field() & ITEM_FIELD_MASK)
            | ((count & ITEM_COUNT_MASK) << ITEM_COUNT_SHIFT)
            | (built.cost() << ITEM_COST_SHIFT);
    if (expected != play.item()) {
      throw new UnsupportedScenarioException(
          "a play whose packed item is not the item the simulator builds as it runs",
          field
              + " ("
              + describe(play.item())
              + ") for "
              + play.card()
              + ", where the simulator builds "
              + expected
              + " ("
              + describe(expected)
              + ")");
    }
  }

  /**
   * Checks a Mirror play's item against the Mirror item the simulator built as the play ran: the
   * card it repeats ({@code fs}) must be the one the simulator's Mirror repeats, and the packed
   * item's level field, deck index and cost the built item's. The simulator builds the item from
   * its side's last card, so a play given with another item is one it does not model.
   *
   * @param play the Mirror play as the scenario gives it
   * @param built the Mirror item the simulator built as the play ran, or null if it built none
   * @throws UnsupportedScenarioException for an item other than the built one
   */
  public static void checkMirrorItem(ScenarioPlan.Play play, MirrorItem built) {
    String field = "cmd[" + play.index() + "].c.sel";
    if (built == null) {
      throw new UnsupportedScenarioException(
          "a Mirror play the simulator ran without building its item",
          field + ".pd=" + play.item());
    }
    String repeated = built.repeats() == null ? null : built.repeats().name();
    if (!play.repeats().name().equals(repeated)) {
      throw new UnsupportedScenarioException(
          "a Mirror play whose repeated card is not the one the simulator's Mirror repeats",
          field
              + ".fs="
              + play.repeats().id()
              + " ("
              + play.repeats().name()
              + "), where the simulator "
              + (repeated == null ? "has nothing to repeat" : "repeats " + repeated));
    }
    // The parts the deck decides are as given, checked as the scenario was read.
    int fromDeck =
        play.item()
            & ~((ITEM_LEVEL_MASK << ITEM_LEVEL_SHIFT)
                | (ITEM_INDEX_MASK << ITEM_INDEX_SHIFT)
                | (-1 << ITEM_COST_SHIFT));
    int expected =
        fromDeck
            | ((built.levelField() & ITEM_LEVEL_MASK) << ITEM_LEVEL_SHIFT)
            | (((built.index() + 1) & ITEM_INDEX_MASK) << ITEM_INDEX_SHIFT)
            | (built.cost() << ITEM_COST_SHIFT);
    if (expected != play.item()) {
      throw new UnsupportedScenarioException(
          "a play whose packed item is not the item the simulator builds as it runs",
          field
              + ".pd="
              + play.item()
              + " ("
              + describe(play.item())
              + ") for "
              + play.card()
              + ", where the simulator builds "
              + expected
              + " ("
              + describe(expected)
              + ")");
    }
  }

  /**
   * Checks a variant card's play item against the variant item the simulator built as the play ran:
   * the packed item's option field must be the option the simulator's player picked plus 1, and its
   * deck index and cost the built item's. The simulator picks the option from the king's elixir as
   * the play is given, so a play given as another option is one it does not model.
   *
   * @param play the variant card's play as the scenario gives it
   * @param built the variant item the simulator built as the play ran, or null if it built none
   * @throws UnsupportedScenarioException for an item other than the built one
   */
  public static void checkVariantItem(ScenarioPlan.Play play, VariantItem built) {
    String field = "cmd[" + play.index() + "].c.sel";
    if (built == null) {
      throw new UnsupportedScenarioException(
          "a variant play the simulator ran without building its item",
          field + ".pd=" + play.item());
    }
    // The parts the deck decides are as given, checked as the scenario was read.
    int fromDeck =
        play.item()
            & ~((ITEM_OPTION_MASK << ITEM_OPTION_SHIFT)
                | (ITEM_INDEX_MASK << ITEM_INDEX_SHIFT)
                | (-1 << ITEM_COST_SHIFT));
    int expected =
        fromDeck
            | (((built.option() + 1) & ITEM_OPTION_MASK) << ITEM_OPTION_SHIFT)
            | (((built.index() + 1) & ITEM_INDEX_MASK) << ITEM_INDEX_SHIFT)
            | (built.cost() << ITEM_COST_SHIFT);
    if (expected != play.item()) {
      throw new UnsupportedScenarioException(
          "a play whose packed item is not the item the simulator builds as it runs",
          field
              + ".pd="
              + play.item()
              + " ("
              + describe(play.item())
              + ") for "
              + play.card()
              + ", where the simulator builds "
              + expected
              + " ("
              + describe(expected)
              + ")");
    }
  }

  /**
   * Whether a play's item depends on the battle, so that only the run can check its evolution
   * field, count and cost: a Mirror's play, a variant card's, a play of an evolution slot's card,
   * or one with an evolution field.
   *
   * @param play the play as the scenario gives it
   * @param slotFlags the slot flags of the play's deck card
   */
  public static boolean dependsOnBattle(ScenarioPlan.Play play, int slotFlags) {
    return play.repeats() != null
        || play.option() != null
        || (slotFlags & MatchSide.EVOLUTION_SLOT) != 0
        || (play.item() & ITEM_FIELD_MASK) != 0;
  }

  /** A packed item's fields, by name. */
  private static String describe(int packed) {
    return "evolution field "
        + (packed & ITEM_FIELD_MASK)
        + ", option field "
        + ((packed >>> ITEM_OPTION_SHIFT) & ITEM_OPTION_MASK)
        + ", count field "
        + ((packed >>> ITEM_COUNT_SHIFT) & ITEM_COUNT_MASK)
        + ", level field "
        + ((packed >>> ITEM_LEVEL_SHIFT) & ITEM_LEVEL_MASK)
        + ", cosmetic field "
        + ((packed >>> ITEM_COSMETIC_SHIFT) & ITEM_COSMETIC_MASK)
        + ", slot flags field "
        + ((packed >>> ITEM_FLAGS_SHIFT) & ITEM_FLAGS_MASK)
        + ", deck index field "
        + ((packed >>> ITEM_INDEX_SHIFT) & ITEM_INDEX_MASK)
        + ", cost "
        + (packed >>> ITEM_COST_SHIFT);
  }

  /** A card's level counted from 1 across all rarities, from its own level index. */
  private int level(int levelIndex, GameRow card) {
    GameRow rarity = tables.table("rarities").row(card.string("Rarity"));
    return levelIndex + rarity.intValue("RelativeLevel") + 1;
  }

  /** The card row of a data id, which must be of one of the three card tables. */
  private GameRow cardRow(int id, String field) {
    GameRow row = row(id, field);
    int tableId = id / IDS_PER_TABLE;
    if (tableId != 26 && tableId != 27 && tableId != 28) {
      refuse("a deck card of table " + tableId + " (" + row.name() + ")", field + "=" + id);
    }
    return row;
  }

  /**
   * The row a data id names, for presenting a scenario: its table's id times a million plus the
   * row's index.
   *
   * @param id the data id
   * @return the row, or empty when the tables hold no such row
   */
  public Optional<GameRow> find(int id) {
    String tableId = Integer.toString(id / IDS_PER_TABLE);
    int index = id % IDS_PER_TABLE;
    for (String name : tables.tableNames()) {
      GameTable table = tables.table(name);
      if (table.id().equals(tableId)) {
        return table.rows().stream().filter(row -> row.index() == index).findFirst();
      }
    }
    return Optional.empty();
  }

  /** The row of a data id: its table's id times a million plus the row's index. */
  private GameRow row(int id, String field) {
    String tableId = Integer.toString(id / IDS_PER_TABLE);
    int index = id % IDS_PER_TABLE;
    for (String name : tables.tableNames()) {
      GameTable table = tables.table(name);
      if (!table.id().equals(tableId)) {
        continue;
      }
      for (GameRow row : table.rows()) {
        if (row.index() == index) {
          return row;
        }
      }
      throw new IllegalArgumentException(field + "=" + id + " names no row of " + name);
    }
    throw new UnsupportedScenarioException(
        "a row of table " + tableId + ", which the game tables do not hold", field + "=" + id);
  }

  /**
   * The replay's capture block ({@link ReplayCapture}), in any format: an object of its fields
   * only, each a string, the client version and the content sha always given. It is no battle
   * input, but it names the data the replay was recorded on: a content sha other than the tables',
   * or a data version other than theirs, is refused, naming both.
   */
  private void capture(JsonNode scenario) {
    JsonNode block = scenario.get(ReplayCapture.FIELD);
    if (block == null) {
      return;
    }
    mapping.put(
        ReplayCapture.FIELD,
        "checked: the data the replay was recorded on, as the tool that saved it names it (client"
            + " version, data version, content sha, capture time); its content sha and data version"
            + " must be the tables', and it is no battle input");
    if (!block.isObject()) {
      refuse("a capture block that is not an object", ReplayCapture.FIELD + "=" + block);
      return;
    }
    onlyFields(block, ReplayCapture.FIELD, ReplayCapture.FIELDS.toArray(String[]::new));
    for (String field : ReplayCapture.FIELDS) {
      JsonNode value = block.get(field);
      if (value != null && !value.isTextual()) {
        refuse(
            "a capture block field that is not a string",
            ReplayCapture.FIELD + "." + field + "=" + value);
      }
    }
    for (String field : List.of(ReplayCapture.CLIENT_VERSION, ReplayCapture.CONTENT_SHA)) {
      if (block.get(field) == null) {
        refuse("a capture block that names no " + field, ReplayCapture.FIELD + "." + field);
      }
    }
    Optional<ReplayCapture> named = ReplayCapture.of(scenario);
    if (named.isEmpty()) {
      return;
    }
    ReplayCapture capture = named.get();
    String input;
    if (!capture.contentSha().equals(tables.contentSha())) {
      input = ReplayCapture.CONTENT_SHA + "=" + capture.contentSha();
    } else if (capture.contentVersion() != null
        && !capture.contentVersion().equals(tables.version())) {
      input = ReplayCapture.CONTENT_VERSION + "=" + capture.contentVersion();
    } else {
      return;
    }
    refuse(
        "a replay recorded on "
            + capture.recordedOn()
            + ", read against the game tables of data version "
            + tables.version()
            + " (content sha "
            + tables.contentSha()
            + ")",
        ReplayCapture.FIELD + "." + input);
  }

  /** Accepts only the value the mapping was established on. */
  private void pin(JsonNode node, String field, String expected) {
    JsonNode value = required(node, field);
    if (!value.toString().equals(expected)) {
      refuse(
          "a value of " + field + " other than " + expected + ", which has no production input",
          field + "=" + value);
    }
    mapping.put(field, "pinned: " + expected);
  }

  /**
   * The replay's events, in a format that carries them: each of a known event type, with only an
   * event's fields. They are what the players' clients showed, not commands, and give the battle no
   * input.
   */
  private void events(JsonNode scenario) {
    int index = 0;
    for (JsonNode event : required(scenario, "evt")) {
      String field = "evt[" + index + "]";
      onlyFields(event, field, ReplayFormat.EVENT_FIELDS.toArray(String[]::new));
      JsonNode type = required(event, "type");
      if (!type.isInt() || !ReplayFormat.EVENT_TYPES.contains(type.asInt())) {
        refuse(
            "an event of a type other than "
                + ReplayFormat.EVENT_TYPES.stream().sorted().toList()
                + ", whose effect on the battle is not established",
            field + ".type=" + type);
      }
      index++;
    }
    mapping.put(
        "evt",
        "carried: the replay's events, each of type "
            + ReplayFormat.EVENT_TYPES.stream().sorted().toList()
            + " with the fields "
            + ReplayFormat.EVENT_FIELDS.stream().sorted().toList()
            + "; no battle input, any other type unsupported");
  }

  /** Known field names: some named in a collection and more. */
  private static String[] with(Collection<String> names, String... more) {
    List<String> all = new ArrayList<>(List.of(more));
    all.addAll(names);
    return all.toArray(String[]::new);
  }

  /** Refuses a field the mapping does not know. */
  private void onlyFields(JsonNode node, String where, String... known) {
    List<String> names = List.of(known);
    for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
      String name = it.next();
      if (!names.contains(name)) {
        refuse("the field " + name + ", which has no mapping", where + "." + name);
      }
    }
  }

  private static JsonNode required(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null) {
      throw new IllegalArgumentException("the scenario has no " + field);
    }
    return value;
  }
}
