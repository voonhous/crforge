package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.target.HitSink;
import org.crforge.core.pathfinding.target.SelectionChain;
import org.crforge.core.pathfinding.target.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drives the Musketeer run - a Musketeer deployed on the left, shooting the princess tower down
 * while the towers stand passive - through {@link Battle} and compares it with the reference run.
 *
 * <p>The Musketeer fires a projectile instead of hitting directly. The reference records every tick
 * of the 457-tick run, every launch with its start and aim, every impact with the damage dealt and
 * the tower's remaining hit points, and the tower's death. This test holds the battle to the part
 * of it a character's outside shows: the lock, the launch ticks as the ticks its hits fire on, the
 * tower's hit points falling only when a shot arrives, eight ticks after it left, the death on the
 * fifteenth impact and the removal in that tick, the whole run tick for tick, and the Musketeer's
 * own hit points at its row's rarity. {@link BattleProjectileFlightTest} holds the projectiles
 * themselves, and {@link TrajectoryRecorderTest} writes the run out and holds the file to the
 * reference byte for byte.
 *
 * <p>Reference tick {@code n} is battle tick {@code n}, as in {@link BattleKillRunTest}, and the
 * same one-tick deploying correction and end-of-step reference allowance apply.
 */
class BattleMusketeerRunTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  static final String REFERENCE = "/pathfinding/golden/musketeer_left_kill.json";

  /** The tower the Musketeer shoots down, as the reference names it. */
  static final String PRINCESS_TOWER = "PrincessTower_1_1";

  /** Reference tick the Musketeer stops and locks on. */
  static final int LOCK_TICK = 155;

  /** Reference tick the first shot leaves on: the fourteenth attack visit after the lock. */
  static final int FIRST_LAUNCH_TICK = 168;

  /** Reference ticks between two shots: the hit speed. */
  static final int SHOT_INTERVAL = 20;

  /** Reference ticks a shot flies for. */
  static final int FLIGHT_TICKS = 8;

  /** Reference tick the fifteenth impact kills the tower on. */
  static final int PRINCESS_DEATH_TICK = 456;

  @Test
  @DisplayName(
      "the Musketeer locks on at 155 and fires every twenty ticks from 168, hitting nothing directly")
  void theMusketeerLocksAndFiresOnTheReferenceTicks() {
    JsonNode reference = load(REFERENCE);
    List<Integer> launchTicks = new ArrayList<>();
    for (JsonNode event : reference.get("events")) {
      if (event.get("event").asText().equals("launch")) {
        launchTicks.add(event.get("tick").asInt());
      }
    }
    assertThat(launchTicks).as("the reference records fifteen launches").hasSize(15);
    assertThat(launchTicks.get(0)).isEqualTo(FIRST_LAUNCH_TICK);

    Standard1v1Battle match = new Standard1v1Battle(reference.get("level").asInt(), false);
    Battle battle = match.getBattle();
    CharacterEntity musketeer = deployMusketeer(match, reference);

    // Every hit the visit fires is recorded with its reference tick and the tower's hit points at
    // that moment, then handed on to the sink the character installed.
    List<Integer> hitTicks = new ArrayList<>();
    List<Integer> towerHitPointsAtTheHit = new ArrayList<>();
    int[] currentTick = {-1};
    SelectionChain selection = musketeer.getUnit().selection();
    HitSink inner = selection.getHitSink();
    TowerEntity tower = towerNamed(battle, PRINCESS_TOWER);
    selection.setHitSink(
        (target, sequenceIndex, extraTargets, last) -> {
          hitTicks.add(currentTick[0]);
          boolean nothingLanded = inner.hit(target, sequenceIndex, extraTargets, last);
          towerHitPointsAtTheHit.add(tower.getHitPoints().getHitPoints());
          return nothingLanded;
        });

    int lockStep = -1;
    for (int tick = 0; tick <= launchTicks.get(launchTicks.size() - 1); tick++) {
      currentTick[0] = tick;
      battle.step();
      if (lockStep < 0 && musketeer.getView().getState() == GridEntityState.ATTACKING) {
        lockStep = tick;
      }
    }

    assertThat(lockStep).as("the lock tick").isEqualTo(LOCK_TICK);
    assertThat(hitTicks).as("the ticks the shots leave on").containsExactlyElementsOf(launchTicks);
    assertThat(towerHitPointsAtTheHit)
        .as("a shot fired is still in the air: the tower stands where the last impact left it")
        .containsExactlyElementsOf(hitPointsBeforeEachLaunch(reference, launchTicks));
    assertThat(musketeer.getView().getX()).isEqualTo(3731);
    assertThat(musketeer.getView().getY()).isEqualTo(18054);
    assertThat(referenceName(musketeer)).isEqualTo(PRINCESS_TOWER);
  }

  /**
   * The tower's hit points the reference has at each launch: what the last impact before the launch
   * left, or the full tower before the first impact.
   */
  private static List<Integer> hitPointsBeforeEachLaunch(
      JsonNode reference, List<Integer> launchTicks) {
    List<Integer> before = new ArrayList<>();
    for (int launchTick : launchTicks) {
      int hp = towerMaximum(reference);
      for (JsonNode event : reference.get("events")) {
        if (event.get("event").asText().equals("impact")
            && event.get("tick").asInt() < launchTick) {
          hp = event.get("hp").asInt();
        }
      }
      before.add(hp);
    }
    return before;
  }

  @Test
  @DisplayName(
      "every shot takes the tower down to the reference's remaining hit points, eight ticks after it left")
  void everyImpactTakesTheTowerDownToTheReferenceHitPoints() {
    JsonNode reference = load(REFERENCE);
    List<String> expected = new ArrayList<>();
    for (JsonNode event : reference.get("events")) {
      String kind = event.get("event").asText();
      if (kind.equals("impact")) {
        expected.add(
            "%d impact %s %d"
                .formatted(
                    event.get("tick").asInt(),
                    event.get("target").asText(),
                    event.get("hp").asInt()));
      } else if (kind.equals("death")) {
        expected.add(
            "%d death %s".formatted(event.get("tick").asInt(), event.get("target").asText()));
      }
    }
    assertThat(expected).as("fifteen impacts and one death").hasSize(16);
    assertThat(expected.get(0))
        .isEqualTo(
            "%d impact %s %d"
                .formatted(FIRST_LAUNCH_TICK + FLIGHT_TICKS, PRINCESS_TOWER, 3052 - 217));

    Standard1v1Battle match = new Standard1v1Battle(reference.get("level").asInt(), false);
    Battle battle = match.getBattle();
    deployMusketeer(match, reference);

    // Every tick the tower's hit points fall is one impact, recorded with the tick and what the
    // tower stands at afterwards; reaching zero is its death, a second event on the same tick.
    TowerEntity tower = towerNamed(battle, PRINCESS_TOWER);
    int standing = tower.getHitPoints().getHitPoints();
    List<String> events = new ArrayList<>();
    for (int tick = 0; tick <= PRINCESS_DEATH_TICK; tick++) {
      battle.step();
      int now = tower.getHitPoints().getHitPoints();
      if (now != standing) {
        events.add("%d impact %s %d".formatted(tick, tower.name(), now));
        if (now == 0) {
          events.add("%d death %s".formatted(tick, tower.name()));
        }
        standing = now;
      }
    }

    assertThat(events)
        .as("every impact on the tower, and its death")
        .containsExactlyElementsOf(expected);
    assertThat(tower.getHitPoints().getHitPoints()).isZero();
    assertThat(tower.getView().isAlive()).isFalse();
    assertThat(battle.getHolder().entities())
        .as("the tower has left the holder in the tick it died")
        .doesNotContain(tower);
  }

  @Test
  @DisplayName("the whole run matches the reference tick for tick, the tower's death included")
  void theWholeRunMatchesTheReferenceTickForTick() {
    JsonNode reference = load(REFERENCE);
    List<JsonNode> records = records(reference);
    assertThat(records).as("the reference is the whole run").hasSize(PRINCESS_DEATH_TICK + 1);

    Standard1v1Battle match = new Standard1v1Battle(reference.get("level").asInt(), false);
    Battle battle = match.getBattle();
    CharacterEntity musketeer = deployMusketeer(match, reference);

    TowerEntity tower = towerNamed(battle, PRINCESS_TOWER);
    for (int i = 0; i < records.size(); i++) {
      battle.step();
      JsonNode record = records.get(i);
      String where = "reference tick " + record.get("tick").asInt();

      assertThat(musketeer.getId())
          .as("%s: the Musketeer follows the six towers in the character band", where)
          .isEqualTo(5000006);
      assertThat(musketeer.getView().getX()).as("%s x", where).isEqualTo(record.get("x").asInt());
      assertThat(musketeer.getView().getY()).as("%s y", where).isEqualTo(record.get("y").asInt());
      assertThat(musketeer.getView().getState())
          .as("%s state", where)
          .isEqualTo(BattleGoldenTrajectoryTest.expectedState(records, i));
      assertThat(referenceName(musketeer))
          .as("%s reference", where)
          .isEqualTo(expectedReference(record));
      assertThat(musketeer.getUnit().movement().getRoute().size())
          .as("%s route length", where)
          .isEqualTo(record.get("route").asInt());
      if (record.get("speed").isNull()) {
        assertThat(musketeer.getSpeedBudget()).as("%s: deploying", where).isZero();
      } else {
        assertThat(musketeer.getSpeedBudget())
            .as("%s movement budget", where)
            .isEqualTo(record.get("speed").asInt());
      }
      assertThat(referenceHitPoints(musketeer))
          .as("%s hit points of the reference", where)
          .isEqualTo(expectedReference(record) == null ? null : record.get("hp").asInt());
    }

    assertThat(battle.getTick()).isEqualTo(records.size());
    assertThat(tower.getHitPoints().getHitPoints()).as("the tower is at zero").isZero();
    assertThat(battle.getHolder().entities())
        .as("the tower has left the holder in the tick it died")
        .doesNotContain(tower);
    // The removal took the reference with it and started the attack finish countdown.
    assertThat(musketeer.getView().getState()).isEqualTo(GridEntityState.ATTACKING);
    assertThat(referenceName(musketeer)).isNull();
    assertThat(musketeer.getUnit().targeting().getTargetLostTimerMs()).isEqualTo(1);
  }

  @Test
  @DisplayName("the Musketeer stands at its row's hit points and carries its row's damage")
  void theMusketeerStandsAtItsRowsHitPoints() {
    JsonNode reference = load(REFERENCE);
    Standard1v1Battle match = new Standard1v1Battle(reference.get("level").asInt(), false);
    CharacterEntity musketeer = deployMusketeer(match, reference);

    // The row is Common although the card is Rare, so the level packs against the Common table:
    // 282 at the first level is 721 at level 11, as 79 is 202 for the Knight.
    assertThat(musketeer.level()).isEqualTo(reference.get("level").asInt());
    assertThat(musketeer.getHitPoints().getMaximum()).isEqualTo(721);
    assertThat(musketeer.getHitPoints().getHitPoints()).isEqualTo(721);
    assertThat(musketeer.getDamage())
        .as("the damage column of a unit that fires is its projectile's, at the level")
        .isEqualTo(reference.get("damage").asInt());
  }

  /** The reference's records as objects keyed by its field names. */
  static List<JsonNode> records(JsonNode reference) {
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
  static String expectedReference(JsonNode record) {
    if (record.get("ref").isNull() || record.get("hp").asInt() == 0) {
      return null;
    }
    return record.get("ref").asText();
  }

  private static int towerMaximum(JsonNode reference) {
    for (JsonNode tower : reference.get("towers")) {
      if (tower.get("name").asText().equals(PRINCESS_TOWER)) {
        return tower.get("hp").asInt();
      }
    }
    throw new IllegalStateException("no " + PRINCESS_TOWER + " in the reference");
  }

  /** The tower of the given name, as the battle holds it. */
  static TowerEntity towerNamed(Battle battle, String name) {
    for (BattleEntity entity : battle.getHolder().entities()) {
      if (entity instanceof TowerEntity tower && tower.name().equals(name)) {
        return tower;
      }
    }
    throw new IllegalStateException("No tower named " + name);
  }

  static String referenceName(CharacterEntity unit) {
    TargetView reference = unit.getUnit().targeting().getReference();
    return reference == null ? null : reference.name();
  }

  static Integer referenceHitPoints(CharacterEntity unit) {
    TargetView reference = unit.getUnit().targeting().getReference();
    return reference == null ? null : reference.getHitPoints();
  }

  /** Places the reference's Musketeer at the reference's level, side and position on tick 0. */
  static CharacterEntity deployMusketeer(Standard1v1Battle match, JsonNode reference) {
    return match.deploy(
        0,
        GameData.unit("Musketeer"),
        reference.get("level").asInt(),
        reference.get("side").asInt(),
        reference.get("deploy").get(0).asInt(),
        reference.get("deploy").get(1).asInt());
  }

  static JsonNode load(String resource) {
    try (InputStream stream = BattleMusketeerRunTest.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Missing test resource " + resource);
      }
      return MAPPER.readTree(stream);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + resource, e);
    }
  }
}
