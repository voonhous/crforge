package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.EntityType;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpawnerDeathIntegrationTest {

  private GameEngine engine;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    engine = new GameEngine();
    engine.setMatch(new Standard1v1Match());
    engine.initMatch();
  }

  @Test
  void tombstoneShouldSpawnSkeletonsOnDeath() {
    // 1. Create Tombstone with death spawn
    TroopStats skeletonStats = TroopStats.builder()
        .name("Skeleton")
        .health(67)
        .damage(67)
        .speed(1.0f)
        .build();

    // Set a high spawnPauseTime and initialize currentTimer
    // so it doesn't cause a wave spawn immediately
    SpawnerComponent spawner = SpawnerComponent.builder()
        .deathSpawnCount(4)
        .spawnStats(skeletonStats)
        .spawnPauseTime(10.0f)
        .currentTimer(10.0f)
        .build();

    Building tombstone = Building.builder()
        .name("Tombstone")
        .team(Team.BLUE)
        .position(new Position(10f, 10f))
        .health(new Health(200))
        .movement(new Movement(0f, 0f, 1.0f, 1.0f, MovementType.BUILDING))
        .lifetime(30f)
        .spawner(spawner)
        .build();

    tombstone.onSpawn();
    engine.spawn(tombstone);
    engine.tick(); // Process pending spawn

    // 2. Kill Tombstone
    tombstone.getHealth().takeDamage(1000);

    // 3. Tick engine (Building.update runs -> GameState.processDeaths runs)
    engine.tick();

    // 4. Tick again to process pending spawns from death
    engine.tick();

    // 5. Verify Skeletons spawned
    long skeletonCount = engine.getGameState().getAliveEntities().stream()
        .filter(e -> e.getEntityType() == EntityType.TROOP)
        .filter(e -> "Skeleton".equals(e.getName()))
        .count();

    assertThat(skeletonCount).as("Should spawn 4 skeletons on death").isEqualTo(4);
  }

  @Test
  void buildingShouldDieWhenLifetimeExpires() {
    // 1. Create Building with short lifetime
    Building cannon = Building.builder()
        .name("Cannon")
        .team(Team.BLUE)
        .position(new Position(10f, 10f))
        .health(new Health(200))
        .movement(new Movement(0f, 0f, 1.0f, 1.0f, MovementType.BUILDING))
        .lifetime(1.0f) // 1 second lifetime
        .deployTime(0f)
        .build();

    cannon.onSpawn();
    engine.spawn(cannon);
    engine.tick();

    // 2. Run engine past lifetime
    engine.runSeconds(1.5f);

    // 3. Process potential death frames
    engine.tick();

    // 4. Verify dead
    assertThat(cannon.isAlive()).as("Building should be dead after lifetime expires").isFalse();
    assertThat(engine.getGameState().getAliveEntities()).doesNotContain(cannon);
  }
}
