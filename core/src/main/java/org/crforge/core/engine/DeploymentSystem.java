package org.crforge.core.engine;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import lombok.RequiredArgsConstructor;
import org.crforge.core.card.Card;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;

/**
 * Handles the processing of Player Actions and the spawning of cards (Troops, Spells, Buildings).
 * Decouples game rules (Elixir spending, Card Cycling) and Entity Creation from the main loop.
 */
@RequiredArgsConstructor
public class DeploymentSystem {

  private static final float SPELL_TRAVEL_DISTANCE = 10f;

  private final GameState state;
  private final CombatSystem combatSystem;

  // Internal queue to hold requests associated with the player who made them
  private final Queue<DeploymentRequest> requestQueue = new ConcurrentLinkedQueue<>();

  public void queueAction(Player player, PlayerActionDTO action) {
    requestQueue.offer(new DeploymentRequest(player, action));
  }

  public void update() {
    while (!requestQueue.isEmpty()) {
      DeploymentRequest request = requestQueue.poll();
      processRequest(request);
    }
  }

  private void processRequest(DeploymentRequest request) {
    Player player = request.player;
    PlayerActionDTO action = request.action;

    // 1. Validate Resources & Cycle Card
    Card card = player.tryPlayCard(action);

    if (card != null) {
      int cardLevel = player.getLevelConfig().getLevelFor(card);
      // 2. Spawn the Entity
      spawnCard(player.getTeam(), card, action.getX(), action.getY(), cardLevel);
    }
  }

  private void spawnCard(Team team, Card card, float x, float y, int level) {
    switch (card.getType()) {
      case TROOP -> spawnTroops(team, card, x, y, level);
      case BUILDING -> spawnBuilding(team, card, x, y, level);
      case SPELL -> castSpell(team, card, x, y);
    }
  }

  private void spawnTroops(Team team, Card card, float x, float y, int level) {
    List<TroopStats> troopStatsList = card.getTroops();
    if (troopStatsList == null || troopStatsList.isEmpty()) {
      return;
    }

    TroopStats mainStats = troopStatsList.get(0);

    for (TroopStats stats : troopStatsList) {
      // Check for spawner capability (Witch/Mother Witch logic)
      SpawnerComponent spawner = null;

      if (stats == mainStats && (card.getSpawnInterval() > 0 || card.getDeathSpawnCount() > 0)) {
        if (card.getTroops().size() > 1) {
          TroopStats spawnStats = card.getTroops().get(1);
          spawner = SpawnerComponent.builder()
              .spawnInterval(card.getSpawnInterval())
              .spawnPauseTime(card.getSpawnPauseTime())
              .unitsPerWave(card.getSpawnNumber())
              .currentTimer(0f) // Start with 0 so it spawns immediately after deploy
              .deathSpawnCount(card.getDeathSpawnCount())
              .spawnStats(spawnStats)
              .build();
        }
      }

      Troop troop = createTroop(team, stats, x, y, spawner, level, card.getRarity());
      state.spawnEntity(troop);

      // Handle Spawn Effects
      if (stats.hasSpawnEffect()) {
        triggerSpawnEffect(team, stats, troop.getPosition().getX(), troop.getPosition().getY());
      }
    }
  }

  /**
   * The Red player's view is rotated 180 degrees relative to the Blue player's view.
   * <p>
   * This means that a formation defined with offsets (dx, dy) for Blue (where +y is forward and +x
   * is right) should be applied as (-dx, -dy) for Red to preserve the formation's relative shape
   * and orientation towards the enemy.
   */
  private Troop createTroop(Team team, TroopStats stats, float baseX, float baseY,
      SpawnerComponent spawner, int level, Rarity rarity) {
    float offsetX = stats.getOffsetX();
    float offsetY = stats.getOffsetY();

    if (team == Team.RED) {
      offsetX = -offsetX;
      offsetY = -offsetY;
    }

    float spawnX = baseX + offsetX;
    float spawnY = baseY + offsetY;

    // Troops enter the arena preloaded per RoyaleAPI secret stats:
    // https://royaleapi.com/blog/secret-stats
    // Exception: noPreload units (e.g. Sparky) start with 0 charge.
    float initialLoad = stats.isNoPreload() ? 0f : stats.getLoadTime();

    int scaledHp     = LevelScaling.scaleCard(stats.getHealth(), rarity, level);
    int scaledDamage = LevelScaling.scaleCard(stats.getDamage(), rarity, level);

    Combat combat = Combat.builder()
        .damage(scaledDamage)
        .range(stats.getRange())
        .sightRange(stats.getSightRange())
        .attackCooldown(stats.getAttackCooldown())
        .loadTime(stats.getLoadTime())
        .accumulatedLoadTime(initialLoad)
        .aoeRadius(stats.getAoeRadius())
        .targetType(stats.getTargetType())
        .hitEffects(stats.getHitEffects())
        .projectileStats(stats.getProjectile())
        .build();

    // Explicitly set deployTimer to deployTime to avoid default value issues
    return Troop.builder()
        .name(stats.getName())
        .team(team)
        .position(new Position(spawnX, spawnY))
        .health(new Health(scaledHp))
        .movement(new Movement(
            stats.getSpeed(),
            stats.getMass(),
            stats.getCollisionRadius(),
            stats.getVisualRadius(),
            stats.getMovementType()))
        .combat(combat)
        .deployTime(stats.getDeployTime())
        .deployTimer(stats.getDeployTime())
        .spawner(spawner)
        .build();
  }

  private void spawnBuilding(Team team, Card card, float x, float y, int level) {
    TroopStats stats = card.getTroops().isEmpty() ? null : card.getTroops().get(0);

    Combat combat = null;
    int scaledBuildingHp     = LevelScaling.scaleCard(card.getBuildingHealth(), card.getRarity(), level);
    int scaledBuildingDamage = stats != null ? LevelScaling.scaleCard(stats.getDamage(), card.getRarity(), level) : 0;

    if (stats != null && stats.getDamage() > 0) {
      float initialLoad = stats.isNoPreload() ? 0f : stats.getLoadTime();
      combat = Combat.builder()
          .damage(scaledBuildingDamage)
          .range(stats.getRange())
          .sightRange(stats.getSightRange()) // Building sight
          .attackCooldown(stats.getAttackCooldown())
          .loadTime(stats.getLoadTime())
          .accumulatedLoadTime(initialLoad)
          .targetType(stats.getTargetType())
          .hitEffects(stats.getHitEffects())
          .projectileStats(stats.getProjectile())
          .build();
    }

    float collisionRadius = stats != null ? stats.getCollisionRadius() : 1.0f;
    float visualRadius = stats != null ? stats.getVisualRadius() : 1.0f;
    float deployTime = stats != null ? stats.getDeployTime() : 1.0f;

    // Create Spawner Component if needed
    SpawnerComponent spawner = null;
    if (card.getSpawnInterval() > 0 || card.getDeathSpawnCount() > 0) {
      if (card.getTroops().size() > 1) {
        TroopStats spawnStats = card.getTroops().get(1);
        spawner = SpawnerComponent.builder()
            .spawnInterval(card.getSpawnInterval())
            .spawnPauseTime(card.getSpawnPauseTime())
            .unitsPerWave(card.getSpawnNumber())
            .currentTimer(0f) // Start with 0 so it spawns immediately after deploy
            .deathSpawnCount(card.getDeathSpawnCount())
            .spawnStats(spawnStats)
            .build();
      }
    }

    // Always use Building class, attach components optionally
    Building building = Building.builder()
        .name(card.getName())
        .team(team)
        .position(new Position(x, y))
        .health(new Health(scaledBuildingHp))
        .movement(new Movement(0, 0, collisionRadius, visualRadius, MovementType.BUILDING))
        .combat(combat)
        .lifetime(card.getBuildingLifetime())
        .remainingLifetime(card.getBuildingLifetime())
        .spawner(spawner)
        .deployTime(deployTime)
        .deployTimer(deployTime) // Explicitly set timer to match time
        .build();

    state.spawnEntity(building);

    // Buildings can have spawn effects too (e.g. Tesla hiding?) - though not in standard CR
    if (stats != null && stats.hasSpawnEffect()) {
      triggerSpawnEffect(team, stats, x, y);
    }
  }

  private void castSpell(Team team, Card card, float x, float y) {
    ProjectileStats proj = card.getProjectile();
    if (proj == null) {
      return;
    }

    int damage = proj.getDamage();
    float speed = proj.getSpeed();
    float radius = proj.getRadius();
    List<EffectStats> effects = proj.getHitEffects();

    if (speed > 0) {
      // Traveling spell
      float startY = (team == Team.BLUE) ? y - SPELL_TRAVEL_DISTANCE : y + SPELL_TRAVEL_DISTANCE;
      Projectile p = new Projectile(team, x, startY, x, y, damage, radius, speed, effects);
      state.spawnProjectile(p);
    } else {
      // Instant spell
      combatSystem.applySpellDamage(team, x, y, damage, radius, effects);
    }
  }

  /**
   * Applies spawn effects immediately at the spawn location.
   */
  private void triggerSpawnEffect(Team team, TroopStats stats, float x, float y) {
    combatSystem.applySpellDamage(
        team,
        x,
        y,
        stats.getSpawnDamage(),
        stats.getSpawnRadius(),
        stats.getSpawnEffects()
    );
  }

  // Simple container for the queue
  private record DeploymentRequest(Player player, PlayerActionDTO action) {

  }
}
