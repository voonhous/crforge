package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.data.ActionRows;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.battle.spawn.SpawnPlacement;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.math.FixedMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An action that spawns characters on a ring around a character: the evolved Witch's interval spawn
 * (Count 4, SpawnRadius 2000, no deploy, not a death spawn). The ring is the plain one, child
 * {@code i} of {@code n} at {@code (n - 1 - i) * 360 / n}; a source whose row sets an angle shift
 * turns it by that shift and the angle it faces; and the source's running actions hear of every
 * child, so the Witch's soul drain counts the ring's skeletons as hers. Each scene adds the spawn
 * row to the configured actions and schedules it on a unit.
 */
class BattleCharacterRingSpawnTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /**
   * The added row: four Witch_EV1_Healing_Skeleton (which the soul drain counts) on a 2000 ring, as
   * Witch_EV1_Interval_Spawn makes its skeletons.
   */
  private static final String RING = "Test_Ring_Spawn";

  /** The skeleton row the evolved Witch's soul drain counts, which the ring makes. */
  private static final String COUNTED = "Witch_EV1_Healing_Skeleton";

  /** The tick the row is scheduled on: the units have deployed and started their actions. */
  private static final int SCHEDULED = 30;

  /** The buff a soul that reaches the evolved Witch applies to her. */
  private static final String HEAL_BUFF = "Witch_EV1_Heal_Buff";

  /** Witch_Soul_Drain's ConstantFlightDuration 1000 ms, in ticks. */
  private static final int FLIGHT_TICKS = 20;

  /** The configured tables with the ring row added to the actions. */
  private static GameTables withRing(Path folder) throws IOException {
    return GameData.altered(
        folder,
        "actions",
        rows -> {
          ObjectNode row = new ObjectMapper().createObjectNode();
          row.put("class", "LogicActionSpawnToLocationData");
          row.put("ClassType", "ActionSpawnToLocation");
          row.putObject("fields")
              .put("ClassType", "ActionSpawnToLocation")
              .put("Count", 4)
              .put("IsDeathSpawn", false)
              .put("SpawnData", "Witch_EV1_Healing_Skeleton")
              .put("SpawnRadius", 2000)
              .put("SpawnType", "CharacterType")
              .put("UseDeploy", false);
          rows.set(RING, row);
        });
  }

  /** One child of the ring as it is made: where it stands, and where its source stood. */
  private record Made(CharacterEntity child, int x, int y, int sourceX, int sourceY, int turn) {}

  /** Deploys a unit, schedules the ring row on it and records the ring's children as made. */
  private static List<Made> ringOf(
      Standard1v1Battle match, GameTables tables, CharacterEntity unit) {
    BattleWorld world = match.getWorld();
    BattleAction ring = new ActionRows(tables, world.getRecords()).build(RING, world.binding(unit));
    match.scheduleAction(SCHEDULED, unit, ring);
    List<Made> made = new ArrayList<>();
    world.addObserver(
        new WorldObserver() {
          @Override
          public void characterSpawned(
              int tick, SpawnHost source, CharacterEntity child, int x, int y) {
            // The ring's children are made on the tick the row is scheduled for.
            if (source == unit && tick == SCHEDULED) {
              int shift = unit.getData().spawnAngleShift();
              int turn =
                  shift == 0
                      ? 0
                      : shift
                          + FixedMath.angleOfVector(
                              unit.getView().getDirX(), unit.getView().getDirY());
              made.add(new Made(child, x, y, unit.getView().getX(), unit.getView().getY(), turn));
            }
          }
        });
    return made;
  }

  @Test
  @DisplayName(
      "four children stand on the plain ring around the Witch, the last straight along the width,"
          + " walking at once and targetable")
  void theRingAroundACharacterIsThePlainRing(@TempDir Path folder) throws IOException {
    GameTables tables = withRing(folder);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity witch =
        match.deploy(0, match.getWorld().getRecords().unit("Witch_EV1"), LEVEL, 0, 14500, 9000);
    List<Made> made = ringOf(match, tables, witch);
    for (int tick = 0; tick <= SCHEDULED + 1; tick++) {
      match.getBattle().step();
    }

    assertThat(made).as("one spawn of four").hasSize(4);
    Made first = made.get(0);
    assertThat(made)
        .as("child i at (3 - i) * 90 degrees, 2000 from where the Witch stands")
        .extracting(m -> (m.x() - first.sourceX()) + "," + (m.y() - first.sourceY()))
        .containsExactly("0,-2000", "-2000,0", "0,2000", "2000,0");
    assertThat(made)
        .as("no deploy is asked and the row is not a death spawn")
        .allSatisfy(
            m -> {
              assertThat(m.child().getView().getState()).isNotEqualTo(GridEntityState.DEPLOYING);
              assertThat(m.child().isSpawnImmune()).isFalse();
            });
  }

  @Test
  @DisplayName(
      "a source whose row sets an angle shift turns the ring by the shift and the angle it faces")
  void anAngleShiftTurnsTheRing(@TempDir Path folder) throws IOException {
    GameTables tables = withRing(folder);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    // The Night Witch's row sets SpawnAngleShift 90.
    CharacterEntity nightWitch =
        match.deploy(0, match.getWorld().getRecords().unit("DarkWitch"), LEVEL, 0, 3500, 9000);
    List<Made> made = ringOf(match, tables, nightWitch);
    for (int tick = 0; tick <= SCHEDULED + 1; tick++) {
      match.getBattle().step();
    }

    assertThat(made).hasSize(4);
    assertThat(made.get(0).turn()).as("the shift and the facing").isNotZero();
    for (int i = 0; i < made.size(); i++) {
      Made m = made.get(i);
      int[] at = SpawnPlacement.ring(m.sourceX(), m.sourceY(), i, 4, 4, m.turn(), 2000);
      assertThat(new int[] {m.x(), m.y()}).as("child %d", i).containsExactly(at);
    }
  }

  @Test
  @DisplayName(
      "the Witch's running actions hear of each child of the ring, so each of them that dies heals"
          + " her a soul's flight later, as her spawner's skeletons do")
  void theSourcesActionsHearOfEachChild(@TempDir Path folder) throws IOException {
    GameTables tables = withRing(folder);
    // The towers shoot: the enemy princess towers kill the skeletons that reach them.
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, true);
    BattleWorld world = match.getWorld();
    CharacterEntity witch =
        match.deploy(0, world.getRecords().unit("Witch_EV1"), LEVEL, 0, 14500, 14000);
    List<Made> ring = ringOf(match, tables, witch);
    List<CharacterEntity> hers = new ArrayList<>();
    world.addObserver(
        new WorldObserver() {
          @Override
          public void characterSpawned(
              int tick, SpawnHost source, CharacterEntity child, int x, int y) {
            if (source == witch) {
              hers.add(child);
            }
          }
        });
    Set<CharacterEntity> dead = new HashSet<>();
    List<Integer> deaths = new ArrayList<>();
    List<Integer> ringDeaths = new ArrayList<>();
    List<Integer> heals = new ArrayList<>();
    for (int tick = 0; tick < 1200 && !witch.isRemovable(); tick++) {
      match.getBattle().step();
      for (CharacterEntity skeleton : hers) {
        if (skeleton.getHitPoints().getHitPoints() <= 0 && dead.add(skeleton)) {
          // Her drain's troop filter counts only Witch_EV1_Healing_Skeleton, not the skeletons
          // of her interval spawn.
          if (skeleton.getData().name().equals(COUNTED)) {
            deaths.add(tick);
          }
          if (ring.stream().anyMatch(m -> m.child() == skeleton)) {
            ringDeaths.add(tick);
          }
        }
      }
      // Each soul that reaches her applies her heal buff, for one tick.
      if (witch.getBuffs().carries(HEAL_BUFF)) {
        heals.add(tick);
      }
    }

    assertThat(ring).as("the ring is made").hasSize(4);
    assertThat(ringDeaths).as("the ring's skeletons die while she lives").hasSize(4);
    assertThat(heals)
        .as(
            "her heal buff listed a soul's flight after each death of hers, the ring's among"
                + " them; its heal shows on the next tick")
        .containsExactlyElementsOf(deaths.stream().map(t -> t + FLIGHT_TICKS).toList());
  }

  @Test
  @DisplayName("a ring around a source whose row attaches its children is refused")
  void aRingAroundAnAttachingSourceIsRefused(@TempDir Path folder) throws IOException {
    GameTables tables = withRing(folder);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    // The Goblin Giant's row sets SpawnAttach; it is played by its card, which makes its riders.
    match.play(0, match.getWorld().getRecords().card("GoblinGiant"), LEVEL, 0, 3500, 9000, "Blue");
    match.getBattle().step();
    CharacterEntity giant =
        match.getWorld().present().stream()
            .filter(e -> e.getData().name().equals("GoblinGiant"))
            .map(CharacterEntity.class::cast)
            .findFirst()
            .orElseThrow();
    ringOf(match, tables, giant);

    assertThatThrownBy(
            () -> {
              for (int tick = 0; tick <= SCHEDULED + 1; tick++) {
                match.getBattle().step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(
            "spawning Witch_EV1_Healing_Skeleton asks for a ring around a character whose row attaches its"
                + " children, which is not established");
  }
}
