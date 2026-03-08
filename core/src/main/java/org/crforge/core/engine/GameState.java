package org.crforge.core.engine;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import java.util.function.Predicate;
import lombok.Getter;
import org.crforge.core.entity.SpawnerSystem;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.player.Team;

/**
 * Container for all game entities and state. Manages entity lifecycle (spawn, death, removal).
 */
@Getter
public class GameState {

  // Destroying the crown tower awards all 3 crowns
  private static final int CROWN_TOWER_CROWN_VALUE = 3;

  private final List<Entity> entities;
  private final List<Projectile> projectiles;
  private final List<Entity> pendingSpawns;
  private final List<Entity> pendingRemovals;

  private final Map<Team, List<Tower>> towers;
  private int frameCount;
  private boolean gameOver;
  private Team winner;

  public GameState() {
    this.entities = new ArrayList<>();
    this.projectiles = new ArrayList<>();
    this.pendingSpawns = new ArrayList<>();
    this.pendingRemovals = new ArrayList<>();
    this.towers = new EnumMap<>(Team.class);
    this.towers.put(Team.BLUE, new ArrayList<>());
    this.towers.put(Team.RED, new ArrayList<>());
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

  /**
   * Process pending spawns and removals. Called at start of each tick.
   */
  public void processPending() {
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
  }

  /**
   * Check for dead entities and trigger death logic.
   *
   * @param spawnerSystem System to handle death spawns
   */
  public void processDeaths(SpawnerSystem spawnerSystem) {
    for (Entity entity : entities) {
      if (!entity.isAlive() && entity instanceof AbstractEntity ae && !ae.isDead()) {
        entity.onDeath();

        // Trigger death spawns (e.g. Golem -> Golemites, Tombstone -> Skeletons)
        if (spawnerSystem != null) {
          spawnerSystem.onDeath(entity);
        }

        // Check for activation of King Tower if Princess Tower dies
        if (entity instanceof Tower tower && tower.isPrincessTower()) {
          Tower king = getCrownTower(tower.getTeam());
          if (king != null) {
            king.setActive(true);
          }
        }

        checkWinCondition(entity);
      }
    }
  }

  // Overload for legacy/testing compatibility
  public void processDeaths() {
    processDeaths(null);
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
    return entities.stream().filter(e -> e.getTeam() == team).toList();
  }

  public List<Entity> getAliveEntities() {
    return entities.stream().filter(Entity::isAlive).toList();
  }

  public List<Entity> getTargetableEntities() {
    return entities.stream().filter(Entity::isTargetable).toList();
  }

  public <T extends Entity> List<T> getEntitiesOfType(Class<T> type) {
    return entities.stream().filter(type::isInstance).map(type::cast).toList();
  }

  public List<Entity> findEntities(Predicate<Entity> predicate) {
    return entities.stream().filter(predicate).toList();
  }

  public Optional<Entity> getEntityById(long id) {
    return entities.stream().filter(e -> e.getId() == id).findFirst();
  }

  public Tower getCrownTower(Team team) {
    return towers.get(team).stream().filter(Tower::isCrownTower).findFirst().orElse(null);
  }

  public List<Tower> getPrincessTowers(Team team) {
    return towers.get(team).stream().filter(Tower::isPrincessTower).toList();
  }

  public int getTowerCount(Team team) {
    return (int) towers.get(team).stream().filter(Entity::isAlive).count();
  }

  /**
   * Checks if a princess tower is alive for the given team and lane.
   *
   * @param team       the team whose princess tower to check
   * @param leftLane   true for left lane, false for right lane
   * @param centerX    arena center X for lane determination
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
    int crowns = 0;

    // Check enemy crown tower
    Tower enemyCrown = getCrownTower(enemy);
    if (enemyCrown != null && !enemyCrown.isAlive()) {
      crowns = CROWN_TOWER_CROWN_VALUE;
    } else {
      // Count destroyed princess towers
      crowns = (int) getPrincessTowers(enemy).stream().filter(t -> !t.isAlive()).count();
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
    frameCount = 0;
    gameOver = false;
    winner = null;
    AbstractEntity.resetIdCounter();
  }
}
