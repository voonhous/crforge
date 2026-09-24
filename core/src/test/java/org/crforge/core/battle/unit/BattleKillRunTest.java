package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.card.UnitDataMapper;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.target.HitSink;
import org.crforge.core.pathfinding.target.SelectionChain;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drives the kill run - a Knight deployed on the left, attacking the princess tower and then the
 * king tower until both are gone - through {@link Battle} and compares it with the reference run.
 *
 * <p>The reference records every tick of the 1258-tick run - the Knight's position, state, target,
 * route length and movement budget, and the target's remaining hit points - and beside the records
 * every hit with its tick, target, damage and the target's remaining hit points, and both deaths.
 * This test holds the battle to all of it: the timing of the hits - the first lands nine ticks
 * after the lock, because the attack time is credited the whole load when the attack starts, and
 * the rest follow at the hit speed - what each tower stands at after each of them, the death of the
 * princess tower on the sixteenth hit and its removal in the tick it dies, the five ticks the
 * Knight then stands without a target while the attack finish time runs down, the resume and the
 * walk to the king tower, its death on the twenty-fifth hit, the hit points every tower and the
 * Knight start the run with, and the damage of one Knight hit, all at the reference's level. {@link
 * TrajectoryRecorderTest} writes the same run out and holds the file to the reference byte for
 * byte.
 *
 * <p>Reference tick {@code n} is battle step {@code n + 1}, as in {@link
 * BattleGoldenTrajectoryTest}, and the one-tick deploying correction is the same. One more offset
 * of the same kind: a record is written before the tick's closing cleanup, so the record of the
 * tick a target dies on still names it, while the step has dropped the reference by the time it
 * returns. {@link #expectedReference} allows for that.
 */
class BattleKillRunTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The tower the Knight attacks first, as the reference names it. */
  private static final String PRINCESS_TOWER = "PrincessTower_1_1";

  /** The tower the Knight walks on to once the first is gone. */
  private static final String KING_TOWER = "KingTower_1_0";

  /** Reference tick the first hit lands on. */
  private static final int FIRST_HIT_TICK = 244;

  /** Reference tick the sixteenth hit kills the princess tower on. */
  private static final int PRINCESS_DEATH_TICK = 604;

  /**
   * Reference tick the Knight takes the king tower and walks again, after the attack finish time.
   */
  private static final int RESUME_TICK = 610;

  @Test
  @DisplayName("the Knight's hits on the princess tower land on the reference ticks")
  void theHitsOnThePrincessTowerLandOnTheReferenceTicks() {
    JsonNode reference = load("/pathfinding/golden/knight_left_kill.json");
    List<Integer> expectedTicks = new ArrayList<>();
    for (JsonNode event : reference.get("events")) {
      if (event.get("event").asText().equals("hit")
          && event.get("target").asText().equals(PRINCESS_TOWER)) {
        expectedTicks.add(event.get("tick").asInt());
      }
    }
    assertThat(expectedTicks)
        .as("the reference records sixteen hits on the princess tower")
        .hasSize(16);
    int lastTick = expectedTicks.get(expectedTicks.size() - 1);

    Standard1v1Battle match = new Standard1v1Battle(reference.get("level").asInt());
    Battle battle = match.getBattle();
    CharacterEntity knight = deployKnight(match, reference);

    // Every hit is recorded with the reference tick it landed on, then handed on to the sink the
    // character installed, so the attacker's own bookkeeping still runs.
    List<int[]> hits = new ArrayList<>();
    List<String> targets = new ArrayList<>();
    int[] currentTick = {-1};
    SelectionChain selection = knight.getUnit().selection();
    HitSink inner = selection.getHitSink();
    selection.setHitSink(
        (target, sequenceIndex, extraTargets, last) -> {
          hits.add(new int[] {currentTick[0], sequenceIndex, extraTargets, last ? 1 : 0});
          targets.add(target == null ? null : target.name());
          return inner.hit(target, sequenceIndex, extraTargets, last);
        });

    battle.step();
    for (int tick = 0; tick <= lastTick; tick++) {
      currentTick[0] = tick;
      battle.step();
    }

    assertThat(hits.stream().map(hit -> hit[0]).toList())
        .as("the ticks the hits landed on")
        .containsExactlyElementsOf(expectedTicks);
    assertThat(targets).as("every hit is on the princess tower").containsOnly(PRINCESS_TOWER);
    for (int[] hit : hits) {
      assertThat(hit[1]).as("a single-target attack").isEqualTo(-1);
      assertThat(hit[2]).as("no extra targets").isZero();
      assertThat(hit[3]).as("the one hit is the last").isEqualTo(1);
    }

    // The sixteenth hit killed the tower, whose removal at the end of the tick took the Knight's
    // reference with it and started the target-lost countdown; the attack itself runs on.
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.ATTACKING);
    assertThat(referenceName(knight)).isNull();
    assertThat(knight.getUnit().targeting().getTargetLostTimerMs()).isEqualTo(1);
    assertThat(knight.getUnit().targeting().isHitStarted()).isTrue();
    assertThat(knight.getUnit().targeting().getLoadTimerMs())
        .as("the last hit reloaded the countdown")
        .isEqualTo(700);
  }

  @Test
  @DisplayName("every hit takes its tower down to the reference's remaining hit points")
  void everyHitTakesItsTowerDownToTheReferenceHitPoints() {
    JsonNode reference = load("/pathfinding/golden/knight_left_kill.json");
    List<String> expected = new ArrayList<>();
    for (JsonNode event : reference.get("events")) {
      expected.add(
          event.get("event").asText().equals("death")
              ? "%d death %s".formatted(event.get("tick").asInt(), event.get("target").asText())
              : "%d hit %s %d"
                  .formatted(
                      event.get("tick").asInt(),
                      event.get("target").asText(),
                      event.get("hp").asInt()));
    }
    assertThat(expected).as("the reference records forty hits and two deaths").hasSize(42);
    int lastTick = reference.get("events").get(41).get("tick").asInt();

    Standard1v1Battle match = new Standard1v1Battle(reference.get("level").asInt());
    Battle battle = match.getBattle();
    deployKnight(match, reference);

    // Every tick a tower's hit points fall is one hit, recorded with the tick and what the tower
    // stands at afterwards, as the reference records it. A tower that reaches zero is dead, which
    // the reference records as a second event on the same tick. The reference's damage is what the
    // hit dealt, which the hit that kills overshoots, so it is not read back from the drop. Both
    // towers are taken from the holder before the run, because a dead tower leaves it in the tick
    // it dies.
    battle.step();
    List<TowerEntity> towers =
        List.of(towerNamed(battle, PRINCESS_TOWER), towerNamed(battle, KING_TOWER));
    Map<String, Integer> standing = new HashMap<>();
    for (TowerEntity tower : towers) {
      standing.put(tower.name(), tower.getHitPoints().getHitPoints());
    }
    List<String> events = new ArrayList<>();
    for (int tick = 0; tick <= lastTick; tick++) {
      battle.step();
      for (TowerEntity tower : towers) {
        int now = tower.getHitPoints().getHitPoints();
        if (now != standing.get(tower.name())) {
          events.add("%d hit %s %d".formatted(tick, tower.name(), now));
          if (now == 0) {
            events.add("%d death %s".formatted(tick, tower.name()));
          }
          standing.put(tower.name(), now);
        }
      }
    }

    assertThat(events)
        .as("every hit on the two towers, and their deaths")
        .containsExactlyElementsOf(expected);
    for (TowerEntity tower : towers) {
      assertThat(tower.getHitPoints().getHitPoints()).as("%s is at zero", tower.name()).isZero();
      assertThat(tower.getView().isAlive()).as("%s is dead", tower.name()).isFalse();
      assertThat(tower.isRemovable()).as("%s is dropped by the holder", tower.name()).isTrue();
      assertThat(battle.getHolder().entities())
          .as("%s has left the holder", tower.name())
          .doesNotContain(tower);
    }
  }

  @Test
  @DisplayName("the whole run matches the reference tick for tick, the king tower's death included")
  void theWholeRunMatchesTheReferenceTickForTick() {
    JsonNode reference = load("/pathfinding/golden/knight_left_kill.json");
    List<JsonNode> records = records(reference);
    assertThat(records).as("the reference is the whole run").hasSize(1258);

    Standard1v1Battle match = new Standard1v1Battle(reference.get("level").asInt());
    Battle battle = match.getBattle();
    CharacterEntity knight = deployKnight(match, reference);

    battle.step();
    TowerEntity king = towerNamed(battle, KING_TOWER);
    for (int i = 0; i < records.size(); i++) {
      battle.step();
      JsonNode record = records.get(i);
      String where = "reference tick " + record.get("tick").asInt();

      assertThat(knight.getId()).as("%s: the Knight follows the six towers", where).isEqualTo(7);
      assertThat(knight.getView().getX()).as("%s x", where).isEqualTo(record.get("x").asInt());
      assertThat(knight.getView().getY()).as("%s y", where).isEqualTo(record.get("y").asInt());
      assertThat(knight.getView().getState())
          .as("%s state", where)
          .isEqualTo(BattleGoldenTrajectoryTest.expectedState(records, i));
      assertThat(referenceName(knight))
          .as("%s reference", where)
          .isEqualTo(expectedReference(record));
      assertThat(knight.getUnit().movement().getRoute().size())
          .as("%s route length", where)
          .isEqualTo(record.get("route").asInt());
      if (record.get("speed").isNull()) {
        assertThat(knight.getSpeedBudget())
            .as("%s: no movement visit while deploying", where)
            .isZero();
      } else {
        assertThat(knight.getSpeedBudget())
            .as("%s movement budget", where)
            .isEqualTo(record.get("speed").asInt());
      }
      assertThat(referenceHitPoints(knight))
          .as("%s hit points of the reference", where)
          .isEqualTo(expectedReference(record) == null ? null : record.get("hp").asInt());
    }

    assertThat(battle.getTick()).isEqualTo(records.size() + 1);
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.ATTACKING);
    assertThat(referenceName(knight)).as("dropped by the king tower's removal").isNull();
    assertThat(king.getHitPoints().getHitPoints()).as("the king tower is at zero").isZero();
    assertThat(battle.getHolder().entities())
        .as("the king tower has left the holder in the tick it died")
        .doesNotContain(king);
  }

  @Test
  @DisplayName(
      "a dead tower leaves the holder in the tick it dies, and the Knight resumes after the finish"
          + " time")
  void aDeadTowerLeavesTheHolderInTheTickItDiesAndTheKnightResumesAfterTheFinishTime() {
    JsonNode reference = load("/pathfinding/golden/knight_left_kill.json");
    Standard1v1Battle match = new Standard1v1Battle(reference.get("level").asInt());
    Battle battle = match.getBattle();
    CharacterEntity knight = deployKnight(match, reference);

    battle.step();
    TowerEntity tower = towerNamed(battle, PRINCESS_TOWER);
    GridEntity towerView = tower.getView();
    for (int tick = 0; tick < PRINCESS_DEATH_TICK; tick++) {
      battle.step();
    }
    assertThat(tower.getHitPoints().getHitPoints()).as("one hit left").isEqualTo(22);
    assertThat(tower.getTargetView().alive()).isTrue();
    assertThat(battle.getHolder().entities()).contains(tower);
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.ATTACKING);
    assertThat(referenceName(knight)).isEqualTo(PRINCESS_TOWER);

    // The killing tick: the hit lands in the targeting visit, the tower is dead for the rest of the
    // tick and gone by the closing cleanup, which tells the Knight at once: its reference is
    // dropped, its default targets lose the tower, and the target-lost countdown starts.
    battle.step();
    assertThat(tower.getHitPoints().getHitPoints()).isZero();
    assertThat(tower.getTargetView().alive()).as("the validator's alive answer").isFalse();
    assertThat(tower.isRemovable()).isTrue();
    assertThat(battle.getHolder().entities())
        .as("the closing cleanup of the killing tick dropped the tower")
        .doesNotContain(tower);
    assertThat(match.getWorld().entityOf(towerView)).as("the world forgot it too").isNull();
    assertThat(knight.getUnit().selection().view(towerView))
        .as("the selection has forgotten the tower")
        .isNull();
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.ATTACKING);
    assertThat(referenceName(knight)).as("dropped by the removal notice").isNull();
    assertThat(knight.getUnit().targeting().getTargetLostTimerMs()).isEqualTo(1);
    assertThat(knight.getSpeedBudget()).isZero();

    // The attack finish time: five visits standing in the attacking state without a target while
    // the countdown runs to 250 ms, the last of which clears the attack.
    for (int tick = PRINCESS_DEATH_TICK + 1; tick < RESUME_TICK; tick++) {
      battle.step();
      assertThat(knight.getView().getState())
          .as("tick %d", tick)
          .isEqualTo(GridEntityState.ATTACKING);
      assertThat(referenceName(knight)).as("tick %d", tick).isNull();
      assertThat(knight.getView().getX()).as("tick %d", tick).isEqualTo(3731);
      assertThat(knight.getView().getY()).as("tick %d", tick).isEqualTo(22854);
    }
    assertThat(knight.getUnit().targeting().getTargetLostTimerMs()).as("run down").isZero();
    assertThat(knight.getUnit().targeting().getAttackTimerMs()).as("the attack is over").isZero();

    // The sixth visit selects the king tower and resumes the walk.
    battle.step();
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.MOVING);
    assertThat(referenceName(knight)).isEqualTo(KING_TOWER);
    assertThat(knight.getUnit().movement().getRoute().size())
        .as("the route to the king")
        .isEqualTo(11);
    assertThat(knight.getSpeedBudget()).isEqualTo(60);
    assertThat(referenceHitPoints(knight)).isEqualTo(4824);
  }

  /**
   * The reference's records as objects keyed by its field names; the file lists them by position.
   */
  private static List<JsonNode> records(JsonNode reference) {
    List<String> fields = new ArrayList<>();
    reference.get("fields").forEach(field -> fields.add(field.asText()));
    List<JsonNode> records = new ArrayList<>();
    for (JsonNode row : reference.get("records")) {
      ObjectNode record = MAPPER.createObjectNode();
      for (int i = 0; i < fields.size(); i++) {
        record.set(fields.get(i), row.get(i));
      }
      records.add(record);
    }
    return records;
  }

  /**
   * The reference the unit holds at the end of the step that produced the given record: the
   * recorded one, except on the tick it dies, when the closing cleanup has already dropped it.
   */
  private static String expectedReference(JsonNode record) {
    if (record.get("ref").isNull() || record.get("hp").asInt() == 0) {
      return null;
    }
    return record.get("ref").asText();
  }

  /** The tower of the given name, as the battle holds it. */
  private static TowerEntity towerNamed(Battle battle, String name) {
    for (BattleEntity entity : battle.getHolder().entities()) {
      if (entity instanceof TowerEntity tower && tower.name().equals(name)) {
        return tower;
      }
    }
    throw new IllegalStateException("No tower named " + name);
  }

  /** The name of the tower the unit currently holds a reference to, or null. */
  private static String referenceName(CharacterEntity unit) {
    TargetView reference = unit.getUnit().targeting().getReference();
    return reference == null ? null : reference.name();
  }

  /** The hit points the unit's reference advertises, or null without a reference. */
  private static Integer referenceHitPoints(CharacterEntity unit) {
    TargetView reference = unit.getUnit().targeting().getReference();
    return reference == null ? null : reference.getHitPoints();
  }

  @Test
  @DisplayName("everything stands at the reference hit points, and the Knight hits for 202")
  void everythingStandsAtTheReferenceHitPoints() {
    JsonNode reference = load("/pathfinding/golden/knight_left_kill.json");
    int level = reference.get("level").asInt();
    Map<String, Integer> expectedTowerHitPoints = new HashMap<>();
    for (JsonNode tower : reference.get("towers")) {
      expectedTowerHitPoints.put(tower.get("name").asText(), tower.get("hp").asInt());
    }
    assertThat(expectedTowerHitPoints).as("the reference lists the six towers").hasSize(6);

    Standard1v1Battle match = new Standard1v1Battle(level);
    Battle battle = match.getBattle();
    CharacterEntity knight = deployKnight(match, reference);

    // Run up to the tick before the first hit lands, so everything still stands at its maximum.
    battle.step();
    for (int tick = 0; tick < FIRST_HIT_TICK; tick++) {
      battle.step();
    }

    Map<String, Integer> towerHitPoints = new HashMap<>();
    Map<String, Integer> advertised = new HashMap<>();
    for (BattleEntity entity : battle.getHolder().entities()) {
      if (entity instanceof TowerEntity tower) {
        towerHitPoints.put(tower.name(), tower.getHitPoints().getHitPoints());
        advertised.put(tower.name(), tower.getTargetView().getHitPoints());
        assertThat(tower.level())
            .as("%s stands at the reference level", tower.name())
            .isEqualTo(level);
        assertThat(tower.getHitPoints().getMaximum())
            .as("%s starts at its maximum", tower.name())
            .isEqualTo(tower.getHitPoints().getHitPoints());
        assertThat(tower.isRemovable()).as("%s stands", tower.name()).isFalse();
      }
    }
    assertThat(towerHitPoints)
        .as("the hit points of the six towers at level %d", level)
        .isEqualTo(expectedTowerHitPoints);
    assertThat(advertised)
        .as("what a weakest-first attacker would read from each tower")
        .isEqualTo(expectedTowerHitPoints);

    assertThat(knight.level()).isEqualTo(level);
    assertThat(knight.getDamage())
        .as("the damage of one Knight hit at level %d", level)
        .isEqualTo(reference.get("damage").asInt());
    // The Knight's own hit points are not in the reference; 690 at the first level scales by the
    // same rule as its damage, 79 to 202, so 690 becomes 1766.
    assertThat(knight.getHitPoints().getHitPoints()).isEqualTo(1766);
    assertThat(knight.getHitPoints().getMaximum()).isEqualTo(1766);
    assertThat(knight.getTargetView().getHitPoints()).isEqualTo(1766);
    assertThat(knight.getView().isAlive()).isTrue();
    assertThat(knight.isRemovable()).isFalse();
  }

  /** Places the reference's Knight at the reference's level, side and position on tick 0. */
  private static CharacterEntity deployKnight(Standard1v1Battle match, JsonNode reference) {
    return match.deploy(
        0,
        UnitDataMapper.toUnitData(
            Objects.requireNonNull(CardRegistry.get("knight"), "knight not found")),
        reference.get("level").asInt(),
        reference.get("side").asInt(),
        reference.get("deploy").get(0).asInt(),
        reference.get("deploy").get(1).asInt());
  }

  private static JsonNode load(String resource) {
    try (InputStream stream = BattleKillRunTest.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Missing test resource " + resource);
      }
      return MAPPER.readTree(stream);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + resource, e);
    }
  }
}
