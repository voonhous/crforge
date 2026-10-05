package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionInstance;
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
 * A newer data version's Mega Minion hero returns after its ability: the hand-over row carries
 * ReturnToOrigin, ReturnOnTargetDeath, ReturnDelay and a ReturnWarpAction, and the hero's starting
 * group makes a context the mark and the hand-over share. The hand-over records where the hero
 * launched, waits ReturnDelay once the warp's run is gone and then flies the hero back there; while
 * it is away from idle it writes its warp window into the context, which pins the mark at the
 * origin.
 */
class BattleMegaMinionReturnTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static final String HERO = "MegaMinionHero";

  private static final String MARK = "MegaMinion_hero_mark_target";

  private static final String HAND_OVER = "MegaMinion_hero_ability_action";

  private static final String TELEPORT = "MegaMinion_hero_teleport_action";

  private static final String RETURN = "MegaMinion_hero_return_action";

  private static final String START = "MegaMinion_hero_start_actions";

  /** The wait before the return, in milliseconds, and in battle ticks of 50 ms. */
  private static final int RETURN_DELAY_MS = 3500;

  private static final int RETURN_DELAY_TICKS = RETURN_DELAY_MS / 50;

  private static final List<String> DECK =
      List.of(
          "MegaMinion", "Archer", "Knight", "Giant", "Minions", "Musketeer", "Fireball", "Arrows");

  private static final List<String> OTHER =
      List.of(
          "Knight", "Archer", "Giant", "Minions", "Musketeer", "Fireball", "Arrows", "MegaMinion");

  /**
   * The configured tables with the newer rows: the hand-over that returns and writes its warp
   * window, the return warp, the mark pinned by the window, and the starting group that creates the
   * context and starts the mark in it. Neither run is stopped by the warp's tag any more.
   */
  private static GameTables returning(Path folder) throws IOException {
    GameData.altered(
        folder,
        "actions",
        rows -> {
          ObjectNode handOver = (ObjectNode) rows.get(HAND_OVER).get("fields");
          handOver.remove("ForceStopIfTrue");
          handOver.put("ReturnDelay", RETURN_DELAY_MS);
          handOver.put("ReturnOnTargetDeath", true);
          handOver.put("ReturnToOrigin", true);
          handOver.putObject("ReturnWarpAction").put("action", RETURN);
          handOver.put("WarpWindowActiveKey", "MegaMinionWarpActive");
          handOver.put("WarpWindowOriginXKey", "MegaMinionWarpOriginX");
          handOver.put("WarpWindowOriginYKey", "MegaMinionWarpOriginY");
          ObjectNode mark = (ObjectNode) rows.get(MARK).get("fields");
          mark.remove("ForceStopIfTrue");
          mark.put("PinnedActiveExpression", "as_int(#MegaMinionWarpActive, 0)");
          mark.put("PinnedPositionXExpression", "as_int(#MegaMinionWarpOriginX, 0)");
          mark.put("PinnedPositionYExpression", "as_int(#MegaMinionWarpOriginY, 0)");
          // The return: the teleport's flight to an injected point, keeping no target.
          ObjectNode back = rows.putObject(RETURN);
          back.setAll((ObjectNode) rows.get(TELEPORT).deepCopy());
          ObjectNode backFields = (ObjectNode) back.get("fields");
          backFields.remove(
              List.of("ForceKeepTargetAfterWarp", "TargetResolver", "OnWarpEndAction"));
          ObjectNode group = rows.putObject(START);
          group.put("class", "LogicActionGroupData");
          group.put("ClassType", "ActionGroup");
          ObjectNode fields = group.putObject("fields");
          fields.put("ClassType", "ActionGroup");
          fields.put("ContextMode", "Create");
          fields.putArray("SubActions").addObject().put("action", MARK);
          fields.putArray("SubActionsDelay").add(0);
        });
    ObjectMapper mapper = new ObjectMapper();
    Path file = folder.resolve("characters.json");
    ObjectNode document = (ObjectNode) mapper.readTree(file.toFile());
    GameData.columns((ObjectNode) document.get("rows"), HERO).put("OnStartingAction", START);
    mapper.writeValue(file.toFile(), document);
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "after the warp the hand-over waits ReturnDelay from the step it first finds the warp's run"
          + " gone, then flies the hero back to where it launched; the warp window pins the mark"
          + " at that point until the hand-over is idle again")
  void theHeroReturnsToItsOrigin(@TempDir Path folder) throws IOException {
    GameTables tables = returning(folder);
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle battle = heroPlayed(tables, records);
    CharacterEntity hero = named(battle, HERO).get(0);
    int tick = battle.getBattle().getTick();
    battle.play(tick, records.card("Knight"), LEVEL, 1, 14500, 25500, "k");
    int limit = tick + 60;
    while (mark(hero).target() == null) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    LadderMatch match = battle.getMatch();
    for (int i = 0; i < 20 || match.side(0).wholeElixir() < 2; i++) {
      step(battle);
    }
    battle.useAbility(battle.getBattle().getTick(), 0, hero.getId(), "a");
    // The hero's position at the end of each step before the launch: the origin is where it
    // stands as the hand-over launches, after that step's walk.
    int beforeX = hero.getView().getX();
    int beforeY = hero.getView().getY();
    limit = battle.getBattle().getTick() + 40;
    while (!runs(hero).contains(TELEPORT)) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      beforeX = hero.getView().getX();
      beforeY = hero.getView().getY();
      step(battle);
    }
    // The window is written as the hand-over's next step begins, and the mark, listed before it,
    // reads it on the step after: from then on the mark is pinned at the origin.
    step(battle);
    step(battle);
    SetIndicatorOnTarget.Run mark = mark(hero);
    assertThat(mark.pinned()).isTrue();
    int originX = mark.pinnedX();
    int originY = mark.pinnedY();
    assertThat(Math.abs(originX - beforeX) + Math.abs(originY - beforeY)).isLessThan(100);
    assertThat(runs(hero)).contains(MARK, HAND_OVER);
    // The warp flies and lands; its run is removed by the run pass after its arrival.
    limit = battle.getBattle().getTick() + 60;
    while (runs(hero).contains(TELEPORT)) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      assertThat(mark(hero).pinned()).isTrue();
      step(battle);
    }
    // The next step the hand-over finds no warp and starts its wait; the return is launched
    // ReturnDelay later.
    for (int i = 1; i <= RETURN_DELAY_TICKS; i++) {
      step(battle);
      assertThat(runs(hero)).doesNotContain(RETURN);
      assertThat(mark(hero).pinned()).isTrue();
      assertThat(mark(hero).pinnedX()).isEqualTo(originX);
      assertThat(mark(hero).pinnedY()).isEqualTo(originY);
    }
    step(battle);
    assertThat(runs(hero)).contains(RETURN);
    // The return lands the hero on the origin, in the step its run finishes.
    ActionInstance back = listed(hero, RETURN);
    limit = battle.getBattle().getTick() + 60;
    while (!back.isFinished()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    assertThat(hero.getView().getX()).isEqualTo(originX);
    assertThat(hero.getView().getY()).isEqualTo(originY);
    // The next run pass removes the return's run; the hand-over then finds it gone and is idle,
    // its next step writes the window shut, and the mark reads it so within a step: whichever of
    // the two the list now steps first, the pin lets go within four steps of the landing.
    step(battle);
    assertThat(runs(hero)).doesNotContain(RETURN);
    int steps = 1;
    while (mark(hero).pinned()) {
      assertThat(steps).isLessThan(4);
      step(battle);
      steps++;
    }
    for (int i = 0; i < 20; i++) {
      step(battle);
      assertThat(mark(hero).pinned()).isFalse();
    }
    assertThat(runs(hero)).contains(MARK, HAND_OVER);
  }

  /** The hero's mark run. */
  private static SetIndicatorOnTarget.Run mark(CharacterEntity hero) {
    return hero.actionHolder().running().stream()
        .filter(SetIndicatorOnTarget.Run.class::isInstance)
        .map(SetIndicatorOnTarget.Run.class::cast)
        .findFirst()
        .orElseThrow();
  }

  /** A battle of the tables with the hero Mega Minion played at (3500, 14000), one step after. */
  private static Standard1v1Battle heroPlayed(GameTables tables, BattleRecords records) {
    Standard1v1Battle battle = null;
    LadderMatch match = null;
    int[] slots = new int[8];
    slots[0] = MatchSide.HERO_SLOT;
    for (int word = 0; match == null || !inHand(match, "MegaMinion"); word++) {
      battle = new Standard1v1Battle(tables);
      match = battle.startLadderMatch(DECK, OTHER, word, 0, slots, new int[8]);
    }
    while (match.side(0).wholeElixir() < records.matchCard("MegaMinion").cost()) {
      step(battle);
    }
    battle.play(
        battle.getBattle().getTick(), records.card("MegaMinion"), LEVEL, 0, 3500, 14000, "m");
    int limit = battle.getBattle().getTick() + 200;
    while (named(battle, HERO).isEmpty()) {
      assertThat(battle.getBattle().getTick()).isLessThan(limit);
      step(battle);
    }
    step(battle);
    return battle;
  }

  /** The run of a row a unit's holder lists. */
  private static ActionInstance listed(CharacterEntity unit, String row) {
    return unit.actionHolder().running().stream()
        .filter(run -> run.getAction().name().equals(row))
        .findFirst()
        .orElseThrow();
  }

  /** The names of the rows a unit's holder lists, finished runs included. */
  private static List<String> runs(CharacterEntity unit) {
    return unit.actionHolder().running().stream()
        .map(ActionInstance::getAction)
        .map(action -> action.name())
        .toList();
  }

  private static boolean inHand(LadderMatch match, String card) {
    List<MatchCard> deck = match.side(0).deck();
    return Arrays.stream(match.side(0).getHand().slots())
        .anyMatch(index -> index >= 0 && deck.get(index).name().equals(card));
  }

  /** The characters of a row the holder lists, in its order. */
  private static List<CharacterEntity> named(Standard1v1Battle battle, String row) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(CharacterEntity.class::isInstance)
        .map(CharacterEntity.class::cast)
        .filter(unit -> unit.getData().name().equals(row))
        .toList();
  }

  private static void step(Standard1v1Battle battle) {
    battle.getBattle().step();
  }
}
