package org.crforge.core.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import lombok.RequiredArgsConstructor;
import org.crforge.core.card.Card;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.entity.Building;
import org.crforge.core.entity.Entity;
import org.crforge.core.entity.Troop;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;

/**
 * Handles the processing of Player Actions and the spawning of cards (Troops, Spells, Buildings).
 * Decouples game rules (Elixir spending, Card Cycling) and Entity Creation from the main loop.
 */
@RequiredArgsConstructor
public class DeploymentSystem {

  private final GameState state;

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

    for (TroopStats stats : troopStatsList) {
      Troop troop = createTroop(team, stats, x, y);
      state.spawnEntity(troop);
    }
  }

  private Troop createTroop(Team team, TroopStats stats, float baseX, float baseY) {
    // Apply spawn offset (for multi-unit cards like Barbarians)
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
        .build();

    return Troop.builder()
        .name(stats.getName())
        .team(team)
        .position(spawnX, spawnY)
        .maxHealth(stats.getHealth())
        .speed(stats.getSpeed())
        .mass(stats.getMass())
        .size(stats.getSize())
        .movementType(stats.getMovementType())
        .combat(combat)
        .deployTime(stats.getDeployTime())
        .build();
  }

  private void spawnBuilding(Team team, Card card, float x, float y) {
    // Buildings use first TroopStats for combat info (if any)
    TroopStats stats = card.getTroops().isEmpty() ? null : card.getTroops().get(0);

    Combat combat = null;
    if (stats != null && stats.getDamage() > 0) {
      combat = Combat.builder()
          .damage(stats.getDamage())
          .range(stats.getRange())
          .attackCooldown(stats.getAttackCooldown())
          .targetType(stats.getTargetType())
          .ranged(stats.isRanged())
          .build();
    }

    float size = stats != null ? stats.getSize() : 2.0f;

    Building building = Building.builder()
        .name(card.getName())
        .team(team)
        .position(x, y)
        .maxHealth(card.getBuildingHealth())
        .size(size)
        .combat(combat)
        .lifetime(card.getBuildingLifetime())
        .build();

    state.spawnEntity(building);
  }

  private void castSpell(Team team, Card card, float x, float y) {
    // Find all enemy entities within spell radius and deal damage
    float radius = card.getSpellRadius();
    int damage = card.getSpellDamage();

    List<Entity> targets = new ArrayList<>();
    for (Entity entity : state.getAliveEntities()) {
      if (entity.getTeam() == team) {
        continue; // Don't damage own team
      }

      float dx = entity.getPosition().getX() - x;
      float dy = entity.getPosition().getY() - y;
      float distSq = dx * dx + dy * dy;

      if (distSq <= radius * radius) {
        targets.add(entity);
      }
    }

    // Apply damage to all targets (deaths processed by GameEngine.processDeaths())
    for (Entity target : targets) {
      target.getHealth().takeDamage(damage);
    }
  }

  // Simple container for the queue
  private record DeploymentRequest(Player player, PlayerActionDTO action) {

  }
}