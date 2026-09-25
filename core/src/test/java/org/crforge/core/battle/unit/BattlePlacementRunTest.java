package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.deploy.DeployCard;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.card.UnitDataMapper;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Plays the placement references through {@link Battle}: every card play is a place-card command
 * due on its tick, and the battle is held to the reference tick for tick - every unit's position,
 * state, reference and hit points, every launch, impact, hit and death, and every projectile
 * position.
 *
 * <p>A unit's record is taken after its state visit, as the battle's step ends, except that the
 * reference named on the tick it dies is still named in the record while the step's closing cleanup
 * has dropped it, and that the reference of a unit leaving in that cleanup is not compared.
 */
class BattlePlacementRunTest {

  static final List<String> REFERENCES =
      List.of(
          "barbarians_left",
          "barbarians_edge",
          "skeleton_army_edge",
          "knight_side1",
          "deploy_refused",
          "two_knights",
          "skeleton_army_bridge",
          "skeleton_army_corner");

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "barbarians_left",
        "barbarians_edge",
        "skeleton_army_edge",
        "knight_side1",
        "deploy_refused",
        "two_knights",
        "skeleton_army_bridge",
        "skeleton_army_corner"
      })
  void theRunMatchesTheReferenceTickForTick(String name) {
    JsonNode reference = BattleMusketeerRunTest.load("/pathfinding/golden/" + name + ".json");
    Standard1v1Battle match = new Standard1v1Battle(reference.get("tower_level").asInt());
    Battle battle = match.getBattle();
    playAll(match, reference);

    int[] currentTick = {-1};
    List<String> events = new ArrayList<>();
    List<String> positions = new ArrayList<>();
    match.getWorld().addObserver(BattleTowerRunTest.eventCollector(currentTick, events));
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void afterPostHooks(
                  int tick, List<WorldEntity> present, List<ProjectileEntity> inFlight) {
                for (ProjectileEntity p : inFlight) {
                  if (!p.isReleased()) {
                    positions.add(
                        "[%d,%d,%d,%d,%d]"
                            .formatted(currentTick[0], p.getId(), p.getX(), p.getY(), p.getZ()));
                  }
                }
              }
            });

    Map<Integer, List<JsonNode>> records = BattleTowerRunTest.otherRecordsByTick(reference);
    int lastTick = records.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
    for (JsonNode event : reference.get("events")) {
      lastTick = Math.max(lastTick, event.get("tick").asInt());
    }
    // A shot still in flight when the run ends is recorded past the last record and event.
    for (JsonNode p : reference.path("projectiles")) {
      lastTick = Math.max(lastTick, p.get(0).asInt());
    }
    Map<String, CharacterEntity> units = new HashMap<>();
    for (int tick = 0; tick <= lastTick; tick++) {
      currentTick[0] = tick;
      battle.step();
      for (BattleEntity entity : battle.getHolder().entities()) {
        if (entity instanceof CharacterEntity c) {
          units.putIfAbsent(c.name(), c);
        }
      }
      for (JsonNode record : records.getOrDefault(tick, List.of())) {
        CharacterEntity unit = units.get(record.get("name").asText());
        assertThat(unit).as("tick %d: %s is in the battle", tick, record.get("name")).isNotNull();
        String where = "tick " + tick + ": " + unit.name();
        assertThat(unit.getView().getX()).as("%s x", where).isEqualTo(record.get("x").asInt());
        assertThat(unit.getView().getY()).as("%s y", where).isEqualTo(record.get("y").asInt());
        assertThat(unit.getView().getState())
            .as("%s state", where)
            .isEqualTo(record.get("state").asInt());
        String recorded = record.get("ref").isNull() ? null : record.get("ref").asText();
        boolean stillThere =
            battle.getHolder().entities().stream()
                .anyMatch(e -> e instanceof WorldEntity w && w.name().equals(recorded));
        // The record is taken before the closing cleanup. A unit that leaves in it has no
        // reference to observe afterwards, so its reference is not compared on that tick.
        boolean unitLeft = !battle.getHolder().entities().contains(unit);
        if (!unitLeft) {
          assertThat(BattleMusketeerRunTest.referenceName(unit))
              .as("%s reference", where)
              .isEqualTo(stillThere ? recorded : null);
        }
        assertThat(unit.getHitPoints().getHitPoints())
            .as("%s own hit points", where)
            .isEqualTo(record.get("own_hp").asInt());
      }
    }

    List<String> expected = new ArrayList<>();
    for (JsonNode event : reference.get("events")) {
      expected.add(BattleTowerRunTest.eventLine(event));
    }
    assertThat(events)
        .as("every launch, impact, hit and death")
        .containsExactlyElementsOf(expected);
    List<String> expectedPositions = new ArrayList<>();
    // A run without projectiles lists none.
    for (JsonNode p : reference.path("projectiles")) {
      expectedPositions.add(p.toString());
    }
    assertThat(positions)
        .as("every projectile position")
        .containsExactlyElementsOf(expectedPositions);
  }

  /** Queues every card play of the reference on its tick. */
  static void playAll(Standard1v1Battle match, JsonNode reference) {
    for (JsonNode command : reference.get("commands")) {
      String cardId = command.get("card").asText().toLowerCase(Locale.ROOT);
      DeployCard card =
          UnitDataMapper.toDeployCard(Objects.requireNonNull(CardRegistry.get(cardId), cardId));
      match.play(
          command.get("tick").asInt(),
          card,
          reference.get("level").asInt(),
          command.get("side").asInt(),
          command.get("point").get(0).asInt(),
          command.get("point").get(1).asInt(),
          command.get("name").asText());
    }
  }
}
