/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Function;

/** A replay scenario of the shape the adapter reads, built for the tests. */
public final class Scenarios {

  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  /** The deck both sides play: six troop cards and two spells, by data id. */
  public static final int[] DECK = {
    26000000, 26000001, 26000002, 26000003, 26000005, 26000014, 28000000, 28000001
  };

  private Scenarios() {
    // Utility class
  }

  /** The command type of a card play in the configured data version's replays. */
  public static final int PLAY = 153;

  /** The command type of an ability command in the configured data version's replays. */
  public static final int ABILITY = 189;

  /**
   * Two players of one deck at level index 0 with the princess towers, and one play: side 0's
   * Knight, given on tick 200 and run on tick 220, as a replay of the configured data version
   * writes it with the least it must hold: the request lists, the header's switches and each side's
   * king level ({@code kt}) in its player data, 1.
   */
  public static ObjectNode knight() {
    ObjectNode scenario = generatedKnight();
    scenario.putArray("srq");
    scenario.putArray("srs");
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    battle.put("cardlvlmin", 0);
    battle.put("rrb", false);
    battle.put("seb", false);
    for (JsonNode data : battle.path("hbd")) {
      ((ObjectNode) data).put("kt", 1);
    }
    return scenario;
  }

  /**
   * The {@link #knight()} battle as a case generated for data version 16.402.18 writes it: the
   * replay shape every version shares ({@link ScenarioShape#GENERATED}) with the play's command
   * type 153. It has no request lists, no header switches of that version and no king levels in its
   * player data, and keeps the shared shape's arena.
   */
  public static ObjectNode generatedKnight() {
    ObjectNode scenario = JSON.objectNode();
    scenario.put("rndSeed", 1131);
    scenario.put("time", 1775066287);
    ObjectNode battle = scenario.putObject("battle");
    battle.put("gmt", 1);
    battle.put("plt", 1);
    battle.put("t1s", 0);
    battle.put("t2s", 0);
    battle.put("gamemode", 72000006);
    battle.put("hm", false);
    battle.put("lvlcap", 0);
    battle.put("stp", 0);
    battle.put("trail", 170000000);
    battle.put("te", -1);
    battle.put("tps", 1);
    battle.put("arena", 54000001);
    for (int side = 0; side < 2; side++) {
      ObjectNode deck = battle.putObject("deck" + side);
      ArrayNode cards = deck.putArray("sp");
      for (int id : DECK) {
        cards.addObject().put("d", id).put("l", 0);
      }
      deck.putArray("sc").addObject().put("d", 159000000).put("l", 0).put("t", 0).put("c", 1);
      ObjectNode avatar = battle.putObject("avatar" + side);
      avatar.put("accountID.hi", 0);
      avatar.put("accountID.lo", side + 1);
      avatar.put("expLevel", 1);
      avatar.put("name", "Offline " + side);
      avatar.put("arena", 54000001);
      avatar.put("npc", false);
    }
    battle.put("location", 15000002);
    ArrayNode hbd = battle.putArray("hbd");
    for (int side = 0; side < 2; side++) {
      ObjectNode emotes = hbd.addObject().putObject("em");
      emotes.putArray("oe");
      emotes.putArray("de");
    }
    ObjectNode command = scenario.putArray("cmd").addObject();
    command.put("ct", PLAY);
    ObjectNode body = command.putObject("c");
    body.put("t", 200);
    body.put("t2", 220);
    body.put("idHi", 0);
    body.put("idLo", 1);
    body.put("px", 3500);
    body.put("py", 14000);
    body.put("sid", -1);
    body.putObject("sel").put("os", 26000000).put("pd", 0x30400000);
    scenario.putArray("evt");
    return scenario;
  }

  /**
   * The {@link #knight()} battle as a replay of the game client whose data version is 16.402.18
   * writes it in full, with made-up players: the arena of that version; each avatar's profile, each
   * deck's header, each card's cosmetics and the tower card's count and flags; player data holding
   * each side's king level ({@code kt}: 15 for side 0, 16 for side 1) beside emotes, skins and a
   * banner; a carried event of each type; the Knight's item with its cosmetic field (bits 17..18)
   * 2; and side 1's avatar without the high word of its account id, which its commands give as 0.
   */
  public static ObjectNode knightWithEveryField() {
    ObjectNode scenario = knight();
    scenario.put("endTick", 3681);
    scenario.putArray("srq");
    scenario.putArray("srs");
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    battle.put("cardlvlmin", 0);
    battle.put("rrb", false);
    battle.put("seb", false);
    battle.put("arena", 54000144);
    battle.put("location", 15000170);
    ArrayNode hbd = battle.putArray("hbd");
    for (int side = 0; side < 2; side++) {
      ObjectNode avatar = (ObjectNode) battle.path("avatar" + side);
      avatar.put("arena", 54000144);
      avatar.put("expLevel", 40 + side);
      avatar.put("expPoints", 100);
      avatar.put("totalExpPoints", 1000);
      avatar.put("clan_name", "Made Up Clan");
      avatar.put("clan_id_hi", 1);
      avatar.put("clan_id_lo", 2);
      avatar.put("badge", 16000000);
      avatar.put("welo", -1);
      avatar.put("scr", 5000);
      avatar.put("spi", 1);
      avatar.put("spt", 2);
      avatar.put("lti", 143);
      avatar.put("birth_date", 0);
      ObjectNode deck = (ObjectNode) battle.path("deck" + side);
      deck.put("hdr", "abcd");
      ObjectNode first = (ObjectNode) deck.path("sp").get(0);
      first.put("pr", 2);
      first.put("sc", 6);
      first.put("hsc", 200);
      ((ObjectNode) deck.path("sc").get(0)).put("c", 0).put("newu", false).put("newf", false);
      ObjectNode data = hbd.addObject();
      data.put("kt", 15 + side);
      ObjectNode emotes = data.putObject("em");
      emotes.putArray("oe").add(4294967296L);
      emotes.putArray("de").addObject().put("p", 1).put("e", 0);
      ObjectNode skins = data.putObject("sk");
      skins.putArray("os").add(87000001);
      skins.putArray("ss").add(87000001);
      skins.put("ats", 87000001);
      skins.put("rnd", false);
      data.putObject("bn").put("bg", 126000000).put("fg", 126000001);
      data.put("npc", 3);
      data.put("hcl", 1500);
      data.put("dts", 181000037);
      data.put("dbi", 4);
      data.put("ptix", -1);
      data.put("rrsmr", 14000004);
      data.put("ts", 1);
    }
    ((ObjectNode) battle.path("avatar1")).remove("accountID.hi");
    ArrayNode events = scenario.putArray("evt");
    events.add(event(1, 1, 108).put("type", 1));
    events.add(event(1, 2, 150).put("type", 3));
    ObjectNode drawn = event(0, 1, 245).put("type", 5);
    drawn.putArray("coords").add(-100).add(87);
    events.add(drawn);
    // A sticker side 1 sent: its kind, its sender's side and its id.
    ObjectNode sticker = event(0, 2, 300).put("type", 10);
    ((ArrayNode) sticker.path("params")).add(1).add(46);
    events.add(sticker);
    ObjectNode command = (ObjectNode) scenario.path("cmd").get(0);
    ((ObjectNode) command.path("c").path("sel")).put("pd", 0x30400000 | (2 << 17));
    return scenario;
  }

  /** A replay event of a side's account at a tick, with one parameter; its type is put after. */
  private static ObjectNode event(int parameter, int accountLo, int tick) {
    ObjectNode event = JSON.objectNode();
    event.put("id_hi", 0);
    event.put("id_lo", accountLo);
    event.putArray("ticks").add(tick);
    event.putArray("params").add(parameter);
    return event;
  }

  /**
   * The {@link #knight()} battle with side 0's Knight in the deck's evolution slot ({@code el} 1),
   * played three times with four other cards between its plays, so it cycles back into the hand.
   * Each play carries the packed item the player's client builds for it: the Knight's carry the
   * slot flags (bit 19) and the count plus 1 (bits 7..9), 1, 2 and 3, and the third, whose count
   * has reached its evolved row's DarkElixirCost of 2, the evolution field 1.
   */
  public static ObjectNode knightEvolvedThirdPlay() {
    ObjectNode scenario = knight();
    ((ObjectNode) scenario.path("battle").path("deck0").path("sp").get(0)).put("el", 1);
    ArrayNode commands = scenario.putArray("cmd");
    addPlay(commands, 220, 26000000, 0x30480080, 3500, 14000);
    addPlay(commands, 260, 26000002, 0x20c00000, 14500, 3000);
    addPlay(commands, 300, 26000001, 0x30800000, 14500, 3000);
    addPlay(commands, 340, 26000005, 0x31400000, 14500, 3000);
    addPlay(commands, 461, 28000001, 0x32000000, 9000, 9000);
    addPlay(commands, 630, 26000000, 0x30480100, 3500, 14000);
    addPlay(commands, 742, 26000002, 0x20c00000, 14500, 3000);
    addPlay(commands, 911, 26000001, 0x30800000, 14500, 3000);
    addPlay(commands, 1079, 26000005, 0x31400000, 14500, 3000);
    addPlay(commands, 1248, 28000001, 0x32000000, 9000, 9000);
    addPlay(commands, 1416, 26000000, 0x30480181, 3500, 14000);
    return scenario;
  }

  /**
   * The {@link #knight()} battle with the Archer Queen in place of the Knight at deck index 0 of
   * both decks: side 0 plays her on tick 220 at (3500, 14000), and an ability command given on tick
   * 330 and run on tick 350 names her by her game object id, 5000006, the first unit made after the
   * six towers. She shoots the left princess tower from tick 317.
   */
  public static ObjectNode archerQueenAbility() {
    ObjectNode scenario = knight();
    for (int side = 0; side < 2; side++) {
      ((ObjectNode) scenario.path("battle").path("deck" + side).path("sp").get(0))
          .put("d", ARCHER_QUEEN);
    }
    // Cost 5, a Champion at level index 0 (level field 10) and deck index field 1: her plain item.
    ((ObjectNode) scenario.path("cmd").get(0).path("c").path("sel"))
        .put("os", ARCHER_QUEEN)
        .put("pd", 0x50402800);
    addAbility((ArrayNode) scenario.path("cmd"), 350, 1, 5000006);
    return scenario;
  }

  /** The Mirror's card, by data id. */
  public static final int MIRROR = 28000006;

  /** The Knight's card, by data id. */
  public static final int KNIGHT = 26000000;

  /** The Archer's card, by data id. */
  public static final int ARCHER = 26000001;

  /**
   * The {@link #knight()} battle with side 0 on a deck that holds the Mirror at deck index 5: it
   * plays Skeletons, Goblins, the Archer and the Knight, and then, on tick 410, the Mirror, which
   * the Knight's play has put back in the hand. Side 1 holds its own eight cards and plays none.
   *
   * <p>The Mirror's item ({@code sel}) is what the player's client builds for it: {@code os} the
   * Mirror, {@code fs} the card it repeats, the Knight, and {@code pd} the Mirror's deck index plus
   * 1 (6), its level field plus 1 (an Epic at level index 0 is level field 5, so 6) and its cost
   * plus the Knight's (1 + 3).
   */
  public static ObjectNode knightThenMirror() {
    ObjectNode scenario = knight();
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    int[][] decks = {
      {28000001, 26000005, 27000000, KNIGHT, ARCHER, MIRROR, 26000010, 26000002},
      {26000002, ARCHER, 26000003, KNIGHT, 26000014, 26000005, 27000000, 26000010}
    };
    for (int side = 0; side < 2; side++) {
      ArrayNode cards = ((ObjectNode) battle.path("deck" + side)).putArray("sp");
      for (int id : decks[side]) {
        cards.addObject().put("d", id).put("l", 0);
      }
    }
    ArrayNode commands = scenario.putArray("cmd");
    addPlay(commands, 230, 26000010, 0x11c00000, 3500, 9000);
    addPlay(commands, 260, 26000002, 0x22000000, 3500, 9000);
    addPlay(commands, 290, ARCHER, 0x31400000, 14500, 9000);
    addPlay(commands, 320, KNIGHT, 0x31000000, 14500, 9500);
    addPlay(commands, 410, MIRROR, 0x41801800, 14500, 9500);
    ((ObjectNode) commands.get(4).path("c").path("sel")).put("fs", KNIGHT);
    return scenario;
  }

  /** The Merge Maiden's card, a variant card, by data id. */
  public static final int MERGE_MAIDEN = 28000025;

  /** The mounted maiden's own card row, MergeMaiden_Mounted, by data id. */
  public static final int MOUNTED_MAIDEN_CARD = 26000105;

  /** The Giant's card, by data id. */
  public static final int GIANT = 26000003;

  /**
   * The mounted Merge Maiden's item: the option field 1 (option 0, MergeMaiden_Mounted), a
   * Legendary at level index 0 (level field 8), the deck index field 1 and the option's cost, 6.
   */
  public static final int MOUNTED_MAIDEN_ITEM = 0x60402010;

  /** The Merge Maiden's item on foot: the option field 2 (MergeMaiden_Normal) for its cost, 3. */
  public static final int MAIDEN_ON_FOOT_ITEM = 0x30402020;

  /**
   * The {@link #knight()} battle with side 0 on a deck that holds the Merge Maiden at deck index 0
   * and the Giant at index 2, both in the opening hand, and side 1 on its own eight cards, playing
   * none. Side 0 plays the Merge Maiden on tick 240 at (14500, 9500): the player's client picks its
   * option from the king's elixir, about 9.9 then, and takes the mounted maiden, whose trigger is
   * 6.
   */
  public static ObjectNode mergeMaidenMounted() {
    ObjectNode scenario = mergeMaidenDecks();
    ArrayNode commands = scenario.putArray("cmd");
    addPlay(commands, 240, MERGE_MAIDEN, MOUNTED_MAIDEN_ITEM, 14500, 9500);
    return scenario;
  }

  /**
   * The {@link #mergeMaidenMounted()} decks with side 0's Giant played first, on tick 230 for 5
   * elixir, and the Merge Maiden on tick 270: its option is picked from about 5.3 elixir, below the
   * mounted maiden's trigger, so it is played as the maiden on foot, for 3.
   */
  public static ObjectNode mergeMaidenOnFoot() {
    ObjectNode scenario = mergeMaidenDecks();
    ArrayNode commands = scenario.putArray("cmd");
    addPlay(commands, 230, GIANT, 0x50c00800, 14500, 8500);
    addPlay(commands, 270, MERGE_MAIDEN, MAIDEN_ON_FOOT_ITEM, 14500, 9500);
    return scenario;
  }

  /** The {@link #knight()} battle on the Merge Maiden's decks, with no command. */
  private static ObjectNode mergeMaidenDecks() {
    ObjectNode scenario = knight();
    putDecks(
        scenario,
        new int[] {MERGE_MAIDEN, 26000002, GIANT, ARCHER, 28000001, KNIGHT, 26000005, 26000010},
        new int[] {26000002, ARCHER, GIANT, KNIGHT, 26000014, 26000005, 27000000, 26000010});
    return scenario;
  }

  /**
   * A battle in which side 0 plays the mounted Merge Maiden and later the Mirror, with the maiden
   * as its side's last card: Skeletons on 230, Goblins on 260, Zap on 290, the maiden (deck index
   * 3) on 325 and the Mirror (deck index 5, level index 3) on 691, whose item names the mounted
   * maiden's row, MergeMaiden_Mounted, as the card it repeats. Side 1 plays the Minions on 500 and
   * the Knight on 540.
   */
  public static ObjectNode mergeMaidenThenMirror() {
    ObjectNode scenario = knight();
    putDecks(
        scenario,
        new int[] {KNIGHT, 26000005, ARCHER, MERGE_MAIDEN, 28000008, MIRROR, 26000010, 26000002},
        new int[] {26000002, ARCHER, GIANT, KNIGHT, 26000014, 26000005, 27000000, 26000010});
    ((ObjectNode) scenario.path("battle").path("deck0").path("sp").get(5)).put("l", 3);
    ArrayNode commands = scenario.putArray("cmd");
    addPlay(commands, 230, 26000010, 0x11c00000, 3500, 9000);
    addPlay(commands, 260, 26000002, 0x22000000, 3500, 9000);
    addPlay(commands, 290, 28000008, 0x21400000, 3500, 24500);
    addPlay(commands, 325, MERGE_MAIDEN, 0x61002010, 14500, 9500);
    addPlay(commands, 1, 500, 26000005, 0x31800000, 14500, 22000);
    addPlay(commands, 1, 540, KNIGHT, 0x31000000, 14500, 21000);
    addPlay(commands, 691, MIRROR, 0x71802400, 14500, 9500);
    ((ObjectNode) commands.get(6).path("c").path("sel")).put("fs", MOUNTED_MAIDEN_CARD);
    return scenario;
  }

  /** Puts both sides' eight cards, each at level index 0. */
  private static void putDecks(ObjectNode scenario, int[] deck0, int[] deck1) {
    int[][] decks = {deck0, deck1};
    for (int side = 0; side < 2; side++) {
      ArrayNode cards = ((ObjectNode) scenario.path("battle").path("deck" + side)).putArray("sp");
      for (int id : decks[side]) {
        cards.addObject().put("d", id).put("l", 0);
      }
    }
  }

  /** The Archer Queen's card, by data id. */
  public static final int ARCHER_QUEEN = 26000072;

  /**
   * Adds an ability command of the side whose account's low word is given, given 20 ticks before
   * the tick it runs on, naming a unit by its game object id.
   */
  public static void addAbility(ArrayNode commands, int runTick, int accountLo, int objectId) {
    ObjectNode command = commands.addObject();
    command.put("ct", ABILITY);
    ObjectNode body = command.putObject("c");
    body.put("t", runTick - 20);
    body.put("t2", runTick);
    body.put("idHi", 0);
    body.put("idLo", accountLo);
    body.put("cgid", objectId);
  }

  /**
   * The {@link #knight()} battle with side 1 on the Royal Chef's towers (support card 159000004,
   * level index 0) and one play of side 1's own: its Giant, run on tick 450 in its back left
   * corner, at (3500, 29000), where it stands as the king's cooking fills.
   */
  public static ObjectNode knightAgainstTheRoyalChef() {
    ObjectNode scenario = knight();
    ((ObjectNode) scenario.path("battle").path("deck1").path("sc").get(0)).put("d", 159000004);
    addPlay((ArrayNode) scenario.path("cmd"), 1, 450, 26000003, 0x51000800, 3500, 29000);
    return scenario;
  }

  /**
   * The {@link #knight()} battle with side 1's towers the Dagger Duchess's (support card 159000002)
   * and side 0's Giant in place of its Knight: the Giant takes deck index 0 and the Knight index 3,
   * so the Giant is in the opening hand, and it is played where the Knight was, on tick 220.
   */
  public static ObjectNode giantVsDuchessTower() {
    ObjectNode scenario = knight();
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    ((ObjectNode) battle.path("deck1").path("sc").get(0)).put("d", 159000002);
    ((ObjectNode) battle.path("deck0").path("sp").get(0)).put("d", 26000003);
    ((ObjectNode) battle.path("deck0").path("sp").get(3)).put("d", 26000000);
    ArrayNode commands = scenario.putArray("cmd");
    addPlay(commands, 220, 26000003, 0x50400800, 3500, 14000);
    return scenario;
  }

  /**
   * Writes into a copy of the tables the columns the play ticks of {@link
   * #knightEvolvedThirdPlay()}, {@link #knightThenMirror()}, {@link #mergeMaidenMounted()}, {@link
   * #mergeMaidenOnFoot()} and {@link #mergeMaidenThenMirror()} were planned against, so that each
   * play is given when its side's elixir holds its cost and a variant play is picked as the option
   * its item names, whatever the configured tables' costs and elixir rate are: the Ladder
   * timeline's starting elixir, 6, and full bar at the first rate, 28000 ms; the most elixir there
   * can be, 10; each played card's cost; the evolved Knight's DarkElixirCost, 2, so the Knight is
   * evolved on its third play; and the Merge Maiden's options, mounted from 6 elixir and on foot
   * from 3, each with 1200 ms of projected time.
   *
   * <p>A test that runs one of those scenarios through the battle writes these before it fits the
   * items, which then read the written costs.
   *
   * @param rows a table's rows by the table's name, to write into
   */
  public static void writePlannedColumns(Function<String, ObjectNode> rows) {
    ObjectNode timeline = columns(rows, "battle_timelines", "Default");
    timeline.put("StartingElixir", 6);
    ((ArrayNode) timeline.get("ElixirFullBarMS")).set(0, 28000);
    columns(rows, "globals", "MAX_MANA").put("NumberValue", 10);
    String[][] costs = {
      {"spells_characters", "Knight", "3"},
      {"spells_characters", "Archer", "3"},
      {"spells_characters", "Goblins", "2"},
      {"spells_characters", "Giant", "5"},
      {"spells_characters", "Minions", "3"},
      {"spells_characters", "Skeletons", "1"},
      {"spells_characters", "MergeMaiden_Mounted", "6"},
      {"spells_characters", "MergeMaiden_Normal", "3"},
      {"spells_other", "Arrows", "3"},
      {"spells_other", "Mirror", "1"},
      {"spells_other", "Zap", "2"},
      {"spells_other", "MergeMaiden", "6"},
      {"spells_evolved", "Knight_EV1", "3"}
    };
    for (String[] cost : costs) {
      columns(rows, cost[0], cost[1]).put("ManaCost", Integer.parseInt(cost[2]));
    }
    columns(rows, "spells_evolved", "Knight_EV1").put("DarkElixirCost", 2);
    ObjectNode maiden = columns(rows, "spells_other", "MergeMaiden");
    maiden.put("UseProjectedTimeSummon", true);
    int[] triggers = {6000, 3000};
    for (int option = 0; option < triggers.length; option++) {
      ((ObjectNode) maiden.get("Options").get(option))
          .put("AvailableManaTrigger", triggers[option])
          .put("PrecastPendingTime", 1200);
    }
  }

  /** The columns of a row of a table, to write into. */
  private static ObjectNode columns(Function<String, ObjectNode> rows, String table, String row) {
    return (ObjectNode) rows.apply(table).get(row).get("columns");
  }

  /** Adds side 0's play of a card, given 20 ticks before the tick it runs on. */
  private static void addPlay(ArrayNode commands, int runTick, int card, int item, int x, int y) {
    addPlay(commands, 0, runTick, card, item, x, y);
  }

  /** Adds a side's play of a card, given 20 ticks before the tick it runs on. */
  private static void addPlay(
      ArrayNode commands, int side, int runTick, int card, int item, int x, int y) {
    ObjectNode command = commands.addObject();
    command.put("ct", PLAY);
    ObjectNode body = command.putObject("c");
    body.put("t", runTick - 20);
    body.put("t2", runTick);
    body.put("idHi", 0);
    body.put("idLo", side + 1);
    body.put("px", x);
    body.put("py", y);
    body.put("sid", -1);
    body.putObject("sel").put("os", card).put("pd", item);
  }
}
