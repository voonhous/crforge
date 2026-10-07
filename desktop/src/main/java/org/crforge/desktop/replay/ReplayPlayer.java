package org.crforge.desktop.replay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.match.LadderMatch;
import org.crforge.core.battle.replay.ReplayBattle;
import org.crforge.core.battle.replay.ScenarioPlan;
import org.crforge.core.battle.replay.UnsupportedScenarioException;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.desktop.battle.AreaHitLog;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.desktop.render.ViewOrientation;

/**
 * Plays a replay on the battle core, as the replay screen shows it, with no graphics: the battle
 * built from the replay ({@link ReplayBattle#build}) with every command queued at its tick, the
 * clock that steps it, pause and speed, and a restart from tick 0.
 *
 * <p>Each play and ability command that runs is noted in the session's messages (its side, named by
 * the screen's colours as the messages are read, its card or unit, and the tick it ran on), and
 * each play's item is checked against the item the battle built for it ({@link
 * ReplayBattle#checkItems}), as a conformance run checks it: a play that ran with another item
 * halts the session with the reason, since the battle has left the replay.
 *
 * <p>The replay stops at its end tick, or when the battle ends by its own rule, whichever is first;
 * a replay that gives no end tick plays until the battle ends. A refused replay has no session.
 */
public final class ReplayPlayer {

  /** The slowest speed, a multiple of real time. */
  public static final float SPEED_MIN = 0.25f;

  /** The fastest speed. */
  public static final float SPEED_MAX = 8f;

  /** Seconds of game time one battle step covers. */
  private static final float STEP_SECONDS = Battle.STEP_MS / 1000f;

  @Getter private final ReplayFile replay;

  private final GameTables tables;

  /** The session playing the replay, or null for a refused replay. */
  @Getter private BattleSession session;

  @Getter private boolean paused;

  @Getter private float speed = 1f;

  private float accumulator;

  /** How many of the battle's plays have been noted and checked. */
  private int playsRead;

  /** How many of the battle's ability commands have been noted. */
  private int abilitiesRead;

  /** The area hits of the steps run since the last drain. */
  private final List<AreaHitLog.AreaHit> areaHits = new ArrayList<>();

  /**
   * A player of a replay, at tick 0.
   *
   * @param replay the replay, refused or playable
   * @param tables the tables it was read against
   */
  public ReplayPlayer(ReplayFile replay, GameTables tables) {
    this.replay = replay;
    this.tables = tables;
    restart();
  }

  /** Builds the replay's battle anew at tick 0, unpaused; a refused replay stays without one. */
  public void restart() {
    playsRead = 0;
    abilitiesRead = 0;
    accumulator = 0f;
    paused = false;
    areaHits.clear();
    if (!replay.playable()) {
      session = null;
      return;
    }
    ScenarioPlan plan = replay.plan();
    Map<String, String> cards = new HashMap<>();
    plan.plays().forEach(play -> cards.put(name(play.index()), play.card()));
    session = BattleSession.of(ReplayBattle.build(tables, plan), cards, plan.deckLevels());
  }

  public void togglePause() {
    paused = !paused;
  }

  /** A manual step stays paused and discards any fractional playback time. */
  public boolean stepOnce() {
    paused = true;
    accumulator = 0f;
    return step();
  }

  /** Doubles the speed, up to {@link #SPEED_MAX}. */
  public void faster() {
    speed = Math.min(SPEED_MAX, speed * 2f);
  }

  /** Halves the speed, down to {@link #SPEED_MIN}. */
  public void slower() {
    speed = Math.max(SPEED_MIN, speed / 2f);
  }

  /**
   * Runs the steps that real time has covered since the last frame at the current speed, unless
   * paused or finished.
   *
   * @param deltaSeconds the real time since the last frame
   * @return how many steps ran
   */
  public int advance(float deltaSeconds) {
    if (paused || finished()) {
      return 0;
    }
    accumulator += deltaSeconds * speed;
    int steps = 0;
    while (accumulator >= STEP_SECONDS) {
      accumulator -= STEP_SECONDS;
      if (!step()) {
        accumulator = 0f;
        break;
      }
      steps++;
    }
    return steps;
  }

  /**
   * Runs one step, unless the replay has finished, then notes and checks the commands that ran.
   *
   * @return true when a step ran to its end
   */
  public boolean step() {
    if (finished()) {
      return false;
    }
    boolean stepped = session.step();
    areaHits.addAll(session.getAreaHits().drain());
    if (stepped) {
      readCommands();
    }
    return stepped;
  }

  /** Whether the replay has stopped: refused, at its end tick, the battle over, or halted. */
  public boolean finished() {
    return session == null || session.getHalted() != null || session.isOver() || atEndTick();
  }

  /** The area hits of the steps run since the last call. */
  public List<AreaHitLog.AreaHit> drainAreaHits() {
    List<AreaHitLog.AreaHit> drained = List.copyOf(areaHits);
    areaHits.clear();
    return drained;
  }

  /**
   * What the status column shows of the replay: the file, the client version and capture time its
   * capture block names, if it has one, the tick against the end tick, why it stopped once it has,
   * the battle's result and the replay's own, and the speed.
   *
   * @param view the screen's orientation, which names the sides in the battle's result
   */
  public List<String> statusLines(ViewOrientation view) {
    List<String> lines = new ArrayList<>();
    lines.add("replay: " + replay.name());
    replay
        .capture()
        .ifPresent(
            capture ->
                lines.add(
                    "recorded: client "
                        + capture.clientVersion()
                        + (capture.capturedAt() == null ? "" : ", " + capture.capturedAt())));
    if (session == null) {
      lines.add("refused: see the reasons");
      return lines;
    }
    int endTick = replay.header().endTick();
    lines.add("tick " + session.tick() + (endTick < 0 ? "" : " / " + endTick));
    lines.add(
        "commands run "
            + (playsRead + abilitiesRead)
            + " / "
            + (replay.plan().plays().size() + replay.plan().abilities().size()));
    lines.add("speed " + speed + "x" + (paused ? ", paused" : ""));
    String stop = stopReason();
    if (stop != null) {
      lines.add("stopped: " + stop);
    }
    if (finished()) {
      lines.add("battle result: " + battleResult(view));
      lines.add("recorded result: " + replay.recordedResult());
    }
    return lines;
  }

  /** Why the replay stopped, or null while it plays. */
  public String stopReason() {
    if (session == null) {
      return "the replay is refused";
    }
    if (session.getHalted() != null) {
      return "halted on tick " + session.tick() + ", " + session.getHalted();
    }
    if (session.isOver()) {
      return "the battle ended on tick " + session.tick();
    }
    if (atEndTick()) {
      return "the replay's end tick " + replay.header().endTick();
    }
    return null;
  }

  /**
   * The battle's result as it stands: the winner once the match has ended, and the crowns, the
   * bottom (blue) side's first.
   *
   * @param view the screen's orientation, which names the sides
   */
  public String battleResult(ViewOrientation view) {
    LadderMatch match = session.match();
    List<Integer> order = view.sidesBottomFirst();
    String crowns = "crowns " + match.crowns(order.get(0)) + " - " + match.crowns(order.get(1));
    if (!match.isEnded()) {
      return "not decided on tick " + session.tick() + ", " + crowns;
    }
    int winner = match.getWinner();
    return (winner < 0 ? "a draw" : view.sideName(winner) + " wins") + ", " + crowns;
  }

  private boolean atEndTick() {
    int endTick = replay.header().endTick();
    return endTick >= 0 && session.tick() >= endTick;
  }

  /**
   * Notes each play and ability command that ran in the last step and checks each play's item; a
   * play whose item is not the battle's halts the session.
   */
  private void readCommands() {
    Standard1v1Battle battle = session.getBattle();
    List<Standard1v1Battle.Play> plays = battle.getPlays();
    for (int i = playsRead; i < plays.size(); i++) {
      Standard1v1Battle.Play play = plays.get(i);
      boolean placed = play.matchCode() == 0 && play.result() != null && play.result().placed();
      if (placed) {
        session.note(
            play.side(),
            " plays " + card(play.name()) + " on tick " + play.tick() + " (" + play.name() + ")");
      }
    }
    try {
      playsRead = ReplayBattle.checkItems(battle, replay.plan(), playsRead);
    } catch (UnsupportedScenarioException e) {
      playsRead = plays.size();
      session.halt("the battle left the replay, " + e.getMessage());
    }
    List<Standard1v1Battle.AbilityUse> uses = battle.getAbilityUses();
    for (; abilitiesRead < uses.size(); abilitiesRead++) {
      Standard1v1Battle.AbilityUse use = uses.get(abilitiesRead);
      int code = use.outcome().code();
      // A unit is named after the play that made it: cmd0_0 is the first unit of cmd0.
      int cut = use.unit().indexOf('_');
      String unit = cut < 0 ? use.unit() : card(use.unit().substring(0, cut));
      session.note(
          use.side(),
          " taps "
              + unit
              + "'s ability on tick "
              + use.tick()
              + " ("
              + use.name()
              + ", unit "
              + use.unit()
              + ")"
              + (code == 0 ? "" : ", refused with code 0x" + Integer.toHexString(code)));
    }
  }

  /** The card a play was given for, by the play's name. */
  private String card(String playName) {
    return replay.plan().plays().stream()
        .filter(play -> name(play.index()).equals(playName))
        .map(ScenarioPlan.Play::card)
        .findFirst()
        .orElse(playName);
  }

  /** A command's play name, as {@link ReplayBattle#build} names it. */
  private static String name(int index) {
    return "cmd" + index;
  }
}
