package org.crforge.core.engine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import lombok.Getter;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;

/**
 * Handles the processing of Player Actions and the spawning of cards (Troops, Spells, Buildings).
 * Decouples game rules (Elixir spending, Card Cycling) and Entity Creation from the main loop.
 *
 * <p>Deployments go through a server sync delay before entities actually spawn on the arena. Elixir
 * is spent and the hand is cycled immediately, but the entity appears after {@link
 * #PLACEMENT_SYNC_DELAY} seconds -- matching real Clash Royale's server latency buffer.
 *
 * <p>Entity construction is delegated to {@link EntityFactory}.
 */
public class DeploymentSystem {

  /** Server synchronization delay before a card's deploy timer starts (seconds). */
  public static final float PLACEMENT_SYNC_DELAY = 1.0f;

  /** Fixed delay between each unit spawn for multi-unit troop cards (seconds). */
  public static final float STAGGER_DELAY = 0.1f;

  private final EntityFactory entityFactory;

  // Internal queue to hold requests associated with the player who made them
  private final Queue<DeploymentRequest> requestQueue = new ConcurrentLinkedQueue<>();

  // Deployments waiting for the sync delay to expire before spawning
  @Getter private final List<PendingDeployment> pendingDeployments = new ArrayList<>();

  public DeploymentSystem(GameState state, AoeDamageService aoeDamageService) {
    this.entityFactory = new EntityFactory(state, aoeDamageService);
  }

  public void queueAction(Player player, PlayerActionDTO action) {
    requestQueue.offer(new DeploymentRequest(player, action));
  }

  /** Clears all queued actions and pending deployments. Called on match reset. */
  public void reset() {
    requestQueue.clear();
    pendingDeployments.clear();
  }

  /**
   * Processes queued actions and ticks pending deployment timers. Multi-unit TROOP cards spawn one
   * unit at a time with stagger delay after the sync delay expires.
   *
   * @param deltaTime time elapsed since last update (seconds)
   */
  public void update(float deltaTime) {
    // 1. Drain request queue -> create PendingDeployments
    while (!requestQueue.isEmpty()) {
      DeploymentRequest request = requestQueue.poll();
      processRequest(request);
    }

    // 2. Tick pending deployment timers with two-phase spawning
    Iterator<PendingDeployment> it = pendingDeployments.iterator();
    while (it.hasNext()) {
      PendingDeployment pending = it.next();

      // Phase 1: Sync delay countdown
      if (!pending.syncComplete) {
        pending.remainingDelay -= deltaTime;
        if (pending.remainingDelay <= 0) {
          pending.syncComplete = true;
          // Carry over leftover time from sync into stagger phase
          pending.staggerTimer = pending.remainingDelay; // negative or zero
        } else {
          continue;
        }
      } else {
        // Phase 2 tick: only subtract deltaTime on ticks after sync completed
        pending.staggerTimer -= deltaTime;
      }

      while (pending.staggerTimer <= 0 && pending.nextUnitIndex < pending.totalUnits) {
        if (pending.isTroop()) {
          // Spawn one troop at a time (single-unit cards have staggerDelay=0, so the
          // while loop spawns the one unit instantly with no behavioral change)
          entityFactory.spawnSingleTroop(
              pending.team,
              pending.card,
              pending.x,
              pending.y,
              pending.level,
              pending.nextUnitIndex);

          // Fire deploy effect only on the first unit
          if (!pending.deployEffectFired && pending.card.getDeployEffect() != null) {
            entityFactory.deployAreaEffect(
                pending.team,
                pending.card.getDeployEffect(),
                pending.x,
                pending.y,
                pending.card.getRarity(),
                pending.level);
            pending.deployEffectFired = true;
          }

          // Fire spawn projectile only on the first unit (e.g. MegaKnight landing damage)
          if (!pending.spawnProjectileFired && pending.card.getSpawnProjectile() != null) {
            entityFactory.fireSpawnProjectile(
                pending.team, pending.card, pending.x, pending.y, pending.level);
            pending.spawnProjectileFired = true;
          }
        } else {
          // Non-staggered: buildings and spells
          if (pending.isTunnelBuilding()) {
            // Tunnel building: spawn dig troop instead of building directly
            entityFactory.spawnTunnelBuilding(
                pending.team, pending.card, pending.x, pending.y, pending.level);
          } else {
            entityFactory.spawnCard(
                pending.team, pending.card, pending.x, pending.y, pending.level);
          }
          pending.nextUnitIndex = pending.totalUnits; // mark complete
          break;
        }

        pending.nextUnitIndex++;
        pending.staggerTimer += pending.staggerDelay;
      }

      // Remove when all units have spawned
      if (pending.nextUnitIndex >= pending.totalUnits) {
        it.remove();
      }
    }
  }

  /**
   * Handles the morph event when a tunnel dig troop arrives at its target. Kills the dig troop and
   * spawns the building card at the target location. Also fires the building's deploy area effect
   * (e.g. GoblinDrillDamage).
   */
  public void handleTunnelMorph(Troop digTroop, float targetX, float targetY) {
    Card card = digTroop.getMorphCard();
    int level = digTroop.getMorphLevel();
    Team team = digTroop.getTeam();

    // Kill the dig troop
    digTroop.getHealth().takeDamage(digTroop.getHealth().getCurrent());

    // Spawn the building at the target location
    entityFactory.spawnCard(team, card, targetX, targetY, level);

    // Fire the building's deploy effect (e.g. GoblinDrillDamage from spawnAreaEffect)
    if (card.getDeployEffect() != null) {
      entityFactory.deployAreaEffect(
          team, card.getDeployEffect(), targetX, targetY, card.getRarity(), level);
    }
  }

  private void processRequest(DeploymentRequest request) {
    Player player = request.player;
    PlayerActionDTO action = request.action;

    // Capture pre-spend elixir for variant resolution (e.g. MergeMaiden form selection)
    int preSpendElixir = player.getElixir().getFloor();

    // 1. Validate Resources & Cycle Card (elixir spent immediately)
    Card card = player.tryPlayCard(action);

    if (card != null) {
      // Resolve variant based on pre-spend elixir
      Card resolvedCard = card.resolveVariant(preSpendElixir);

      // For mirrorCopiesVariant cards, store the resolved variant as lastPlayedCard
      // so Mirror replays the specific variant without re-evaluating triggers
      if (card.isMirrorCopiesVariant() && resolvedCard != card) {
        player.setLastPlayedCard(resolvedCard);
      }

      // Mirror sets pendingMirrorLevel to override the card's normal level
      int cardLevel;
      if (player.getPendingMirrorLevel() > 0) {
        cardLevel = player.getPendingMirrorLevel();
        player.clearPendingMirrorLevel();
      } else {
        cardLevel = player.getLevelConfig().getLevelFor(resolvedCard);
      }
      // 2. Queue for spawn after sync delay
      pendingDeployments.add(
          new PendingDeployment(
              player.getTeam(),
              resolvedCard,
              action.getX(),
              action.getY(),
              cardLevel,
              PLACEMENT_SYNC_DELAY));
    }
  }

  // Simple container for the queue
  private record DeploymentRequest(Player player, PlayerActionDTO action) {}

  /**
   * Holds a resolved deployment during the server sync delay and staggered unit spawning. Elixir
   * has already been spent and the hand cycled.
   *
   * <p>Two-phase lifecycle:
   *
   * <ol>
   *   <li>Sync delay: {@code remainingDelay} counts down to zero
   *   <li>Stagger: multi-unit TROOP cards spawn one unit at a time with {@code staggerDelay}
   *       between each; buildings/spells/single-unit troops spawn all at once
   * </ol>
   */
  @Getter
  public static class PendingDeployment {

    final Team team;
    final Card card;
    final float x;
    final float y;
    final int level;
    float remainingDelay;

    // Stagger state
    int nextUnitIndex;
    final int totalUnits;
    final float staggerDelay;
    float staggerTimer;
    boolean syncComplete;
    boolean deployEffectFired;
    boolean spawnProjectileFired;

    PendingDeployment(Team team, Card card, float x, float y, int level, float remainingDelay) {
      this.team = team;
      this.card = card;
      this.x = x;
      this.y = y;
      this.level = level;
      this.remainingDelay = remainingDelay;

      // Multi-unit TROOP cards get staggered spawning
      this.totalUnits = card.getType() == CardType.TROOP ? card.getTotalDeployCount() : 1;
      this.staggerDelay = totalUnits > 1 ? STAGGER_DELAY : 0f;
    }

    /** Whether this deployment is a troop card (all troops go through the stagger path). */
    public boolean isTroop() {
      return card.getType() == CardType.TROOP;
    }

    /** Whether this deployment is a tunnel building (e.g. GoblinDrill deploys via dig unit). */
    public boolean isTunnelBuilding() {
      return card.getTunnelDigUnit() != null;
    }

    /**
     * Whether this deployment is currently in the stagger phase (sync delay expired, but not all
     * units have spawned yet).
     */
    public boolean isStaggering() {
      return syncComplete && nextUnitIndex < totalUnits;
    }

    /** Returns the fraction of stagger progress (0.0 = no units spawned, 1.0 = all spawned). */
    public float getStaggerProgress() {
      return totalUnits > 0 ? (float) nextUnitIndex / totalUnits : 1f;
    }
  }
}
