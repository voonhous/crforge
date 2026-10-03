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
}
