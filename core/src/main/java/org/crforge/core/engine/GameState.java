package org.crforge.core.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import lombok.Getter;
import lombok.Setter;
import org.crforge.core.arena.Arena;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.player.Team;

/** Container for all game entities and state. Manages entity lifecycle (spawn, death, removal). */
@Getter
public class GameState {

  // Destroying the crown tower awards all 3 crowns
  private static final int CROWN_TOWER_CROWN_VALUE = 3;

  private final List<Entity> entities;
  private final List<Projectile> projectiles;
  private final List<Entity> pendingSpawns;
  private final List<Entity> pendingRemovals;
  private final List<AoeDamageEvent> aoeDamageEvents;

  private final Map<Team, List<Tower>> towers;
  private List<Entity> cachedAliveEntities;
  private List<Entity> cachedBlueAlive;
  private List<Entity> cachedRedAlive;
  private List<AreaEffect> cachedAreaEffects;
  private Map<Long, Entity> entityById;
  @Setter private Arena arena;
  @Setter private DeathHandler deathHandler;
  private int frameCount;
  private boolean gameOver;
  private Team winner;

  public GameState() {
    this.entities = new ArrayList<>();
    this.projectiles = new ArrayList<>();
    this.pendingSpawns = new ArrayList<>();
    this.pendingRemovals = new ArrayList<>();
    this.aoeDamageEvents = new ArrayList<>();
    this.towers = new EnumMap<>(Team.class);
    this.towers.put(Team.BLUE, new ArrayList<>());
    this.towers.put(Team.RED, new ArrayList<>());
    this.cachedAliveEntities = Collections.emptyList();
    this.cachedBlueAlive = Collections.emptyList();
    this.cachedRedAlive = Collections.emptyList();
    this.cachedAreaEffects = Collections.emptyList();
    this.entityById = Collections.emptyMap();
    this.frameCount = 0;
    this.gameOver = false;
    this.winner = null;
  }

  public void spawnEntity(Entity entity) {
    pendingSpawns.add(entity);
  }

  public void spawnProjectile(Projectile projectile) {
    projectiles.add(projectile);
  }

  public void removeEntity(Entity entity) {
    pendingRemovals.add(entity);
  }

  public void removeProjectile(Projectile projectile) {
    projectiles.remove(projectile);
  }

  /** Process pending spawns and removals. Called at start of each tick. */
  public void recordAoeDamage(float centerX, float centerY, float radius, Team sourceTeam) {
    aoeDamageEvents.add(new AoeDamageEvent(centerX, centerY, radius, sourceTeam));
  }

  public void processPending() {
    // Clear AOE damage events from previous tick
    aoeDamageEvents.clear();

    // Process spawns
    for (Entity entity : pendingSpawns) {
      entities.add(entity);
      entity.onSpawn();

      if (entity instanceof Tower tower) {
        towers.get(tower.getTeam()).add(tower);
      }
    }
    pendingSpawns.clear();

    // Process removals
    for (Entity entity : pendingRemovals) {
      entities.remove(entity);
      if (entity instanceof Tower tower) {
        towers.get(tower.getTeam()).remove(tower);
      }
    }
    pendingRemovals.clear();

    // Remove dead projectiles
    projectiles.removeIf(p -> !p.isActive());

    // Rebuild cached alive list after structural changes
    refreshCaches();
  }

  /**
   * Check for dead entities and trigger death logic. Uses the registered {@link DeathHandler} for
   * death spawns, death damage, etc.
   */
  public void processDeaths() {
    for (Entity entity : entities) {
      if (!entity.isAlive() && entity instanceof AbstractEntity ae && !ae.isDead()) {
        entity.onDeath();

        // Trigger death spawns (e.g. Golem -> Golemites, Tombstone -> Skeletons)
        if (deathHandler != null) {
          deathHandler.onDeath(entity);
        }

        // Check for activation of King Tower if Princess Tower dies
        if (entity instanceof Tower tower && tower.isPrincessTower()) {
          Tower king = getCrownTower(tower.getTeam());
          if (king != null) {
            king.setActive(true);
          }
          if (arena != null) {
            // Free the tower's tiles so the owning team can deploy on the footprint
            arena.freePrincessTowerTiles(
                tower.getPosition().getX(), tower.getPosition().getY(), tower.getTeam());
            // Open pocket deploy zone for the opposing team in the destroyed tower's lane
            Team attackingTeam = tower.getTeam().opposite();
            boolean leftLane = tower.getPosition().getX() < Arena.WIDTH / 2.0f;
            arena.openPocketZone(attackingTeam, leftLane);
          }
        }

        checkWinCondition(entity);
      }
    }

    // Refresh cache since entities may have died
    refreshCaches();
  }

  private void checkWinCondition(Entity deadEntity) {
    if (!(deadEntity instanceof Tower tower)) {
      return;
    }

    if (tower.isCrownTower()) {
      gameOver = true;
      winner = tower.getTeam().opposite();
    }
  }

  public void incrementFrame() {
    frameCount++;
  }

  public float getGameTimeSeconds() {
    return frameCount / 30f;
  }

  public List<Entity> getEntitiesByTeam(Team team) {
    return team == Team.BLUE ? cachedBlueAlive : cachedRedAlive;
  }

  /**
   * Rebuilds all cached entity lists in a single pass through the entities list. Call once at the
   * start of each tick, after processPending(), so all systems share a single snapshot.
   */
  public void refreshCaches() {
    List<Entity> alive = new ArrayList<>(entities.size());
    List<Entity> blueAlive = new ArrayList<>();
    List<Entity> redAlive = new ArrayList<>();
    List<AreaEffect> areaEffects = new ArrayList<>();
    Map<Long, Entity> byId = new HashMap<>(entities.size() * 2);

    for (Entity e : entities) {
      byId.put(e.getId(), e);
      if (e instanceof AreaEffect ae) {
        areaEffects.add(ae);
      }
      if (e.isAlive()) {
        alive.add(e);
        if (e.getTeam() == Team.BLUE) {
          blueAlive.add(e);
        } else {
          redAlive.add(e);
        }
      }
    }

    cachedAliveEntities = alive;
    cachedBlueAlive = blueAlive;
    cachedRedAlive = redAlive;
    cachedAreaEffects = areaEffects;
    entityById = byId;
  }

  public List<Entity> getAliveEntities() {
    return cachedAliveEntities;
  }

  public List<Entity> getTargetableEntities() {
    return entities.stream().filter(Entity::isTargetable).toList();
  }

  @SuppressWarnings("unchecked")
  public <T extends Entity> List<T> getEntitiesOfType(Class<T> type) {
    if (type == AreaEffect.class) {
      return (List<T>) cachedAreaEffects;
    }
    return entities.stream().filter(type::isInstance).map(type::cast).toList();
  }

  /** Returns the cached list of area effects. Faster than getEntitiesOfType(AreaEffect.class). */
  public List<AreaEffect> getAreaEffects() {
    return cachedAreaEffects;
  }

  public List<Entity> findEntities(Predicate<Entity> predicate) {
    return entities.stream().filter(predicate).toList();
  }

  public Optional<Entity> getEntityById(long id) {
    Entity e = entityById.get(id);
    return Optional.ofNullable(e);
  }

  public Tower getCrownTower(Team team) {
    for (Tower tower : towers.get(team)) {
      if (tower.isCrownTower()) {
        return tower;
      }
    }
    return null;
  }

  public List<Tower> getPrincessTowers(Team team) {
    List<Tower> result = new ArrayList<>(2);
    for (Tower tower : towers.get(team)) {
      if (tower.isPrincessTower()) {
        result.add(tower);
      }
    }
    return result;
  }

  public int getTowerCount(Team team) {
    int count = 0;
    for (Tower tower : towers.get(team)) {
      if (tower.isAlive()) {
        count++;
      }
    }
    return count;
  }

  /**
   * Checks if a princess tower is alive for the given team and lane.
   *
   * @param team the team whose princess tower to check
   * @param leftLane true for left lane, false for right lane
   * @param centerX arena center X for lane determination
   * @return true if a matching princess tower is alive
   */
  public boolean isPrincessTowerAlive(Team team, boolean leftLane, float centerX) {
    for (Tower tower : towers.get(team)) {
      if (tower.isPrincessTower() && tower.isAlive()) {
        boolean towerIsLeft = tower.getPosition().getX() < centerX;
        if (towerIsLeft == leftLane) {
          return true;
        }
      }
    }
    return false;
  }

  public int getCrownCount(Team team) {
    Team enemy = team.opposite();

    // Check enemy crown tower
    Tower enemyCrown = getCrownTower(enemy);
    if (enemyCrown != null && !enemyCrown.isAlive()) {
      return CROWN_TOWER_CROWN_VALUE;
    }

    // Count destroyed princess towers
    int crowns = 0;
    for (Tower tower : towers.get(enemy)) {
      if (tower.isPrincessTower() && !tower.isAlive()) {
        crowns++;
      }
    }
    return crowns;
  }

  public void reset() {
    entities.clear();
    projectiles.clear();
    pendingSpawns.clear();
    pendingRemovals.clear();
    towers.get(Team.BLUE).clear();
    towers.get(Team.RED).clear();
    aoeDamageEvents.clear();
    cachedAliveEntities = Collections.emptyList();
    cachedBlueAlive = Collections.emptyList();
    cachedRedAlive = Collections.emptyList();
    cachedAreaEffects = Collections.emptyList();
    entityById = Collections.emptyMap();
    frameCount = 0;
    gameOver = false;
    winner = null;
    AbstractEntity.resetIdCounter();
  }
}
