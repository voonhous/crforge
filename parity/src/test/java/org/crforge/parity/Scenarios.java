package org.crforge.parity;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A replay scenario of the shape the adapter reads, built for the tests. */
final class Scenarios {

  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  /** The deck both sides play: six troop cards and two spells, by data id. */
  static final int[] DECK = {
    26000000, 26000001, 26000002, 26000003, 26000005, 26000014, 28000000, 28000001
  };

  private Scenarios() {
    // Utility class
  }

  /**
   * Two players of one deck at level index 0 with the princess towers, and one play: side 0's
   * Knight, given on tick 200 and run on tick 220.
   */
  static ObjectNode knight() {
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
    command.put("ct", 124);
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
   * The {@link #knight()} battle with side 0's Knight in the deck's evolution slot ({@code el} 1),
   * played three times with four other cards between its plays, so it cycles back into the hand.
   * Each play carries the packed item the player's client builds for it: the Knight's carry the
   * slot flags (bit 19) and the count plus 1 (bits 7..9), 1, 2 and 3, and the third, whose count
   * has reached its evolved row's DarkElixirCost of 2, the evolution field 1.
   */
  static ObjectNode knightEvolvedThirdPlay() {
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
   * The {@link #knight()} battle with side 1 on the Royal Chef's towers (support card 159000004,
   * level index 0) and one play of side 1's own: its Giant, run on tick 450 in its back left
   * corner, at (3500, 29000), where it stands as the king's cooking fills.
   */
  static ObjectNode knightAgainstTheRoyalChef() {
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
  static ObjectNode giantVsDuchessTower() {
    ObjectNode scenario = knight();
    ObjectNode battle = (ObjectNode) scenario.path("battle");
    ((ObjectNode) battle.path("deck1").path("sc").get(0)).put("d", 159000002);
    ((ObjectNode) battle.path("deck0").path("sp").get(0)).put("d", 26000003);
    ((ObjectNode) battle.path("deck0").path("sp").get(3)).put("d", 26000000);
    ArrayNode commands = scenario.putArray("cmd");
    addPlay(commands, 220, 26000003, 0x50400800, 3500, 14000);
    return scenario;
  }

  /** Adds side 0's play of a card, given 20 ticks before the tick it runs on. */
  private static void addPlay(ArrayNode commands, int runTick, int card, int item, int x, int y) {
    addPlay(commands, 0, runTick, card, item, x, y);
  }

  /** Adds a side's play of a card, given 20 ticks before the tick it runs on. */
  private static void addPlay(
      ArrayNode commands, int side, int runTick, int card, int item, int x, int y) {
    ObjectNode command = commands.addObject();
    command.put("ct", 124);
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
