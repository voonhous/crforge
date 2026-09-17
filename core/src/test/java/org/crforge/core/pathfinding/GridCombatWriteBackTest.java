package org.crforge.core.pathfinding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import org.crforge.core.card.Card;
import org.crforge.core.component.Combat;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.testing.SimHarness;
import org.crforge.core.testing.SimSystems;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the grid system writes back onto a troop's combat component, with no combat system running
 * beside it.
 *
 * <p>The target lock is raised by two places in the simulation: the combat system, when a troop it
 * drives engages, and the grid system, on every tick its targeting visit has put a troop into the
 * attacking state. A test that runs both cannot say which of the two wrote the flag, so this one
 * leaves the combat system out of the harness entirely: the only writer left is the grid system's
 * own write-back.
 *
 * <p>The run is the standard left-lane Knight: put down at (3500, 10000) against the three red
 * crown towers, in the order a match places them, it walks the left lane and reaches the left
 * princess tower's attack range on tick 236, at (3731, 22854) - the same tick and position the
 * golden left-lane trajectory records.
 */
class GridCombatWriteBackTest {

  /** Level both the towers and the Knight are scaled to. */
  private static final int LEVEL = 11;

  /** The tick the Knight enters the attacking state on, counted from the tick it was spawned in. */
  private static final int LOCK_TICK = 236;

  /** Where the Knight is put down. */
  private static final int KNIGHT_X = 3500;

  private static final int KNIGHT_Y = 10_000;

  /** The red king tower's place on the standard arena, in game units. */
  private static final int RED_KING_X = 9000;

  private static final int RED_KING_Y = 29_000;

  /** The red princess towers' place on the standard arena, in game units. */
  private static final int RED_LEFT_PRINCESS_X = 3500;

  private static final int RED_RIGHT_PRINCESS_X = 14_500;

  private static final int RED_PRINCESS_Y = 25_500;

  @Test
  @DisplayName("the grid system raises the target lock with no combat system running")
  void theGridSystemIsTheOnlyWriterOfTheTargetLock() {
    // The builder resets the entity id counter, so every entity of the run is created after it.
    SimHarness.Builder builder = SimHarness.create();
    Tower king = Tower.createCrownTower(Team.RED, RED_KING_X, RED_KING_Y, LEVEL);
    Tower leftPrincess =
        Tower.createPrincessTower(Team.RED, RED_LEFT_PRINCESS_X, RED_PRINCESS_Y, LEVEL);
    Tower rightPrincess =
        Tower.createPrincessTower(Team.RED, RED_RIGHT_PRINCESS_X, RED_PRINCESS_Y, LEVEL);
    SimHarness sim =
        builder
            .withSystems(SimSystems.PHYSICS, SimSystems.SPAWNER)
            .withGridPathfinding()
            // Placement order matters: the default target selection ranks the opposing side's
            // towers in the order they were registered, king first.
            .spawn(king)
            .spawn(leftPrincess)
            .spawn(rightPrincess)
            .deployed()
            .build();

    assertThat(sim.combatSystem())
        .as("no combat system runs, so nothing else can raise the lock")
        .isNull();
    assertThat(sim.gridPathfindingSystem()).as("the harness runs the grid rules").isNotNull();

    Card knight = Objects.requireNonNull(CardRegistry.get("knight"), "knight not found");
    sim.spawnerSystem()
        .spawnUnit(
            KNIGHT_X,
            KNIGHT_Y,
            Team.BLUE,
            knight.getUnitStats(),
            LEVEL,
            knight.getUnitStats().getDeployTime());

    sim.tick(LOCK_TICK - 1);
    Troop troop = sim.troop("Knight");
    Combat combat = troop.getCombat();
    assertThat(troop.getGridUnitState().getEntity().getState())
        .as("the Knight is still walking on the tick before it locks on")
        .isEqualTo(GridEntityState.MOVING);
    assertThat(combat.isTargetLocked()).as("nothing has locked yet").isFalse();

    sim.tick();

    assertThat(troop.getGridUnitState().getEntity().getState())
        .as("the targeting visit put the Knight into the attacking state")
        .isEqualTo(GridEntityState.ATTACKING);
    assertThat(combat.getCurrentTarget())
        .as("the reference was written back as the combat target")
        .isSameAs(leftPrincess);
    assertThat(combat.isTargetLocked()).as("the grid system raised the lock").isTrue();
    assertThat(troop.getPosition().getX()).isEqualTo(3731);
    assertThat(troop.getPosition().getY()).isEqualTo(22_854);
  }
}
