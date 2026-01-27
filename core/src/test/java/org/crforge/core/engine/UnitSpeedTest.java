package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.crfoge.data.card.CardRegistry;
import org.crforge.core.arena.Arena;
import org.crforge.core.card.Card;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.physics.BasePathfinder;
import org.crforge.core.physics.PhysicsSystem;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests that units travel correct distances over time based on their configured speeds.
 */
class UnitSpeedTest {

  private PhysicsSystem physicsSystem;
  private GameState gameState;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Arena arena = Arena.standard();
    gameState = new GameState();
    physicsSystem = new PhysicsSystem(arena, new BasePathfinder());
  }

  @Test
  void knightShouldMoveAtSpeed60() {
    // 60 / 60 = 1.0 tiles/sec
    verifyUnitSpeed("knight", 1.0f);
  }

  private void verifyUnitSpeed(String cardId, float expectedSpeedTilesPerSec) {
    Card card = CardRegistry.get(cardId);
    assertThat(card).isNotNull();

    TroopStats stats = card.getTroops().get(0);

    // Spawn at (3.5, 5.0) -> moving straight up towards bridge
    float startX = 3.5f;
    float startY = 5.0f;

    Troop unit = Troop.builder()
        .name(stats.getName())
        .team(Team.BLUE)
        .position(new Position(startX, startY))
        .health(new Health(stats.getHealth()))
        .movement(new Movement(stats.getSpeed(), stats.getMass(), stats.getSize(),
            stats.getMovementType()))
        .combat(Combat.builder().build()) // Dummy combat
        .deployTime(0f) // Instant deploy for test
        .build();

    unit.onSpawn();
    gameState.spawnEntity(unit);
    gameState.processPending();

    Position initialPos = unit.getPosition().copy();

    // Run simulation for 2 seconds
    float runTime = 2.0f;
    float deltaTime = 1.0f / 30.0f;
    int ticks = (int) (runTime * 30);

    for (int i = 0; i < ticks; i++) {
      // Update entity (deploy timer, etc)
      unit.update(deltaTime);
      // Update physics (movement)
      physicsSystem.update(gameState.getAliveEntities(), deltaTime);
    }

    Position finalPos = unit.getPosition();

    // Calculate distance moved
    float distance = initialPos.distanceTo(finalPos);
    float expectedDistance = expectedSpeedTilesPerSec * runTime;

    // Verify speed matches expected
    assertThat(distance).isCloseTo(expectedDistance, within(0.05f));
  }
}
