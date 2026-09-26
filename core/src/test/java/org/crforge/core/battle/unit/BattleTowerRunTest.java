package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.AreaDamage;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.move.MovementState;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Drives the runs in which the towers fight back through {@link Battle} and compares them with the
 * reference runs, tick for tick.
 *
 * <p>Three references. In the first a Knight walks up the left lane: the princess tower in front of
 * it locks on at 130, fires its first arrow at 145 and one every sixteen ticks after, each arrow
 * taking 109 off the Knight, which reaches the tower, lands seven hits of its own and dies to the
 * seventeenth arrow at 405. In the second a Musketeer stops short of the same tower and fires five
 * shots before the tower's seventh arrow kills it at 253; its last shot lands after it has left, so
 * each run is played to its last event and projectile position. In the third a Wizard fires three
 * fireballs, whose impacts damage everything within their radius - the tower alone, as the other
 * towers stand outside it - before the tower kills it at 253. The king towers stay out of all
 * three: nothing damages a king tower and no princess tower falls, so neither king is activated.
 *
 * <p>Reference tick {@code n} is battle tick {@code n}, as in {@link BattleKillRunTest}, and the
 * same one-tick deploying correction and end-of-step reference allowance apply. The towers and the
 * unit are visited from the same first tick, as in the reference.
 */
class BattleTowerRunTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** A Knight on the left lane, shot down by the princess tower in front of it. */
  static final String KNIGHT_REFERENCE = "/pathfinding/golden/tower_vs_knight_left.json";

  /** A Musketeer on the left lane, trading shots with the princess tower in front of it. */
  static final String MUSKETEER_REFERENCE = "/pathfinding/golden/musketeer_vs_tower.json";

  /** A Wizard on the left lane, whose fireballs damage everything in their radius. */
  static final String WIZARD_REFERENCE = "/pathfinding/golden/wizard_vs_tower.json";

  /** A Valkyrie on the left lane, whose every hit damages the circle around herself. */
  static final String VALKYRIE_REFERENCE = "/pathfinding/golden/valkyrie_vs_tower.json";

  /** The Valkyrie with an enemy Knight joining her at the tower: two victims in each circle. */
  static final String VALKYRIE_TWO_VICTIMS_REFERENCE =
      "/pathfinding/golden/valkyrie_two_victims.json";

  /**
   * The Valkyrie fighting an enemy Knight beside her own princess tower, which her circle spares.
   */
  static final String VALKYRIE_OWN_TOWER_REFERENCE = "/pathfinding/golden/valkyrie_own_tower.json";

  /**
   * The Knight again with the towers at the first level: it destroys the princess tower, which
   * wakes the king tower, and dies under the fire of three towers.
   */
  static final String LEVEL_ONE_REFERENCE = "/pathfinding/golden/tower_vs_knight_left_level1.json";

  /**
   * The Knight run again with the Royal Chef's level-up row scheduled on the Knight on tick 250:
   * its hit points keep their share of the higher maximum and its hits deal the next level's
   * damage.
   */
  static final String LEVEL_UP_REFERENCE = "/pathfinding/golden/knight_level_up.json";

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        KNIGHT_REFERENCE,
        MUSKETEER_REFERENCE,
        WIZARD_REFERENCE,
        LEVEL_ONE_REFERENCE,
        VALKYRIE_REFERENCE,
        VALKYRIE_TWO_VICTIMS_REFERENCE,
        VALKYRIE_OWN_TOWER_REFERENCE,
        LEVEL_UP_REFERENCE
      })
  void theWholeRunMatchesTheReferenceTickForTick(String resource) {
    JsonNode reference = BattleMusketeerRunTest.load(resource);
    List<JsonNode> records = BattleMusketeerRunTest.records(reference);
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), reference.get("tower_level").asInt());
    Battle battle = match.getBattle();
    List<CharacterEntity> units = deployAll(match, reference);
    CharacterEntity unit = units.get(0);
    Map<Integer, List<JsonNode>> otherRecords = otherRecordsByTick(reference);

    for (int i = 0; i < records.size(); i++) {
      battle.step();
      JsonNode record = records.get(i);
      for (JsonNode other : otherRecords.getOrDefault(record.get("tick").asInt(), List.of())) {
        assertOtherUnit(battle, units, other, record.get("tick").asInt());
      }
      String where = "reference tick " + record.get("tick").asInt();

      assertThat(unit.getView().getX()).as("%s x", where).isEqualTo(record.get("x").asInt());
      assertThat(unit.getView().getY()).as("%s y", where).isEqualTo(record.get("y").asInt());
      assertThat(unit.getView().getState())
          .as("%s state", where)
          .isEqualTo(BattleGoldenTrajectoryTest.expectedState(records, i));
      assertThat(BattleMusketeerRunTest.referenceName(unit))
          .as("%s reference", where)
          .isEqualTo(BattleMusketeerRunTest.expectedReference(record));
      assertThat(unit.getUnit().movement().getRoute().size())
          .as("%s route length", where)
          .isEqualTo(record.get("route").asInt());
      if (!record.get("speed").isNull()) {
        assertThat(unit.getSpeedBudget())
            .as("%s movement budget", where)
            .isEqualTo(record.get("speed").asInt());
      }
      assertThat(BattleMusketeerRunTest.referenceHitPoints(unit))
          .as("%s hit points of the reference", where)
          .isEqualTo(
              BattleMusketeerRunTest.expectedReference(record) == null
                  ? null
                  : record.get("hp").asInt());
      assertThat(unit.getHitPoints().getHitPoints())
          .as("%s the unit's own hit points", where)
          .isEqualTo(record.get("own_hp").asInt());
    }

    assertThat(unit.getHitPoints().getHitPoints()).as("the unit is dead").isZero();
    assertThat(battle.getHolder().entities())
        .as("the unit has left the holder in the tick it died")
        .doesNotContain(unit);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        KNIGHT_REFERENCE,
        MUSKETEER_REFERENCE,
        WIZARD_REFERENCE,
        LEVEL_ONE_REFERENCE,
        VALKYRIE_REFERENCE,
        VALKYRIE_TWO_VICTIMS_REFERENCE,
        VALKYRIE_OWN_TOWER_REFERENCE
      })
  void everyLaunchImpactHitAndDeathFallsOnTheReferenceTick(String resource) {
    JsonNode reference = BattleMusketeerRunTest.load(resource);
    List<String> expected = new ArrayList<>();
    for (JsonNode event : reference.get("events")) {
      expected.add(eventLine(event));
    }

    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), reference.get("tower_level").asInt());
    Battle battle = match.getBattle();
    CharacterEntity unit = deploy(match, reference);
    int[] currentTick = {-1};
    List<String> events = new ArrayList<>();
    match.getWorld().addObserver(eventCollector(currentTick, events));

    int lastTick = lastTick(reference);
    for (int tick = 0; tick <= lastTick; tick++) {
      currentTick[0] = tick;
      battle.step();
    }

    assertThat(events)
        .as("every launch, impact, hit and death, in the order the battle made them")
        .containsExactlyElementsOf(expected);
    assertThat(battle.getHolder().entities()).doesNotContain(unit);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        KNIGHT_REFERENCE,
        MUSKETEER_REFERENCE,
        WIZARD_REFERENCE,
        LEVEL_ONE_REFERENCE,
        VALKYRIE_REFERENCE,
        VALKYRIE_TWO_VICTIMS_REFERENCE,
        VALKYRIE_OWN_TOWER_REFERENCE
      })
  void everyProjectileFliesThroughTheReferencePositions(String resource) {
    JsonNode reference = BattleMusketeerRunTest.load(resource);
    List<String> expected = new ArrayList<>();
    for (JsonNode position : reference.get("projectiles")) {
      expected.add(position.toString());
    }

    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), reference.get("tower_level").asInt());
    Battle battle = match.getBattle();
    deploy(match, reference);
    int[] currentTick = {-1};
    List<String> positions = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void afterPostHooks(
                  int tick, List<WorldEntity> present, List<ProjectileEntity> inFlight) {
                if (currentTick[0] < 0) {
                  return;
                }
                for (ProjectileEntity projectile : inFlight) {
                  if (!projectile.isReleased()) {
                    positions.add(
                        "[%d,%d,%d,%d,%d]"
                            .formatted(
                                currentTick[0],
                                projectile.getId(),
                                projectile.getX(),
                                projectile.getY(),
                                projectile.getZ()));
                  }
                }
              }
            });

    int lastTick = lastTick(reference);
    for (int tick = 0; tick <= lastTick; tick++) {
      currentTick[0] = tick;
      battle.step();
    }

    assertThat(positions)
        .as("every projectile's position after each of its flight steps")
        .containsExactlyElementsOf(expected);
  }

  /**
   * The records of the further units, each tagged with its unit's name and keyed by tick, from the
   * layout's compact rows.
   */
  static Map<Integer, List<JsonNode>> otherRecordsByTick(JsonNode reference) {
    Map<Integer, List<JsonNode>> byTick = new HashMap<>();
    if (!reference.has("unit_records")) {
      return byTick;
    }
    List<String> fields = new ArrayList<>();
    reference.get("unit_fields").forEach(field -> fields.add(field.asText()));
    reference
        .get("unit_records")
        .fields()
        .forEachRemaining(
            entry -> {
              for (JsonNode row : entry.getValue()) {
                ObjectNode record = MAPPER.createObjectNode();
                record.put("name", entry.getKey());
                for (int i = 0; i < fields.size(); i++) {
                  record.set(fields.get(i), row.get(i));
                }
                byTick
                    .computeIfAbsent(record.get("tick").asInt(), t -> new ArrayList<>())
                    .add(record);
              }
            });
    return byTick;
  }

  /**
   * Holds a further unit to its record: position, state, reference and its own hit points. The
   * reference is the recorded one unless the entity it names has left the holder in the step's
   * closing cleanup, which the record, taken before the cleanup, does not show.
   */
  private static void assertOtherUnit(
      Battle battle, List<CharacterEntity> units, JsonNode record, int tick) {
    CharacterEntity other =
        units.stream()
            .filter(u -> u.name().equals(record.get("name").asText()))
            .findFirst()
            .orElseThrow();
    String where = "reference tick " + tick + ": " + other.name();
    assertThat(other.getView().getX()).as("%s x", where).isEqualTo(record.get("x").asInt());
    assertThat(other.getView().getY()).as("%s y", where).isEqualTo(record.get("y").asInt());
    assertThat(other.getView().getState())
        .as("%s state", where)
        .isEqualTo(record.get("state").asInt());
    String recorded = record.get("ref").isNull() ? null : record.get("ref").asText();
    boolean stillThere =
        battle.getHolder().entities().stream()
            .anyMatch(e -> e instanceof WorldEntity w && w.name().equals(recorded));
    String expectedReference = stillThere ? recorded : null;
    assertThat(BattleMusketeerRunTest.referenceName(other))
        .as("%s reference", where)
        .isEqualTo(expectedReference);
    assertThat(other.getHitPoints().getHitPoints())
        .as("%s own hit points", where)
        .isEqualTo(record.get("own_hp").asInt());
  }

  /** Collects every launch, impact, hit and death as the reference lists them. */
  static WorldObserver eventCollector(int[] currentTick, List<String> events) {
    return new WorldObserver() {
      @Override
      public void damageDealt(int tick, WorldEntity target, int damage, DamageResult result) {
        if (currentTick[0] < 0 || !result.landed()) {
          return;
        }
        events.add(
            "%d hit %s %d %d"
                .formatted(
                    currentTick[0], target.name(), damage, target.getTargetView().getHitPoints()));
        if (result.died()) {
          events.add("%d death %s".formatted(currentTick[0], target.name()));
        }
      }

      @Override
      public void areaHit(
          int tick,
          WorldEntity attacker,
          WorldEntity victim,
          int damage,
          int hitId,
          DamageResult result) {
        if (currentTick[0] < 0) {
          return;
        }
        events.add(
            "%d area_hit %s %s %d %d %d"
                .formatted(
                    currentTick[0],
                    attacker.name(),
                    victim.name(),
                    damage,
                    victim.getTargetView().getHitPoints(),
                    hitId));
        if (result.died()) {
          events.add("%d death %s".formatted(currentTick[0], victim.name()));
        }
      }

      @Override
      public void areaDamaged(
          int tick, WorldEntity owner, AreaDamage.Area area, AreaDamage.Outcome outcome) {
        if (currentTick[0] < 0) {
          return;
        }
        events.add(
            "%d area %s %d %d r%d %d %d %d %s %s %s"
                .formatted(
                    currentTick[0],
                    owner.name(),
                    area.x(),
                    area.y(),
                    area.radius(),
                    area.damage(),
                    area.towerDamage(),
                    area.hitId(),
                    names(outcome.inCircle()),
                    names(outcome.validated()),
                    names(outcome.damaged())));
      }

      @Override
      public void projectileLaunched(int tick, ProjectileEntity projectile) {
        if (currentTick[0] < 0) {
          return;
        }
        events.add(
            "%d launch %s %s %s %s %d %d %d aim %d %d %d"
                .formatted(
                    currentTick[0],
                    projectile.name(),
                    projectile.getData().name(),
                    projectile.getOwner() == null ? null : projectile.getOwner().name(),
                    projectile.getTarget() == null ? null : projectile.getTarget().name(),
                    projectile.getX(),
                    projectile.getY(),
                    projectile.getZ(),
                    projectile.getAimX(),
                    projectile.getAimY(),
                    projectile.getAimZ()));
      }

      @Override
      public void pushbackRequested(
          int tick,
          WorldEntity unit,
          boolean started,
          int fromX,
          int fromY,
          MovementState pushback) {
        events.add(
            "%d pushback %s %d from %d %d at %d %d target %d %d budget %d"
                .formatted(
                    currentTick[0],
                    unit.name(),
                    started ? 1 : 0,
                    fromX,
                    fromY,
                    unit.getView().getX(),
                    unit.getView().getY(),
                    pushback.getTargetX(),
                    pushback.getTargetY(),
                    pushback.getPushbackBudget()));
      }

      @Override
      public void relocated(int tick, WorldEntity unit, int x, int y, int toX, int toY) {
        events.add(
            "%d relocate %s %d %d to %d %d".formatted(currentTick[0], unit.name(), x, y, toX, toY));
      }

      @Override
      public void projectileImpacted(
          int tick,
          ProjectileEntity projectile,
          WorldEntity target,
          int damage,
          DamageResult result) {
        if (currentTick[0] < 0) {
          return;
        }
        events.add(
            "%d impact %s %s %d %d %d %d %d"
                .formatted(
                    currentTick[0],
                    projectile.name(),
                    target.name(),
                    damage,
                    target.getTargetView().getHitPoints(),
                    projectile.getX(),
                    projectile.getY(),
                    projectile.getZ()));
        if (result.died()) {
          events.add("%d death %s".formatted(currentTick[0], target.name()));
        }
      }
    };
  }

  private static List<String> names(List<TargetView> views) {
    return views.stream().map(TargetView::name).toList();
  }

  private static List<String> jsonNames(JsonNode list) {
    List<String> names = new ArrayList<>();
    list.forEach(name -> names.add(name.asText()));
    return names;
  }

  /**
   * The last tick the reference shows anything on: its unit's last record, or a later event or
   * projectile position, as when a shot is still in flight after the unit that fired it has left.
   */
  static int lastTick(JsonNode reference) {
    int last = BattleMusketeerRunTest.records(reference).size() - 1;
    for (JsonNode event : reference.get("events")) {
      last = Math.max(last, event.get("tick").asInt());
    }
    for (JsonNode position : reference.path("projectiles")) {
      last = Math.max(last, position.get(0).asInt());
    }
    return last;
  }

  /** One reference event in the collector's layout. */
  static String eventLine(JsonNode event) {
    int tick = event.get("tick").asInt();
    String kind = event.get("event").asText();
    return switch (kind) {
      case "hit" ->
          "%d hit %s %d %d"
              .formatted(
                  tick,
                  event.get("target").asText(),
                  event.get("damage").asInt(),
                  event.get("hp").asInt());
      case "death" -> "%d death %s".formatted(tick, event.get("target").asText());
      case "pushback" ->
          "%d pushback %s %d from %d %d at %d %d target %d %d budget %d"
              .formatted(
                  tick,
                  event.get("unit").asText(),
                  event.get("started").asInt(),
                  event.get("frm").get(0).asInt(),
                  event.get("frm").get(1).asInt(),
                  event.get("x").asInt(),
                  event.get("y").asInt(),
                  event.get("target").get(0).asInt(),
                  event.get("target").get(1).asInt(),
                  event.get("budget").asInt());
      case "relocate" ->
          "%d relocate %s %d %d to %d %d"
              .formatted(
                  tick,
                  event.get("unit").asText(),
                  event.get("x").asInt(),
                  event.get("y").asInt(),
                  event.get("to").get(0).asInt(),
                  event.get("to").get(1).asInt());
      case "launch" ->
          "%d launch %s %s %s %s %d %d %d aim %d %d %d"
              .formatted(
                  tick,
                  event.get("projectile").asText(),
                  event.get("config").asText(),
                  event.get("owner").isNull() ? null : event.get("owner").asText(),
                  event.get("target").isNull() ? null : event.get("target").asText(),
                  event.get("x").asInt(),
                  event.get("y").asInt(),
                  event.get("z").asInt(),
                  event.get("aim").get(0).asInt(),
                  event.get("aim").get(1).asInt(),
                  event.get("aim_z").asInt());
      case "impact" ->
          "%d impact %s %s %d %d %d %d %d"
              .formatted(
                  tick,
                  event.get("projectile").asText(),
                  event.get("target").asText(),
                  event.get("damage").asInt(),
                  event.get("hp").asInt(),
                  event.get("x").asInt(),
                  event.get("y").asInt(),
                  event.get("z").asInt());
      case "area_hit" ->
          "%d area_hit %s %s %d %d %d"
              .formatted(
                  tick,
                  event.get("attacker").asText(),
                  event.get("target").asText(),
                  event.get("damage").asInt(),
                  event.get("hp").asInt(),
                  event.get("hit_id").asInt());
      case "area" ->
          "%d area %s %d %d r%d %d %d %d %s %s %s"
              .formatted(
                  tick,
                  event.get("owner").asText(),
                  event.get("centre").get(0).asInt(),
                  event.get("centre").get(1).asInt(),
                  event.get("radius").asInt(),
                  event.get("damage").asInt(),
                  event.get("tower_damage").asInt(),
                  event.get("hit_id").asInt(),
                  jsonNames(event.get("in_circle")),
                  jsonNames(event.get("validated")),
                  jsonNames(event.get("victims")));
      default -> throw new IllegalStateException("unknown event " + kind);
    };
  }

  /** Places the reference's unit at the reference's level, side and position on tick 0. */
  static CharacterEntity deploy(Standard1v1Battle match, JsonNode reference) {
    return deployAll(match, reference).get(0);
  }

  /**
   * Places the reference's unit on tick 0 and every further unit the reference lists on its own
   * tick, under its own name, at the reference's level, then schedules each row the reference lists
   * on its unit in the command pass of its tick, the unit as its cause, as a buff's starting action
   * or an ability's activation would.
   *
   * <p>A placement runs at the head of the step of its tick, so a unit placed on the reference's
   * tick {@code n} is first visited in battle step {@code n}, as in the reference.
   *
   * @return the reference's unit first, then the further units in the order the reference lists
   *     them
   */
  static List<CharacterEntity> deployAll(Standard1v1Battle match, JsonNode reference) {
    List<CharacterEntity> units = new ArrayList<>();
    units.add(
        match.deploy(
            0,
            unitData(reference.get("card").asText()),
            reference.get("level").asInt(),
            reference.get("side").asInt(),
            reference.get("deploy").get(0).asInt(),
            reference.get("deploy").get(1).asInt()));
    if (reference.has("units")) {
      for (JsonNode unit : reference.get("units")) {
        units.add(
            match.deploy(
                unit.get("tick").asInt(),
                unitData(unit.get("card").asText()),
                reference.get("level").asInt(),
                unit.get("side").asInt(),
                unit.get("deploy").get(0).asInt(),
                unit.get("deploy").get(1).asInt(),
                unit.get("name").asText()));
      }
    }
    for (JsonNode schedule : reference.path("unit_schedules")) {
      String name = schedule.get("unit").asText();
      CharacterEntity unit =
          units.stream().filter(u -> u.name().equals(name)).findFirst().orElseThrow();
      match.scheduleAction(
          schedule.get("tick").asInt(),
          unit,
          GameData.actions()
              .build(schedule.get("action").asText(), match.getWorld().binding(unit)));
    }
    return units;
  }

  private static UnitData unitData(String cardName) {
    return GameData.unit(cardName);
  }
}
