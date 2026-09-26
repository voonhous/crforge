package org.crforge.core.battle.unit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.AreaDamage;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.target.RangeTest;
import org.crforge.core.pathfinding.target.TargetView;

/**
 * Records one character's run through a battle in the layout of the reference trajectories, so that
 * a run the engine plays can be compared with a reference tick for tick, or become a fixture.
 *
 * <p>Attached to the battle's world as an observer before the first step, it writes one record per
 * tick the character spends in the holder - its position, state, reference, route length, movement
 * budget and the reference's remaining hit points - one event per hit landed in the battle, with
 * the damage dealt and what the target stands at afterwards, one per projectile launched, with its
 * start and aim, one per projectile arrived, with the damage dealt and what the target stands at
 * afterwards, and one per death. A run with projectiles also lists every projectile's position
 * after each of its flight steps, which a run without has no line for. The header carries the
 * deployment, the level, the damage of one of the character's hits, and the towers standing when
 * the recorder first saw the battle, with their starting hit points.
 *
 * <p>Ticks are counted from the character's first tick in the holder, as the reference counts them,
 * so a placement on a later tick records the same run. Two more conventions of the reference are
 * kept. A deploying tick is recorded as the character stood before its state visit: deploying,
 * without a reference, a budget or hit points, so the last deploying tick is written as deploying
 * although the visit that ends the deployment runs in the same tick; every other tick is recorded
 * after the state visit. And the budget is what the movement visit asked for while the character
 * walks, and zero otherwise.
 *
 * <p>A run in which the towers fight carries three things more. The header gives the towers' level
 * and says that they fight, every record ends with the character's own hit points, and a list of
 * the towers' own events follows the events: each time a tower's reference changes, with whether
 * the new one is in range, each time a tower locks on, and, inside the closing cleanup that removes
 * an entity, what the removal left each tower holding, then the removal itself. The towers' events
 * go on after the character has left, for as long as the battle is played. The steps of a king
 * tower's activation are among them, written after the tick's visits, as they happen after them.
 *
 * <p>The text is laid out as the committed fixtures are: one compact line per tower, event and
 * record, so that two runs diff by the values that moved.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Nothing here is a rule of the game: the recorder reads the battle and writes what the"
            + " reference format asks for. Supplied: ticks counted from the character's first tick"
            + " in the holder, a deploying tick recorded before the state visit, every hit,"
            + " launch and impact in the battle recorded whoever made it, a projectile's position"
            + " recorded after its step unless that step arrived, a reference's hit points"
            + " read from what it advertises to attackers, and a tower's reference and lock read"
            + " after the tick's post-hooks, when the target has already moved.")
public final class TrajectoryRecorder implements WorldObserver {

  /** The columns of a record, in the order a record lists them. */
  public static final List<String> FIELDS =
      List.of("tick", "x", "y", "state", "ref", "route", "speed", "hp");

  /** The columns of a record of every other character the recorder follows. */
  public static final List<String> UNIT_FIELDS =
      List.of("tick", "x", "y", "state", "ref", "own_hp");

  /** The columns of a record in a run whose towers fight: the character's own hit points last. */
  public static final List<String> FIGHTING_FIELDS =
      List.of("tick", "x", "y", "state", "ref", "route", "speed", "hp", "own_hp");

  private final CharacterEntity unit;

  /** The other characters the recorder follows, in the order they were given. */
  private final List<CharacterEntity> others;

  /** Each other character's header line, once it has been seen in the holder. */
  private final Map<CharacterEntity, String> otherHeaders = new HashMap<>();

  /** Each other character's records, from the tick it was first seen in the holder. */
  private final Map<CharacterEntity, List<String>> otherRecords = new HashMap<>();

  private final int deployX;
  private final int deployY;

  /** The towers standing when the recorder first saw the battle, in ascending id. */
  private final List<String> towers = new ArrayList<>();

  private final List<String> events = new ArrayList<>();
  private final List<String> records = new ArrayList<>();

  /** One line per projectile position: the tick, the projectile's id and where it stands. */
  private final List<String> projectiles = new ArrayList<>();

  /** True when the recorder first saw a tower that fights; the towers' level is then recorded. */
  private boolean towersAttack;

  private int towerLevel;

  /** The towers that fight and are still standing, in ascending id. */
  private final List<TowerEntity> fightingTowers = new ArrayList<>();

  /** The name of each fighting tower's reference as last recorded; absent until it holds one. */
  private final Map<TowerEntity, String> towerReferences = new HashMap<>();

  /** The fighting towers that have locked on and not yet lost their reference. */
  private final Set<TowerEntity> lockedTowers = new HashSet<>();

  /** Each fighting tower's state as last recorded. */
  private final Map<TowerEntity, Integer> towerStates = new HashMap<>();

  private final List<String> towerEvents = new ArrayList<>();

  /**
   * The activation steps of the tick in progress. They happen in the run pass and a pending pass,
   * after the towers' visits, so they are written after the visits' events at the tick's end.
   */
  private final List<String> activationEvents = new ArrayList<>();

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
    this(unit, List.of());
  }

  /**
   * Prepares to record a character's run and, beside it, the positions, states, references and hit
   * points of other characters of the same battle, each from its own first tick in the holder.
   *
   * @param unit the character to record
   * @param others the other characters to follow
   */
  public TrajectoryRecorder(CharacterEntity unit, List<CharacterEntity> others) {
    this.unit = unit;
    this.others = List.copyOf(others);
    this.deployX = unit.getView().getX();
    this.deployY = unit.getView().getY();
  }

  @Override
  public void afterPrePass(int tick, List<WorldEntity> present) {
    if (towers.isEmpty()) {
      for (WorldEntity entity : present) {
        if (entity instanceof TowerEntity tower) {
          towers.add(towerLine(tower));
          if (!tower.isHoldingFire()) {
            towersAttack = true;
            towerLevel = tower.level();
            fightingTowers.add(tower);
          }
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
    String head = eventHead(tick);
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
  public void projectileLaunched(int tick, ProjectileEntity projectile) {
    if (firstTick < 0) {
      return;
    }
    WorldEntity owner = projectile.getOwner();
    WorldEntity target = projectile.getTarget();
    events.add(
        eventHead(tick)
            + "\"launch\", \"projectile\": "
            + quote(projectile.name())
            + ", \"config\": "
            + quote(projectile.getData().name())
            + ", \"owner\": "
            + (owner == null ? "null" : quote(owner.name()))
            + ", \"target\": "
            + (target == null ? "null" : quote(target.name()))
            + ", \"x\": "
            + projectile.getX()
            + ", \"y\": "
            + projectile.getY()
            + ", \"z\": "
            + projectile.getZ()
            + ", \"aim\": ["
            + projectile.getAimX()
            + ", "
            + projectile.getAimY()
            + "], \"aim_z\": "
            + projectile.getAimZ()
            + "}");
  }

  @Override
  public void projectileImpacted(
      int tick, ProjectileEntity projectile, WorldEntity target, int damage, DamageResult result) {
    if (firstTick < 0) {
      return;
    }
    String head = eventHead(tick);
    String targetName = quote(target.name());
    events.add(
        head
            + "\"impact\", \"projectile\": "
            + quote(projectile.name())
            + ", \"target\": "
            + targetName
            + ", \"damage\": "
            + damage
            + ", \"hp\": "
            + target.getTargetView().getHitPoints()
            + ", \"x\": "
            + projectile.getX()
            + ", \"y\": "
            + projectile.getY()
            + ", \"z\": "
            + projectile.getZ()
            + "}");
    if (result.died()) {
      events.add(head + "\"death\", \"target\": " + targetName + "}");
    }
  }

  @Override
  public void afterPostHooks(int tick, List<WorldEntity> present, List<ProjectileEntity> inFlight) {
    if (firstTick < 0) {
      return;
    }
    for (TowerEntity tower : fightingTowers) {
      recordTowerVisit(tick, tower);
    }
    towerEvents.addAll(activationEvents);
    activationEvents.clear();
    for (CharacterEntity other : others) {
      if (present.contains(other)) {
        recordOther(tick, other);
      }
    }
    // A projectile is recorded wherever it is in flight, even after the unit it came from has left.
    for (ProjectileEntity projectile : inFlight) {
      if (!projectile.isReleased()) {
        projectiles.add(
            "  ["
                + (tick - firstTick)
                + ", "
                + projectile.getId()
                + ", "
                + projectile.getX()
                + ", "
                + projectile.getY()
                + ", "
                + projectile.getZ()
                + "]");
      }
    }
    if (!present.contains(unit)) {
      return;
    }
    int x = unit.getView().getX();
    int y = unit.getView().getY();
    Integer ownHitPoints = towersAttack ? unit.getHitPoints().getHitPoints() : null;
    if (deployingAtHead) {
      records.add(
          record(
              tick - firstTick,
              x,
              y,
              GridEntityState.DEPLOYING,
              null,
              0,
              null,
              null,
              ownHitPoints));
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
            reference == null ? null : reference.getHitPoints(),
            ownHitPoints));
  }

  /**
   * What a tower's visit did this tick: a change of reference, with whether the new one is in
   * range, then a lock, which is the tower entering the attacking state; or, when the visit gave
   * the reference up after a lock, the drop. A tower still attacking after its reference was
   * removed has not locked again.
   */
  private void recordTowerVisit(int tick, TowerEntity tower) {
    TargetView reference = tower.getTargeting().getReference();
    String name = reference == null ? null : reference.name();
    if (!Objects.equals(name, towerReferences.get(tower))) {
      towerReferences.put(tower, name);
      String inRange =
          reference == null
              ? "null"
              : RangeTest.referenceInRange(tower.getTargeting(), reference, 0) ? "1" : "0";
      towerEvents.add(
          towerEventHead(tick, "reference", tower)
              + ", \"target\": "
              + nameOrNull(name)
              + ", \"in_range\": "
              + inRange
              + "}");
    }
    int state = tower.getView().getState();
    Integer before = towerStates.put(tower, state);
    boolean entered = before == null || before != GridEntityState.ATTACKING;
    if (state == GridEntityState.ATTACKING && entered && lockedTowers.add(tower)) {
      towerEvents.add(
          towerEventHead(tick, "lock", tower) + ", \"target\": " + nameOrNull(name) + "}");
    }
    if (reference == null && lockedTowers.remove(tower)) {
      towerEvents.add(towerEventHead(tick, "reference_dropped", tower) + "}");
    }
  }

  /**
   * What the removal of an entity left each fighting tower holding: a changed reference, with the
   * target-lost countdown the removal started, and the drop of a reference a tower had locked on;
   * then the removal itself.
   */
  @Override
  public void entityRemoved(int tick, WorldEntity removed) {
    if (firstTick < 0 || !towersAttack) {
      return;
    }
    fightingTowers.remove(removed);
    String removedName = quote(removed.name());
    for (TowerEntity tower : fightingTowers) {
      TargetView reference = tower.getTargeting().getReference();
      String name = reference == null ? null : reference.name();
      if (!Objects.equals(name, towerReferences.get(tower))) {
        towerReferences.put(tower, name);
        towerEvents.add(
            towerEventHead(tick, "reference", tower)
                + ", \"target\": "
                + nameOrNull(name)
                + ", \"removed\": "
                + removedName
                + ", \"target_lost_timer\": "
                + tower.getTargeting().getTargetLostTimerMs()
                + "}");
      }
      if (reference == null && lockedTowers.remove(tower)) {
        towerEvents.add(
            towerEventHead(tick, "reference_dropped", tower)
                + ", \"removed\": "
                + removedName
                + "}");
      }
    }
    towerEvents.add(
        "  {\"tick\": "
            + (tick - firstTick)
            + ", \"event\": \"removed\", \"entity\": "
            + removedName
            + "}");
  }

  /**
   * One tick of another character, after its state visit: where it stands, its state, its reference
   * and its own hit points. Its header is written the first time it is seen.
   */
  private void recordOther(int tick, CharacterEntity other) {
    otherHeaders.computeIfAbsent(
        other,
        o ->
            "  {\"name\": "
                + quote(o.name())
                + ", \"card\": "
                + quote(o.getData().name())
                + ", \"side\": "
                + o.side()
                + ", \"deploy\": ["
                + o.getView().getX()
                + ", "
                + o.getView().getY()
                + "], \"tick\": "
                + (tick - firstTick)
                + ", \"id\": "
                + o.getId()
                + ", \"lane\": "
                + o.getView().getLane()
                + ", \"hp\": "
                + (o.getHitPoints() == null ? 0 : o.getHitPoints().getMaximum())
                + "}");
    TargetView reference = other.getTargeting().getReference();
    otherRecords
        .computeIfAbsent(other, o -> new ArrayList<>())
        .add(
            "   ["
                + (tick - firstTick)
                + ", "
                + other.getView().getX()
                + ", "
                + other.getView().getY()
                + ", "
                + other.getView().getState()
                + ", "
                + (reference == null ? "null" : quote(reference.name()))
                + ", "
                + (other.getHitPoints() == null ? "null" : other.getHitPoints().getHitPoints())
                + "]");
  }

  /** One victim of the area of a character's hit. */
  @Override
  public void areaHit(
      int tick,
      WorldEntity attacker,
      WorldEntity victim,
      int damage,
      int hitId,
      DamageResult result) {
    if (firstTick < 0) {
      return;
    }
    String head = eventHead(tick);
    events.add(
        head
            + "\"area_hit\", \"attacker\": "
            + quote(attacker.name())
            + ", \"target\": "
            + quote(victim.name())
            + ", \"damage\": "
            + damage
            + ", \"hp\": "
            + victim.getTargetView().getHitPoints()
            + ", \"hit_id\": "
            + hitId
            + "}");
    if (result.died()) {
      events.add(head + "\"death\", \"target\": " + quote(victim.name()) + "}");
    }
  }

  /** The area of a character's hit: who stood in it, whom it accepted and whom it damaged. */
  @Override
  public void areaDamaged(
      int tick, WorldEntity owner, AreaDamage.Area area, AreaDamage.Outcome outcome) {
    if (firstTick < 0) {
      return;
    }
    events.add(
        eventHead(tick)
            + "\"area\", \"owner\": "
            + quote(owner.name())
            + ", \"centre\": ["
            + area.x()
            + ", "
            + area.y()
            + "], \"radius\": "
            + area.radius()
            + ", \"damage\": "
            + area.damage()
            + ", \"tower_damage\": "
            + area.towerDamage()
            + ", \"hit_id\": "
            + area.hitId()
            // No row of the data pushes with a hit, so the area pushes nothing.
            + ", \"push\": 0, \"push_flag\": 0, \"in_circle\": "
            + names(outcome.inCircle())
            + ", \"validated\": "
            + names(outcome.validated())
            + ", \"victims\": "
            + names(outcome.damaged())
            + ", \"pushed\": []}");
  }

  /** The names of a list of entities as a JSON list. */
  private static String names(List<TargetView> views) {
    StringBuilder out = new StringBuilder("[");
    for (int i = 0; i < views.size(); i++) {
      out.append(i == 0 ? "" : ", ").append(quote(views.get(i).name()));
    }
    return out.append("]").toString();
  }

  /** A step of a king tower's activation, held until the tick's visits have been written. */
  @Override
  public void activation(int tick, TowerEntity king, ActivationEvent event) {
    if (firstTick < 0 || !towersAttack) {
      return;
    }
    String line =
        switch (event.kind()) {
          case CONDITION ->
              towerEventHead(tick, "activation_condition", king)
                  + ", \"damaged\": "
                  + (event.damaged() ? 1 : 0)
                  + ", \"tower_destroyed\": "
                  + (event.towerDestroyed() ? 1 : 0);
          case ACTIVATING_STARTED ->
              towerEventHead(tick, "activating_started", king) + ", \"phase\": " + event.phase();
          case EFFECT ->
              towerEventHead(tick, "activation_effect", king) + ", \"phase\": " + event.phase();
          case ACTIVATING_FINISHED -> towerEventHead(tick, "activating_finished", king);
          case ACTIVATING_REMOVED -> towerEventHead(tick, "activating_removed", king);
        };
    activationEvents.add(line + "}");
  }

  /** The opening of a tower event line, up to the tower's name. */
  private String towerEventHead(int tick, String kind, TowerEntity tower) {
    return "  {\"tick\": "
        + (tick - firstTick)
        + ", \"event\": "
        + quote(kind)
        + ", \"tower\": "
        + quote(tower.name());
  }

  private static String nameOrNull(String name) {
    return name == null ? "null" : quote(name);
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
    if (towersAttack) {
      out.append(" \"tower_level\": ").append(towerLevel).append(",\n");
      out.append(" \"towers_attack\": true,\n");
    }
    appendList(out, "towers", towers).append(",\n");
    List<String> unitLines = new ArrayList<>();
    for (CharacterEntity other : others) {
      if (otherHeaders.containsKey(other)) {
        unitLines.add(otherHeaders.get(other));
      }
    }
    if (!unitLines.isEmpty()) {
      appendList(out, "units", unitLines).append(",\n");
    }
    appendList(out, "events", events).append(",\n");
    if (towersAttack) {
      appendList(out, "tower_events", towerEvents).append(",\n");
    }
    List<String> fields = towersAttack ? FIGHTING_FIELDS : FIELDS;
    out.append(" \"fields\": [");
    for (int i = 0; i < fields.size(); i++) {
      out.append(i == 0 ? "" : ", ").append(quote(fields.get(i)));
    }
    out.append("],\n");
    if (!unitLines.isEmpty()) {
      out.append(" \"unit_fields\": [");
      for (int i = 0; i < UNIT_FIELDS.size(); i++) {
        out.append(i == 0 ? "" : ", ").append(quote(UNIT_FIELDS.get(i)));
      }
      out.append("],\n \"unit_records\": {\n");
      List<String> blocks = new ArrayList<>();
      for (CharacterEntity other : others) {
        if (otherRecords.containsKey(other)) {
          blocks.add(
              "  "
                  + quote(other.name())
                  + ": [\n"
                  + String.join(",\n", otherRecords.get(other))
                  + "\n  ]");
        }
      }
      out.append(String.join(",\n", blocks)).append("\n },\n");
    }
    appendList(out, "records", records);
    if (!projectiles.isEmpty()) {
      out.append(",\n");
      appendList(out, "projectiles", projectiles);
    }
    out.append("\n}\n");
    return out.toString();
  }

  /** The opening of an event line, up to its kind. */
  private String eventHead(int tick) {
    return "  {\"tick\": " + (tick - firstTick) + ", \"event\": ";
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

  /** One record; the character's own hit points are written only when given. */
  private static String record(
      int tick,
      int x,
      int y,
      int state,
      String ref,
      int route,
      Integer speed,
      Integer hp,
      Integer ownHitPoints) {
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
        + (ownHitPoints == null ? "" : ", " + ownHitPoints)
        + "]";
  }

  /** The text as a JSON string, with the two characters that need escaping escaped. */
  private static String quote(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
