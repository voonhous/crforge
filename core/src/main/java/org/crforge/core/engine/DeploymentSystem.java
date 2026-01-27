package org.crforge.core.engine;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import lombok.RequiredArgsConstructor;
import org.crforge.core.card.Card;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.entity.base.MovementType;
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
      // 2. Spawn the Entity
      spawnCard(player.getTeam(), card, action.getX(), action.getY());
    }
  }

  private void spawnCard(Team team, Card card, float x, float y) {
    switch (card.getType()) {
      case TROOP -> spawnTroops(team, card, x, y);
      case BUILDING -> spawnBuilding(team, card, x, y);
      case SPELL -> castSpell(team, card, x, y);
    }
  }

  private void spawnTroops(Team team, Card card, float x, float y) {
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
              .currentTimer(card.getSpawnInterval())
              .deathSpawnCount(card.getDeathSpawnCount())
              .spawnStats(spawnStats)
              .build();
        }
      }

      Troop troop = createTroop(team, stats, x, y, spawner);
      state.spawnEntity(troop);
    }
  }

  private Troop createTroop(Team team, TroopStats stats, float baseX, float baseY,
      SpawnerComponent spawner) {
    float spawnX = baseX + stats.getOffsetX();
    float spawnY = baseY + stats.getOffsetY();

    Combat combat = Combat.builder()
        .damage(stats.getDamage())
        .range(stats.getRange())
        .sightRange(stats.getSightRange())
        .attackCooldown(stats.getAttackCooldown())
        .aoeRadius(stats.getAoeRadius())
        .targetType(stats.getTargetType())
        .ranged(stats.isRanged())
        .hitEffects(stats.getHitEffects()) // Pass effects
        .build();

    return Troop.builder()
        .name(stats.getName())
        .team(team)
        .position(new Position(spawnX, spawnY))
        .health(new Health(stats.getHealth()))
        .movement(new Movement(stats.getSpeed(), stats.getMass(), stats.getSize(),
            stats.getMovementType()))
        .combat(combat)
        .deployTime(stats.getDeployTime())
        .spawner(spawner)
        .build();
  }

  private void spawnBuilding(Team team, Card card, float x, float y) {
    TroopStats stats = card.getTroops().isEmpty() ? null : card.getTroops().get(0);

    Combat combat = null;
    if (stats != null && stats.getDamage() > 0) {
      combat = Combat.builder()
          .damage(stats.getDamage())
          .range(stats.getRange())
          .attackCooldown(stats.getAttackCooldown())
          .targetType(stats.getTargetType())
          .ranged(stats.isRanged())
          .hitEffects(stats.getHitEffects())
          .build();
    }

    float size = stats != null ? stats.getSize() : 2.0f;
    int health = card.getBuildingHealth();

    // Create Spawner Component if needed
    SpawnerComponent spawner = null;
    if (card.getSpawnInterval() > 0 || card.getDeathSpawnCount() > 0) {
      if (card.getTroops().size() > 1) {
        TroopStats spawnStats = card.getTroops().get(1);
        spawner = SpawnerComponent.builder()
            .spawnInterval(card.getSpawnInterval())
            .currentTimer(card.getSpawnInterval())
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
        .health(new Health(health))
        .movement(new Movement(0, 0, size, MovementType.BUILDING))
        .combat(combat)
        .lifetime(card.getBuildingLifetime())
        .remainingLifetime(card.getBuildingLifetime())
        .spawner(spawner)
        .build();

    state.spawnEntity(building);
  }

  private void castSpell(Team team, Card card, float x, float y) {
    float speed = card.getSpellProjectileSpeed();
    int damage = card.getSpellDamage();
    float radius = card.getSpellRadius();
    List<EffectStats> effects = card.getSpellEffects();

    if (speed > 0) {
      // Traveling spell: spawn position-targeted projectile
      float startY = (team == Team.BLUE) ? y - SPELL_TRAVEL_DISTANCE : y + SPELL_TRAVEL_DISTANCE;
      Projectile p = new Projectile(team, x, startY, x, y, damage, radius, speed, effects);
      state.spawnProjectile(p);
    } else {
      // Instant spell: delegate to CombatSystem
      combatSystem.applySpellDamage(team, x, y, damage, radius, effects);
    }
  }

  // Simple container for the queue
  private record DeploymentRequest(Player player, PlayerActionDTO action) {

  }
}
