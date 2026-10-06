package org.crforge.desktop.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.desktop.render.ViewOrientation;
import org.crforge.desktop.render.ViewState;
import org.crforge.parity.CommandTypes;
import org.crforge.parity.ReplayCapture;
import org.crforge.parity.ReplayScenario;
import org.crforge.parity.ReplaySmokeRun;
import org.crforge.parity.ScenarioPlan;

/**
 * A replay file read for the viewer: its battle header, what the replay mapping ({@link
 * ReplayScenario}) refused of it, and, when nothing was refused, its translation, which {@link
 * ReplayPlayer} builds the battle from.
 *
 * <p>The replay is read against the configured game tables, whose data version also names the
 * command type numbers the commands are read by ({@link CommandTypes}). A replay is refused, never
 * played in part, when any of these refuses it, and every reason is listed:
 *
 * <ul>
 *   <li>each field, pinned value and command the mapping refuses ({@link ReplayScenario#survey}),
 *       the same refusals collapsed into one line with their count;
 *   <li>the tables, when the battle core refuses to build a battle on them;
 *   <li>the replay's battle, when the battle core refuses to set it up.
 * </ul>
 *
 * <p>The header is read leniently, naming each id by its row in the tables where it can, so that a
 * refused replay can still be described.
 *
 * <p>A replay may carry a capture block ({@link ReplayCapture}), written by the tool that saved it:
 * the client version, data version and content sha it was recorded on, and when. Its description
 * names them, and whether the tables' data version is the one the block names, or only assumed for
 * a replay without a block. A block that names other data than the tables' is one of the mapping's
 * refusals; whoever chose the tables may list why it could not find the replay's own first.
 */
public final class ReplayFile {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The replay's own result: neither the replay files nor the scenarios hold one. */
  private static final String NO_RECORDED_RESULT = "none in the replay";

  /** The most characters of a refused input a line shows. */
  private static final int INPUT_SHOWN = 160;

  private final Path path;
  private final String dataVersion;
  private final String contentSha;
  private final ReplayCapture capture;
  private final Header header;
  private final List<String> refusals;
  private final ScenarioPlan plan;

  /**
   * The battle header of a replay, read leniently.
   *
   * @param gameMode the game mode row's name, or its id when the tables do not hold it
   * @param location the location row's name, or its id
   * @param decks each side's deck, by card row name or id, in the replay's order
   * @param endTick the replay's last tick, or -1 when it gives none
   * @param commands how many commands it holds
   * @param commandTypes how many commands of each type, in the order the types first appear
   */
  public record Header(
      String gameMode,
      String location,
      List<List<String>> decks,
      int endTick,
      int commands,
      Map<Integer, Integer> commandTypes) {}

  private ReplayFile(
      Path path,
      GameTables tables,
      ReplayCapture capture,
      Header header,
      List<String> refusals,
      ScenarioPlan plan) {
    this.path = path;
    this.dataVersion = tables.version();
    this.contentSha = tables.contentSha();
    this.capture = capture;
    this.header = header;
    this.refusals = List.copyOf(refusals);
    this.plan = plan;
  }

  /**
   * Reads a replay file.
   *
   * @param path the file
   * @param tables the game tables it is read against
   * @return the replay, refused or playable
   * @throws IOException when the file cannot be read or is not JSON
   */
  public static ReplayFile read(Path path, GameTables tables) throws IOException {
    return parse(path, MAPPER.readTree(Files.readAllBytes(path)), tables);
  }

  /**
   * Reads a replay document.
   *
   * @param path where it came from, for presentation
   * @param document the replay document
   * @param tables the game tables it is read against
   * @return the replay, refused or playable
   */
  public static ReplayFile parse(Path path, JsonNode document, GameTables tables) {
    return parse(path, document, tables, null);
  }

  /**
   * Reads a replay document, with the reason its tables are not the ones it names, if there is one.
   *
   * @param path where it came from, for presentation
   * @param document the replay document
   * @param tables the game tables it is read against
   * @param tablesRefusal why the replay's own data could not be read on, listed as its first
   *     reason; null when there is none
   * @return the replay, refused or playable
   */
  public static ReplayFile parse(
      Path path, JsonNode document, GameTables tables, String tablesRefusal) {
    ReplayScenario mapping = new ReplayScenario(tables);
    Header header = header(document, mapping);
    List<String> refusals = new ArrayList<>();
    if (tablesRefusal != null) {
      refusals.add(tablesRefusal);
    }
    refusals.addAll(collapse(mapping.survey(document)));
    ScenarioPlan plan = null;
    if (refusals.isEmpty()) {
      plan = new ReplayScenario(tables).translate(document);
      // The battle is built once here, so a refusal to set it up is listed before any window.
      try {
        ReplaySmokeRun.build(tables, plan);
      } catch (RuntimeException e) {
        refusals.add("the battle core refuses to set up the replay's battle: " + reason(e));
        plan = null;
      }
    } else {
      // The tables are checked on their own, so their refusal is listed with the mapping's.
      try {
        new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL);
      } catch (RuntimeException e) {
        refusals.add(
            "the battle core refuses the game tables of data version "
                + tables.version()
                + ": "
                + reason(e));
      }
    }
    return new ReplayFile(
        path, tables, ReplayCapture.of(document).orElse(null), header, refusals, plan);
  }

  /** Where the replay came from. */
  public Path path() {
    return path;
  }

  /** The data version of the tables the replay was read against. */
  public String dataVersion() {
    return dataVersion;
  }

  /** The replay's capture block, or empty when it has none. */
  public Optional<ReplayCapture> capture() {
    return Optional.ofNullable(capture);
  }

  /**
   * Whether the data version is the one the replay names: its capture block's content sha is the
   * tables'. Otherwise the data version is assumed (no block) or not the replay's (refused).
   */
  public boolean dataNamed() {
    return capture != null && capture.contentSha().equals(contentSha);
  }

  /** What the replay was recorded on, as its capture block names it, with when. */
  private String recordedOn() {
    if (capture == null) {
      return "not named by the replay (no capture block)";
    }
    return capture.recordedOn()
        + (capture.capturedAt() == null ? "" : ", captured " + capture.capturedAt());
  }

  /** The tables' data version and how it relates to the replay's. */
  private String dataVersionLine() {
    String tables = dataVersion + " (content sha " + contentSha + "), ";
    if (capture == null) {
      return tables + "assumed: the replay does not name the data it was recorded on";
    }
    return tables + (dataNamed() ? "named by the replay" : "not the replay's");
  }

  /**
   * The line the viewer's data details give the replay: whether its capture block named the data,
   * with the client version and capture time, or the data version is assumed.
   */
  public String dataLine() {
    if (capture == null) {
      return "Replay: data version assumed, the replay does not name the data it was recorded on";
    }
    if (!dataNamed()) {
      return "Replay: recorded on other data, " + capture.recordedOn();
    }
    return "Replay: data named by its capture block (client "
        + capture.clientVersion()
        + (capture.capturedAt() == null ? "" : ", captured " + capture.capturedAt())
        + ")";
  }

  /** The replay's battle header. */
  public Header header() {
    return header;
  }

  /** Why the replay cannot be played, one reason a line; empty when it can be. */
  public List<String> refusals() {
    return refusals;
  }

  /** Whether the replay can be played: nothing refused it. */
  public boolean playable() {
    return plan != null;
  }

  /** The replay translated into the battle's inputs, or null for a refused replay. */
  public ScenarioPlan plan() {
    return plan;
  }

  /** The replay's own result, as the replay records it. */
  public String recordedResult() {
    return NO_RECORDED_RESULT;
  }

  /**
   * The lines the viewer prints at startup and shows for a refused replay, each deck labelled with
   * its side and its colour and place in the viewer's default orientation.
   */
  public List<String> describe() {
    return describe(ViewState.replay().getOrientation());
  }

  /**
   * The lines the viewer prints at startup and shows for a refused replay.
   *
   * @param view the orientation whose colours and places label the decks
   */
  public List<String> describe(ViewOrientation view) {
    List<String> lines = new ArrayList<>();
    lines.add("replay: " + path.toAbsolutePath().normalize());
    lines.add("  recorded on: " + recordedOn());
    lines.add("  data version: " + dataVersionLine());
    lines.add("  game mode: " + header.gameMode() + ", location " + header.location());
    for (int side = 0; side < header.decks().size(); side++) {
      lines.add(
          "  side "
              + side
              + " deck ("
              + view.sideName(side)
              + ", "
              + (view.atTop(side) ? "top" : "bottom")
              + "): "
              + String.join(", ", header.decks().get(side)));
    }
    lines.add(
        "  end tick: "
            + (header.endTick() < 0
                ? "none given, played until the battle ends"
                : String.valueOf(header.endTick())));
    lines.add("  commands: " + header.commands() + " (" + commandTypes() + ")");
    lines.add("  recorded result: " + recordedResult());
    if (refusals.isEmpty()) {
      lines.add(
          "  mapping: every field read; "
              + plan.plays().size()
              + " plays, "
              + plan.abilities().size()
              + " ability commands");
    } else {
      lines.add(
          "  refused, " + refusals.size() + (refusals.size() == 1 ? " reason:" : " reasons:"));
      for (String refusal : refusals) {
        lines.add("    - " + refusal);
      }
    }
    return lines;
  }

  /** The command types and their counts, each named by the data version's command types. */
  private String commandTypes() {
    Optional<CommandTypes> types = CommandTypes.of(dataVersion);
    List<String> parts = new ArrayList<>();
    header
        .commandTypes()
        .forEach(
            (type, count) -> {
              String what = types.map(t -> t.describe(type)).orElse(null);
              parts.add(
                  "type "
                      + type
                      + " x"
                      + count
                      + (what == null
                          ? ", not mapped in data version " + dataVersion
                          : ", " + what));
            });
    return parts.isEmpty() ? "none" : String.join("; ", parts);
  }

  /** Reads the header, naming each id by its row where the tables hold it. */
  private static Header header(JsonNode document, ReplayScenario mapping) {
    JsonNode battle = document.path("battle");
    List<List<String>> decks = new ArrayList<>();
    for (int side = 0; side < 2; side++) {
      List<String> names = new ArrayList<>();
      for (JsonNode entry : battle.path("deck" + side).path("sp")) {
        names.add(name(entry.path("d"), mapping));
      }
      decks.add(List.copyOf(names));
    }
    Map<Integer, Integer> types = new LinkedHashMap<>();
    int commands = 0;
    for (JsonNode command : document.path("cmd")) {
      types.merge(command.path("ct").asInt(-1), 1, Integer::sum);
      commands++;
    }
    return new Header(
        name(battle.path("gamemode"), mapping),
        name(battle.path("location"), mapping),
        List.copyOf(decks),
        document.path("endTick").asInt(-1),
        commands,
        types);
  }

  /** An id's row name, else the id, else "?" for a missing field. */
  private static String name(JsonNode id, ReplayScenario mapping) {
    if (!id.isInt()) {
      return "?";
    }
    return mapping.find(id.asInt()).map(GameRow::name).orElse("#" + id.asInt());
  }

  /**
   * The survey's refusals as lines, one per feature: the same feature met on several inputs, as
   * every command of one unmapped type, is one line with the count and the first input.
   */
  private static List<String> collapse(List<ReplayScenario.Refusal> refusals) {
    Map<String, List<String>> byFeature = new LinkedHashMap<>();
    for (ReplayScenario.Refusal refusal : refusals) {
      byFeature.computeIfAbsent(refusal.feature(), f -> new ArrayList<>()).add(refusal.input());
    }
    List<String> lines = new ArrayList<>();
    byFeature.forEach(
        (feature, inputs) ->
            lines.add(
                feature
                    + ": "
                    + shorten(inputs.get(0))
                    + (inputs.size() == 1
                        ? ""
                        : " and " + (inputs.size() - 1) + " more (" + inputs.size() + " in all)")));
    return lines;
  }

  /** An input as a line shows it: a long one, such as a whole player's data, cut short. */
  private static String shorten(String input) {
    return input.length() <= INPUT_SHOWN ? input : input.substring(0, INPUT_SHOWN) + "...";
  }

  private static String reason(RuntimeException e) {
    return e.getClass().getSimpleName() + ": " + e.getMessage();
  }
}
