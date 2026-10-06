package org.crforge.parity;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.crforge.core.battle.data.GameClients;

/**
 * The replay fields that differ between data versions, and how {@link ReplayScenario} reads each in
 * a version's replays. Every version's replays share the fields that version 14.593.1's write; a
 * later version's replays write more fields, and pin some shared ones to other values.
 *
 * <p>A version is listed here only once its replays' fields have been decided, each one consumed,
 * pinned or carried as {@link ReplayScenario} describes. A replay of any other version is read by
 * the 14.593.1 fields, so a field only a later version writes is refused as one with no mapping.
 *
 * <p>A case generated for the tests ({@link ScenarioShape#GENERATED}) is written in 14.593.1's
 * replay shape whatever its version, and is read by a version's generated cases' fields ({@link
 * #generated}), listed once the version's recorded battles of such cases have established them.
 *
 * @param dataVersion the data version, as the game tables name it
 * @param rootPins the replay's own fields pinned to one value, by field
 * @param events whether the replay's events ({@code evt}) are carried, each of an event type in
 *     {@link #EVENT_TYPES}; else they are pinned to none
 * @param battlePins the battle header's fields pinned to one value beyond those every version pins,
 *     by field
 * @param avatarPins each avatar's fields pinned to one value, by field
 * @param avatarCarried each avatar's fields carried, by field, with what each is
 * @param accountHighOptional whether an avatar may leave out the high word of its account id, read
 *     as 0
 * @param deckCarried each deck's fields carried beyond its cards and its tower selection
 * @param cardCarried each deck card's fields carried beyond its row, level and slot flags
 * @param selectionPins the tower selection's fields pinned to one value, by field
 * @param selectionCarried the tower selection's fields carried
 * @param playerData how each player's data ({@code hbd}) is read: {@code null} for the 14.593.1
 *     rule, only data with no emotes listed; else the fields an entry may hold, its king level
 *     {@code kt} among them
 * @param cosmeticCarried whether a play's packed cosmetic field (bits 17..18) is carried; else it
 *     must be 0
 */
public record ReplayFormat(
    String dataVersion,
    Map<String, String> rootPins,
    boolean events,
    Map<String, String> battlePins,
    Map<String, String> avatarPins,
    Map<String, String> avatarCarried,
    boolean accountHighOptional,
    List<String> deckCarried,
    List<String> cardCarried,
    Map<String, String> selectionPins,
    List<String> selectionCarried,
    List<String> playerData,
    boolean cosmeticCarried) {

  /** The event types of a version whose events are carried. */
  public static final Set<Integer> EVENT_TYPES = Set.of(1, 3, 5);

  /** The fields of one event. */
  public static final Set<String> EVENT_FIELDS =
      Set.of("type", "id_hi", "id_lo", "ticks", "params", "coords");

  /** The player data field that gives the side's king level, counted from 1. */
  public static final String KING_LEVEL_FIELD = "kt";

  /** The fields of version 14.593.1's replays, which every version's replays share. */
  public static final ReplayFormat V14_593_1 =
      new ReplayFormat(
          "14.593.1",
          Map.of(),
          false,
          Map.of(),
          ordered("expLevel", "1", "npc", "false"),
          Map.of(),
          false,
          List.of(),
          List.of(),
          ordered("t", "0", "c", "1"),
          List.of(),
          null,
          false);

  /**
   * The fields of version 16.402.18's replays: the request lists ({@code srq}, {@code srs}) and
   * three header switches pinned to the one value they are established on; the replay's events, the
   * players' profiles, the deck cards' cosmetics, the tower card's counts and flags and the
   * players' data other than the king level carried, with no battle input; each side's king level
   * read from its player data.
   */
  public static final ReplayFormat V16_402_18 =
      new ReplayFormat(
          "16.402.18",
          ordered("srq", "[]", "srs", "[]"),
          true,
          ordered("cardlvlmin", "0", "rrb", "false", "seb", "false"),
          ordered("npc", "false"),
          ordered(
              "expLevel",
              "the player's experience level, no battle input: the king's level is its player"
                  + " data's kt",
              "expPoints",
              "presentation",
              "totalExpPoints",
              "presentation",
              "clan_name",
              "presentation",
              "clan_id_hi",
              "presentation",
              "clan_id_lo",
              "presentation",
              "badge",
              "presentation",
              "welo",
              "presentation",
              "scr",
              "presentation",
              "spi",
              "presentation",
              "spt",
              "presentation",
              "lti",
              "presentation",
              "birth_date",
              "presentation"),
          true,
          List.of("hdr"),
          List.of("pr", "sc", "hsc"),
          ordered("t", "0"),
          List.of("c", "newu", "newf"),
          List.of(
              KING_LEVEL_FIELD,
              "em",
              "sk",
              "bn",
              "br",
              "brp",
              "egr",
              "npc",
              "hcl",
              "dts",
              "dbi",
              "ptix",
              "rrsmr",
              "ts"),
          true);

  /**
   * The fields of a case generated for version 16.402.18 ({@link ScenarioShape#GENERATED}): version
   * 14.593.1's, read with 16.402.18's command types. Such a case leaves out the request lists
   * ({@code srq}, {@code srs}), the header switches {@code cardlvlmin}, {@code rrb} and {@code
   * seb}, and the players' king levels ({@code kt}), and keeps 14.593.1's arena. The recorded
   * battles of such cases play the same with the request lists empty, the switches at the values
   * this version's replays pin, the arena of this version's replays and a king level of 1 for each
   * side added, so each king stands at level 1, as the 14.593.1 fields give it; without a king
   * level, the avatar's exp level does not change it.
   */
  public static final ReplayFormat GENERATED_16_402_18 = V14_593_1.forDataVersion("16.402.18");

  /**
   * The format of each data version whose replays' fields have been decided. The fields are the
   * game client's: client 16.402.17's replays of data version 16.402.18 decided them for every data
   * version that client runs ({@link GameClients#CLIENT_16_402_17}); its replays of 16.426.22 write
   * the same fields.
   */
  private static final Map<String, ReplayFormat> BY_VERSION = byVersion();

  /**
   * The fields of each data version's generated cases, once the version's recorded battles of such
   * cases have established them. A 14.593.1 case is in its own version's replay shape.
   */
  private static final Map<String, ReplayFormat> GENERATED_BY_VERSION =
      Map.of(
          V14_593_1.dataVersion(),
          V14_593_1,
          GENERATED_16_402_18.dataVersion(),
          GENERATED_16_402_18);

  /** The table of {@link #BY_VERSION}. */
  private static Map<String, ReplayFormat> byVersion() {
    Map<String, ReplayFormat> byVersion = new HashMap<>();
    byVersion.put(V14_593_1.dataVersion(), V14_593_1);
    for (String version : GameClients.CLIENT_16_402_17) {
      byVersion.put(version, V16_402_18.forDataVersion(version));
    }
    return Map.copyOf(byVersion);
  }

  /**
   * The format of a data version's replays.
   *
   * @param dataVersion the data version, or null
   * @return its format, or empty when its fields have not been decided
   */
  public static Optional<ReplayFormat> of(String dataVersion) {
    return dataVersion == null
        ? Optional.empty()
        : Optional.ofNullable(BY_VERSION.get(dataVersion));
  }

  /**
   * The format a replay read against tables of a data version is read by: the version's own, else
   * 14.593.1's.
   */
  public static ReplayFormat orShared(String dataVersion) {
    return of(dataVersion).orElse(V14_593_1);
  }

  /**
   * The format a data version's generated cases are read by ({@link ScenarioShape#GENERATED}).
   * Unlike a replay's, it does not fall back to 14.593.1's: a version's generated cases are read
   * only once its recorded battles have established their fields.
   *
   * @param dataVersion the data version, or null
   * @return its generated cases' format, or empty when it is not established
   */
  public static Optional<ReplayFormat> generated(String dataVersion) {
    return dataVersion == null
        ? Optional.empty()
        : Optional.ofNullable(GENERATED_BY_VERSION.get(dataVersion));
  }

  /** The same fields, as another data version's. */
  private ReplayFormat forDataVersion(String version) {
    return new ReplayFormat(
        version,
        rootPins,
        events,
        battlePins,
        avatarPins,
        avatarCarried,
        accountHighOptional,
        deckCarried,
        cardCarried,
        selectionPins,
        selectionCarried,
        playerData,
        cosmeticCarried);
  }

  /** Whether the player data entries are read field by field, the king level among them. */
  public boolean kingLevelFromPlayerData() {
    return playerData != null;
  }

  /** Pairs in the order given, as a map that keeps that order. */
  private static Map<String, String> ordered(String... pairs) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put(pairs[i], pairs[i + 1]);
    }
    return Collections.unmodifiableMap(map);
  }
}
