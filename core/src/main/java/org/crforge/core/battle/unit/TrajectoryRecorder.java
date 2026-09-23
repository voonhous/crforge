package org.crforge.core.battle.unit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.target.TargetView;

/**
 * Records one character's run through a battle in the layout of the reference trajectories, so that
 * a run the engine plays can be compared with a reference tick for tick, or become a fixture.
 *
 * <p>Attached to the battle's world as an observer before the first step, it writes one record per
 * tick the character spends in the holder - its position, state, reference, route length, movement
 * budget and the reference's remaining hit points - one event per hit landed in the battle, with
 * the damage dealt and what the target stands at afterwards, and one per death. The header carries
 * the deployment, the level, the damage of one of the character's hits, and the towers standing
 * when the recorder first saw the battle, with their starting hit points.
 *
 * <p>Ticks are counted from the character's first tick in the holder, as the reference counts them,
 * so a placement on a later tick records the same run. Two more conventions of the reference are
 * kept. A deploying tick is recorded as the character stood before its state visit: deploying,
 * without a reference, a budget or hit points, so the last deploying tick is written as deploying
 * although the visit that ends the deployment runs in the same tick; every other tick is recorded
 * after the state visit. And the budget is what the movement visit asked for while the character
 * walks, and zero otherwise.
 *
 * <p>The text is laid out as the committed fixtures are: one compact line per tower, event and
 * record, so that two runs diff by the values that moved.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Nothing here is a rule of the game: the recorder reads the battle and writes what the"
            + " reference format asks for. Supplied: ticks counted from the character's first tick"
            + " in the holder, a deploying tick recorded before the state visit, every hit in the"
            + " battle recorded whoever landed it, and a reference's hit points read from what it"
            + " advertises to attackers.")
public final class TrajectoryRecorder implements WorldObserver {

  /** The columns of a record, in the order a record lists them. */
  public static final List<String> FIELDS =
      List.of("tick", "x", "y", "state", "ref", "route", "speed", "hp");

  private final CharacterEntity unit;
  private final int deployX;
  private final int deployY;

  /** The towers standing when the recorder first saw the battle, in ascending id. */
  private final List<String> towers = new ArrayList<>();

  private final List<String> events = new ArrayList<>();
  private final List<String> records = new ArrayList<>();

  /** The battle tick of the character's first tick in the holder, or -1 before it. */
  private int firstTick = -1;

  /** True when the character was deploying at the head of the tick in progress. */
  private boolean deployingAtHead;

  /**
   * Prepares to record a character's run. Create the recorder before the battle's first step, while
   * the character stands at its deploy position, and attach it with {@link
   * BattleWorld#addObserver}.
   *
   * @param unit the character to record
   */
  public TrajectoryRecorder(CharacterEntity unit) {
    this.unit = unit;
    this.deployX = unit.getView().getX();
    this.deployY = unit.getView().getY();
  }

  @Override
  public void afterPrePass(int tick, List<WorldEntity> present) {
    if (towers.isEmpty()) {
      for (WorldEntity entity : present) {
        if (entity instanceof TowerEntity tower) {
          towers.add(towerLine(tower));
        }
      }
    }
    if (firstTick < 0 && present.contains(unit)) {
      firstTick = tick;
    }
    deployingAtHead = unit.getView().getState() == GridEntityState.DEPLOYING;
  }

  @Override
  public void damageDealt(int tick, WorldEntity target, int damage, DamageResult result) {
    if (firstTick < 0 || !result.landed()) {
      return;
    }
    String head = "  {\"tick\": " + (tick - firstTick) + ", \"event\": ";
    String targetName = quote(target.name());
    events.add(
        head
            + "\"hit\", \"target\": "
            + targetName
            + ", \"damage\": "
            + damage
            + ", \"hp\": "
            + target.getTargetView().getHitPoints()
            + "}");
    if (result.died()) {
      events.add(head + "\"death\", \"target\": " + targetName + "}");
    }
  }

  @Override
  public void afterPostHooks(int tick, List<WorldEntity> present) {
    if (firstTick < 0 || !present.contains(unit)) {
      return;
    }
    int x = unit.getView().getX();
    int y = unit.getView().getY();
    if (deployingAtHead) {
      records.add(record(tick - firstTick, x, y, GridEntityState.DEPLOYING, null, 0, null, null));
      return;
    }
    int state = unit.getView().getState();
    TargetView reference = unit.getUnit().targeting().getReference();
    records.add(
        record(
            tick - firstTick,
            x,
            y,
            state,
            reference == null ? null : reference.name(),
            unit.getUnit().movement().getRoute().size(),
            state == GridEntityState.MOVING ? unit.getSpeedBudget() : 0,
            reference == null ? null : reference.getHitPoints()));
  }

  /** The number of records written so far. */
  public int recordCount() {
    return records.size();
  }

  /** The run so far, laid out as the reference trajectories are. */
  public String text() {
    StringBuilder out = new StringBuilder();
    out.append("{\n");
    out.append(" \"card\": ").append(quote(unit.getData().name())).append(",\n");
    out.append(" \"deploy\": [").append(deployX).append(", ").append(deployY).append("],\n");
    out.append(" \"side\": ").append(unit.side()).append(",\n");
    out.append(" \"lane\": ").append(unit.getView().getLane()).append(",\n");
    out.append(" \"level\": ").append(unit.level()).append(",\n");
    out.append(" \"damage\": ").append(unit.getDamage()).append(",\n");
    appendList(out, "towers", towers).append(",\n");
    appendList(out, "events", events).append(",\n");
    out.append(" \"fields\": [");
    for (int i = 0; i < FIELDS.size(); i++) {
      out.append(i == 0 ? "" : ", ").append(quote(FIELDS.get(i)));
    }
    out.append("],\n");
    appendList(out, "records", records).append("\n");
    out.append("}\n");
    return out.toString();
  }

  /** Writes the run so far to a file, replacing what is there. */
  public void writeTo(Path file) throws IOException {
    Files.writeString(file, text(), StandardCharsets.UTF_8);
  }

  /** Appends a key whose value lists its lines one per line, up to the closing bracket. */
  private static StringBuilder appendList(StringBuilder out, String key, List<String> lines) {
    return out.append(" ")
        .append(quote(key))
        .append(": [\n")
        .append(String.join(",\n", lines))
        .append("\n ]");
  }

  private static String towerLine(TowerEntity tower) {
    return "  {\"name\": "
        + quote(tower.name())
        + ", \"x\": "
        + tower.getView().getX()
        + ", \"y\": "
        + tower.getView().getY()
        + ", \"side\": "
        + tower.side()
        + ", \"hp\": "
        + (tower.getHitPoints() == null ? 0 : tower.getHitPoints().getMaximum())
        + "}";
  }

  private static String record(
      int tick, int x, int y, int state, String ref, int route, Integer speed, Integer hp) {
    return "  ["
        + tick
        + ", "
        + x
        + ", "
        + y
        + ", "
        + state
        + ", "
        + (ref == null ? "null" : quote(ref))
        + ", "
        + route
        + ", "
        + (speed == null ? "null" : speed)
        + ", "
        + (hp == null ? "null" : hp)
        + "]";
  }

  /** The text as a JSON string, with the two characters that need escaping escaped. */
  private static String quote(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
