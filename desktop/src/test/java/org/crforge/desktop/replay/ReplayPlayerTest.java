package org.crforge.desktop.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.replay.ScenarioItems;
import org.crforge.core.battle.replay.ScenarioPlan;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.desktop.battle.BattleAdapter;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.desktop.battle.EntityView;
import org.crforge.desktop.render.ViewOrientation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The replay screen's state with no graphics: the replay's commands run at their ticks and are
 * noted, the clock steps by real time at the chosen speed, pause stops it, R restarts at tick 0,
 * and the replay stops at its end tick with both results shown.
 */
class ReplayPlayerTest {

  /** Seconds one battle step covers. */
  private static final float STEP_SECONDS = Battle.STEP_MS / 1000f;

  private static ReplayPlayer player(ObjectNode document) {
    return new ReplayPlayer(
        ReplayFile.parse(Path.of("replay.json"), document, Replays.tables()), Replays.tables());
  }

  /** A replay document with its plays' items fitted to the tables: their rows' costs and levels. */
  private static ObjectNode fit(ObjectNode document) {
    return ScenarioItems.fitted(document, Replays.tables());
  }

  @Test
  void manualStepPausesRunsOneTickAndDiscardsFractionalPlaybackTime() {
    ReplayPlayer player = player(fit(Replays.archerQueen()));
    player.advance(STEP_SECONDS * 0.75f);
    assertThat(player.stepOnce()).isTrue();
    assertThat(player.getSession().tick()).isEqualTo(1);
    assertThat(player.isPaused()).isTrue();
    assertThat(player.advance(1f)).isZero();
    player.togglePause();
    assertThat(player.advance(STEP_SECONDS * 0.5f)).isZero();
    assertThat(player.stepOnce()).isTrue();
    assertThat(player.getSession().tick()).isEqualTo(2);
    while (player.step()) {}
    assertThat(player.stepOnce()).isFalse();
    assertThat(player.getSession().tick()).isEqualTo(400);
  }

  @Test
  @DisplayName("the replay's play and ability command run at their ticks and are noted")
  void commandsRunAndAreNoted() {
    ReplayPlayer player = player(fit(Replays.archerQueen()));

    while (player.step()) {
      // To the replay's end tick
    }

    Standard1v1Battle battle = player.getSession().getBattle();
    assertThat(battle.getPlays()).extracting(Standard1v1Battle.Play::name).containsExactly("cmd0");
    assertThat(battle.getPlays().get(0).tick()).isEqualTo(220);
    assertThat(battle.getAbilityUses())
        .extracting(Standard1v1Battle.AbilityUse::name)
        .containsExactly("cmd1");
    assertThat(player.getSession().messages())
        .anyMatch(m -> m.endsWith("] blue plays ArcherQueen on tick 220 (cmd0)"))
        .anyMatch(
            m -> m.endsWith("] blue taps ArcherQueen's ability on tick 350 (cmd1, unit cmd0_0)"));
    assertThat(player.getSession().getHalted()).isNull();
  }

  @Test
  @DisplayName("the notes name a side by its colour in the view, read as the frame is drawn")
  void theNotesNameTheSideByTheViewsColour() {
    ReplayPlayer player = player(fit(Replays.archerQueen()));
    while (player.step()) {
      // To the replay's end tick
    }
    // Side 0 played: drawn red at the top in the replay's flipped view, blue standing.
    assertThat(player.getSession().getBattle().getPlays().get(0).side()).isZero();
    BattleSession session = player.getSession();

    assertThat(BattleAdapter.frame(session, ViewOrientation.FLIPPED::sideName).messages())
        .anyMatch(m -> m.endsWith("] red plays ArcherQueen on tick 220 (cmd0)"))
        .anyMatch(
            m -> m.endsWith("] red taps ArcherQueen's ability on tick 350 (cmd1, unit cmd0_0)"))
        .noneMatch(m -> m.contains("blue"));
    assertThat(BattleAdapter.frame(session, ViewOrientation.STANDARD::sideName).messages())
        .anyMatch(m -> m.endsWith("] blue plays ArcherQueen on tick 220 (cmd0)"));
    // The battle is the replay's either way: the view names it, it does not change it.
    assertThat(session.getBattle().getPlays()).hasSize(1);
  }

  @Test
  @DisplayName(
      "the frame shows the replay's own levels: each hand card's, each tower's, the unit's")
  void theFrameShowsTheReplaysLevels() {
    ObjectNode document = fit(Replays.archerQueen());
    // Side 0's cards past the Archer Queen, which is played, each at a level of its own.
    ArrayNode deck = (ArrayNode) document.path("battle").path("deck0").path("sp");
    for (int i = 1; i < deck.size(); i++) {
      ((ObjectNode) deck.get(i)).put("l", i);
    }
    ReplayPlayer player = player(document);
    ScenarioPlan plan = player.getReplay().plan();

    BattleFrame frame = BattleAdapter.frame(player.getSession());

    for (BattleFrame.SideView side : frame.sides()) {
      List<String> names = plan.decks().get(side.side());
      int[] levels = plan.deckLevels().get(side.side());
      for (BattleFrame.CardView card : side.hand()) {
        assertThat(card.level())
            .as("side %d's %s", side.side(), card.name())
            .isEqualTo(levels[names.indexOf(card.name())]);
      }
      assertThat(side.next().level()).isEqualTo(levels[names.indexOf(side.next().name())]);
    }
    // Side 0's hand holds cards at four levels, never all at the Ladder level.
    assertThat(frame.sides().get(0).hand())
        .extracting(BattleFrame.CardView::level)
        .doesNotHaveDuplicates();
    List<EntityView> towers =
        frame.entities().stream().filter(e -> e.kind() == EntityView.Kind.TOWER).toList();
    assertThat(towers).hasSize(6);
    for (EntityView tower : towers) {
      Standard1v1Battle.Towers own = plan.towers().get(tower.side());
      assertThat(tower.level())
          .as("side %d's %s", tower.side(), tower.name())
          .isEqualTo(tower.king() ? own.kingLevel() : own.level());
    }

    while (player.getSession().getBattle().getPlays().isEmpty()) {
      player.step();
    }
    EntityView queen =
        BattleAdapter.frame(player.getSession()).entities().stream()
            .filter(e -> e.name().equals("ArcherQueen"))
            .findFirst()
            .orElseThrow();
    assertThat(queen.level()).isEqualTo(plan.plays().get(0).level());
  }

  @Test
  @DisplayName("the replay stops at its end tick and shows the battle's result beside its own")
  void stopsAtTheEndTick() {
    ReplayPlayer player = player(fit(Replays.archerQueen()));

    int steps = 0;
    while (player.step()) {
      steps++;
    }

    assertThat(steps).isEqualTo(400);
    assertThat(player.finished()).isTrue();
    assertThat(player.getSession().tick()).isEqualTo(400);
    assertThat(player.stopReason()).isEqualTo("the replay's end tick 400");
    // The battle's own crowns as they stand, the bottom side's first: side 1 in the flipped view.
    LadderMatch match = player.getSession().match();
    assertThat(match.isEnded()).isFalse();
    assertThat(player.statusLines(ViewOrientation.FLIPPED))
        .contains(
            "tick 400 / 400",
            "commands run 2 / 2",
            "stopped: the replay's end tick 400",
            "battle result: not decided on tick 400, crowns "
                + match.crowns(1)
                + " - "
                + match.crowns(0),
            "recorded result: none in the replay");
    assertThat(player.advance(1f)).isZero();
  }

  @Test
  @DisplayName("real time steps the battle at the chosen speed, and pause holds it")
  void clockSpeedAndPause() {
    ReplayPlayer player = player(fit(Replays.archerQueen()));

    assertThat(player.advance(STEP_SECONDS * 10.5f)).isEqualTo(10);
    player.faster();
    assertThat(player.getSpeed()).isEqualTo(2f);
    assertThat(player.advance(STEP_SECONDS * 5)).isEqualTo(10);
    player.togglePause();
    assertThat(player.advance(1f)).isZero();
    assertThat(player.getSession().tick()).isEqualTo(20);
    player.togglePause();
    for (int i = 0; i < 10; i++) {
      player.slower();
    }
    assertThat(player.getSpeed()).isEqualTo(ReplayPlayer.SPEED_MIN);
    for (int i = 0; i < 10; i++) {
      player.faster();
    }
    assertThat(player.getSpeed()).isEqualTo(ReplayPlayer.SPEED_MAX);
  }

  @Test
  @DisplayName("restarting builds the replay's battle anew at tick 0 and plays it the same way")
  void restart() {
    ReplayPlayer player = player(fit(Replays.archerQueen()));
    for (int i = 0; i < 300; i++) {
      player.step();
    }
    int units = player.getSession().getBattle().getPlays().get(0).units().size();
    player.togglePause();

    player.restart();

    assertThat(player.getSession().tick()).isZero();
    assertThat(player.isPaused()).isFalse();
    assertThat(player.getSession().getBattle().getPlays()).isEmpty();
    for (int i = 0; i < 300; i++) {
      player.step();
    }
    assertThat(player.getSession().getBattle().getPlays()).hasSize(1);
    assertThat(player.getSession().getBattle().getPlays().get(0).units()).hasSize(units);
  }

  @Test
  @DisplayName("a play that runs with an item other than the battle's halts the replay")
  void anItemOtherThanTheBattlesHalts() {
    ObjectNode document = fit(Replays.archerQueen());
    // Side 1's Archer in its deck's evolution slot, played on tick 230 with the count field 2
    // (bits 7..9), where the battle builds its first play with the count field 1: the slot flags
    // field 1 (bit 19) and the deck index field 2 (bits 22..27), its cost and level from its rows.
    ((ObjectNode) document.path("battle").path("deck1").path("sp").get(1)).put("el", 1);
    ObjectNode play = ((ArrayNode) document.path("cmd")).insertObject(1);
    play.put("ct", Replays.PLAY);
    ObjectNode body = play.putObject("c");
    body.put("t", 210).put("t2", 230).put("idHi", 0).put("idLo", 2);
    body.put("px", 3500).put("py", 18000).put("sid", -1);
    body.putObject("sel").put("os", 26000001).put("pd", (2 << 22) | (1 << 19) | (2 << 7));
    fit(document);
    int item = document.path("cmd").get(1).path("c").path("sel").path("pd").asInt();
    ReplayPlayer player = player(document);

    while (player.step()) {
      // Until it halts
    }

    // The play ran in the step of tick 230, after which 231 steps have completed.
    assertThat(player.getSession().getBattle().getPlays().get(1).tick()).isEqualTo(230);
    assertThat(player.getSession().tick()).isEqualTo(231);
    assertThat(player.getSession().getHalted())
        .startsWith("the battle left the replay, a play whose packed item is not")
        .contains("cmd[1].c.sel.pd=" + item);
    assertThat(player.stopReason()).startsWith("halted on tick 231");
    assertThat(player.statusLines(ViewOrientation.FLIPPED))
        .contains("recorded result: none in the replay");
  }

  @Test
  @DisplayName("a refused replay has no battle, and stepping it does nothing")
  void aRefusedReplayHasNoBattle() {
    ObjectNode document = fit(Replays.archerQueen());
    // The play's command type of another data version, 14.593.1.
    ((ObjectNode) document.path("cmd").get(0)).put("ct", 124);

    ReplayPlayer player = player(document);

    assertThat(player.getSession()).isNull();
    assertThat(player.finished()).isTrue();
    assertThat(player.step()).isFalse();
    assertThat(player.advance(1f)).isZero();
    assertThat(player.stopReason()).isEqualTo("the replay is refused");
    assertThat(player.statusLines(ViewOrientation.FLIPPED))
        .containsExactly("replay: replay.json", "refused: see the reasons");
  }
}
