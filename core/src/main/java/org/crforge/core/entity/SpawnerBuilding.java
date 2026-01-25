package org.crforge.core.entity;

import java.util.function.Consumer;
import lombok.Getter;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;

@Getter
public class SpawnerBuilding extends Building implements Spawnable {

  private final float spawnInterval;
  private final int deathSpawnCount;
  private final TroopStats spawnStats;

  private float spawnTimer;
  private Consumer<Entity> spawnCallback;

  protected SpawnerBuilding(SpawnerBuilder builder) {
    super(builder);
    this.spawnInterval = builder.spawnInterval;
    this.deathSpawnCount = builder.deathSpawnCount;
    this.spawnStats = builder.spawnStats;
    this.spawnTimer = spawnInterval;
  }

  public static SpawnerBuilder builder() {
    return new SpawnerBuilder();
  }

  @Override
  public void setSpawnCallback(Consumer<Entity> callback) {
    this.spawnCallback = callback;
  }

  @Override
  public void update(float deltaTime) {
    super.update(deltaTime);

    if (dead) {
      return;
    }

    // Periodic Spawning
    if (spawnInterval > 0 && spawnStats != null && spawnCallback != null) {
      spawnTimer -= deltaTime;
      if (spawnTimer <= 0) {
        spawnTimer += spawnInterval;
        spawnUnit(0, 0); // Spawn at center
      }
    }
  }

  @Override
  public void onDeath() {
    super.onDeath();

    // Death Spawning
    if (deathSpawnCount > 0 && spawnStats != null && spawnCallback != null) {
      for (int i = 0; i < deathSpawnCount; i++) {
        // Simple random spread for death spawns
        float offsetX = (float) (Math.random() - 0.5f) * getSize();
        float offsetY = (float) (Math.random() - 0.5f) * getSize();
        spawnUnit(offsetX, offsetY);
      }
    }
  }

  private void spawnUnit(float offsetX, float offsetY) {
    float spawnX = getPosition().getX() + offsetX;
    float spawnY = getPosition().getY() + offsetY;

    Troop troop = createTroopFromStats(spawnStats, spawnX, spawnY);
    spawnCallback.accept(troop);
  }

  private Troop createTroopFromStats(TroopStats stats, float x, float y) {
    Combat troopCombat = Combat.builder()
        .damage(stats.getDamage())
        .range(stats.getRange())
        .sightRange(stats.getSightRange())
        .attackCooldown(stats.getAttackCooldown())
        .aoeRadius(stats.getAoeRadius())
        .targetType(stats.getTargetType())
        .ranged(stats.isRanged())
        .build();

    return Troop.builder()
        .name(stats.getName())
        .team(getTeam())
        .position(x, y)
        .maxHealth(stats.getHealth())
        .speed(stats.getSpeed())
        .mass(stats.getMass())
        .size(stats.getSize())
        .movementType(stats.getMovementType())
        .combat(troopCombat)
        .deployTime(0.5f) // Fast deploy for spawned units
        .build();
  }

  public static class SpawnerBuilder extends Building.Builder {

    private float spawnInterval = 0;
    private int deathSpawnCount = 0;
    private TroopStats spawnStats = null;

    public SpawnerBuilder spawnInterval(float spawnInterval) {
      this.spawnInterval = spawnInterval;
      return this;
    }

    public SpawnerBuilder deathSpawnCount(int deathSpawnCount) {
      this.deathSpawnCount = deathSpawnCount;
      return this;
    }

    public SpawnerBuilder spawnStats(TroopStats spawnStats) {
      this.spawnStats = spawnStats;
      return this;
    }

    @Override
    public SpawnerBuilding build() {
      return new SpawnerBuilding(this);
    }

    // Override parent setters to return SpawnerBuilder for chaining
    @Override
    public SpawnerBuilder name(String name) {
      super.name(name);
      return this;
    }

    @Override
    public SpawnerBuilder team(org.crforge.core.player.Team team) {
      super.team(team);
      return this;
    }

    @Override
    public SpawnerBuilder position(float x, float y) {
      super.position(x, y);
      return this;
    }

    @Override
    public SpawnerBuilder maxHealth(int maxHealth) {
      super.maxHealth(maxHealth);
      return this;
    }

    @Override
    public SpawnerBuilder mass(float mass) {
      super.mass(mass);
      return this;
    }

    @Override
    public SpawnerBuilder size(float size) {
      super.size(size);
      return this;
    }

    @Override
    public SpawnerBuilder combat(Combat combat) {
      super.combat(combat);
      return this;
    }

    @Override
    public SpawnerBuilder lifetime(float lifetime) {
      super.lifetime(lifetime);
      return this;
    }
  }
}
