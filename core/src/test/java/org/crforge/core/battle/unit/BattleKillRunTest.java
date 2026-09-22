package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.crforge.core.battle.Battle;
import org.crforge.core.card.UnitDataMapper;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.target.HitSink;
import org.crforge.core.pathfinding.target.SelectionChain;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drives the kill run - a Knight deployed on the left, attacking the princess tower and then the
 * king tower until both are gone - through {@link Battle} and compares it with the reference run.
 *
 * <p>The reference records every hit with its tick, target, damage and the target's remaining hit
 * points, and both deaths. What this test holds the battle to grows with the milestone; today it is
 * the timing of the hits on the princess tower: the first lands nine ticks after the lock, because
 * the attack time is credited the whole load when the attack starts, and the rest follow at the hit
 * speed. Nothing loses hit points yet, so the tower survives its sixteenth hit and the unit keeps
 * attacking it; the death and the walk to the king tower are the next steps.
 *
 * <p>Reference tick {@code n} is battle step {@code n + 1}, as in {@link
 * BattleGoldenTrajectoryTest}.
 */
class BattleKillRunTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The tower the Knight attacks first, as the reference names it. */
  private static final String PRINCESS_TOWER = "PrincessTower_1_1";

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

    Standard1v1Battle match = new Standard1v1Battle();
    Battle battle = match.getBattle();
    CharacterEntity knight =
        match.deploy(
            0,
            UnitDataMapper.toUnitData(
                Objects.requireNonNull(CardRegistry.get("knight"), "knight not found")
                    .getUnitStats()),
            reference.get("side").asInt(),
            reference.get("deploy").get(0).asInt(),
            reference.get("deploy").get(1).asInt());

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

    // Nothing takes damage yet, so the tower stands and the Knight keeps attacking it.
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.ATTACKING);
    assertThat(knight.getUnit().targeting().getReference().name()).isEqualTo(PRINCESS_TOWER);
    assertThat(knight.getUnit().targeting().isHitStarted()).isTrue();
    assertThat(knight.getUnit().targeting().getLoadTimerMs())
        .as("the last hit reloaded the countdown")
        .isEqualTo(700);
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
