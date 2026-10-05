package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.SetIndicatorOnTarget;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.match.MatchCard;
import org.crforge.core.battle.match.MatchSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A newer data version's Mega Minion hero mark carries a pin, PinnedActiveExpression, which reads
 * the context the hero's starting group creates: each step the mark asks it with the run's context.
 * A pin that answers 0 leaves the mark as it was; one that holds is refused; a mark started with no
 * context never asks it.
 */
class BattleMarkPinTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String HERO = "MegaMinionHero";

  private static final String MARK = "MegaMinion_hero_mark_target";

  private static final String START = "MegaMinion_hero_start_actions";

  private static final List<String> DECK =
      List.of(
          "MegaMinion", "Archer", "Knight", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  private static final List<String> OTHER =
      List.of(
          "Knight", "Archer", "Giant", "Minions", "Musketeer", "Fireball", "Arrows", "MegaMinion");

  /**
   * The configured tables with a pin on the mark and, when asked, the newer starting group that
   * creates a context and starts the mark in it.
   */
  private static GameTables withPin(Path folder, String pin, boolean inGroup) throws IOException {
    GameData.altered(
        folder,
        "actions",
        rows -> {
          ((ObjectNode) rows.get(MARK).get("fields")).put("PinnedActiveExpression", pin);
          ObjectNode group = rows.putObject(START);
          group.put("class", "LogicActionGroupData");
          group.put("ClassType", "ActionGroup");
          ObjectNode fields = group.putObject("fields");
          fields.put("ClassType", "ActionGroup");
          fields.put("ContextMode", "Create");
          fields.putArray("SubActions").addObject().put("action", MARK);
          fields.putArray("SubActionsDelay").add(0);
        });
    if (inGroup) {
      ObjectMapper mapper = new ObjectMapper();
      Path file = folder.resolve("characters.json");
      ObjectNode document = (ObjectNode) mapper.readTree(file.toFile());
      GameData.columns((ObjectNode) document.get("rows"), HERO).put("OnStartingAction", START);
      mapper.writeValue(file.toFile(), document);
    }
    return GameTables.load(folder);
  }

  /** A battle of the tables with the hero Mega Minion played at (3500, 14000), and a Knight. */
  private static Standard1v1Battle heroAndKnight(GameTables tables) {
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    int[] slots = new int[8];
    slots[0] = MatchSide.HERO_SLOT;
    for (int word = 0; match == null || !inHand(match, "MegaMinion"); word++) {
      battle = new Standard1v1Battle(tables);
      match = battle.startLadderMatch(DECK, OTHER, word, 0, slots, new int[8]);
    }
    while (match.side(0).wholeElixir() < records.matchCard("MegaMinion").cost()) {
      battle.getBattle().step();
    }
    int tick = battle.getBattle().getTick();
    battle.play(tick, records.card("MegaMinion"), LEVEL, 0, 3500, 14000, "m");
    battle.play(tick, records.card("Knight"), LEVEL, 1, 14500, 25500, "k");
    return battle;
  }

  private static boolean inHand(LadderMatch match, String card) {
    List<MatchCard> deck = match.side(0).deck();
    return Arrays.stream(match.side(0).getHand().slots())
        .anyMatch(index -> index >= 0 && deck.get(index).name().equals(card));
  }

  /** The id of the object the hero's mark holds within 200 steps, or -1. */
  private static int markedWithin200(Standard1v1Battle battle) {
    for (int i = 0; i < 200; i++) {
      battle.getBattle().step();
      for (CharacterEntity unit : characters(battle, HERO)) {
        for (var run : unit.actionHolder().running()) {
          if (run instanceof SetIndicatorOnTarget.Run mark && mark.target() != null) {
            return mark.target().id();
          }
        }
      }
    }
    return -1;
  }

  private static List<CharacterEntity> characters(Standard1v1Battle battle, String row) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(CharacterEntity.class::isInstance)
        .map(CharacterEntity.class::cast)
        .filter(unit -> unit.getData().name().equals(row))
        .toList();
  }

  @Test
  @DisplayName(
      "a mark started in a context asks its pin, which answers 0 while nothing wrote the key: it"
          + " marks the Knight as before")
  void aPinThatAnswersZeroChangesNothing(@TempDir Path folder) throws IOException {
    Standard1v1Battle battle =
        heroAndKnight(withPin(folder, "as_int(#MegaMinionWarpActive, 0)", true));

    int marked = markedWithin200(battle);
    assertThat(marked).isEqualTo(characters(battle, "Knight").get(0).getId());
  }

  @Test
  @DisplayName("a pin that holds, read from the context with its default 1, is refused")
  void aPinThatHoldsIsRefused(@TempDir Path folder) throws IOException {
    Standard1v1Battle battle =
        heroAndKnight(withPin(folder, "as_int(#MegaMinionWarpActive, 1)", true));

    assertThatThrownBy(() -> markedWithin200(battle))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("is pinned by its PinnedActiveExpression");
  }

  @Test
  @DisplayName("a mark started with no context never asks its pin, even one that would hold")
  void aMarkWithNoContextNeverAsks(@TempDir Path folder) throws IOException {
    Standard1v1Battle battle = heroAndKnight(withPin(folder, "1", false));

    int marked = markedWithin200(battle);
    assertThat(marked).isEqualTo(characters(battle, "Knight").get(0).getId());
  }
}
