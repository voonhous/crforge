package org.crforge.bridge.observation;

import java.util.ArrayList;
import java.util.List;
import org.crforge.bridge.dto.EntityDTO;
import org.crforge.bridge.dto.HandCardDTO;
import org.crforge.bridge.dto.ObservationDTO;
import org.crforge.bridge.dto.PlayerObsDTO;
import org.crforge.bridge.dto.TowerDTO;
import org.crforge.core.card.Card;
import org.crforge.core.component.Combat;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.player.Hand;
import org.crforge.core.player.Player;
import org.crforge.data.card.CardRegistry;

/** Builds observation DTOs from the current game state for RL agents. */
public class ObservationBuilder {

  /** Builds a complete observation snapshot from the current engine state. */
  public static ObservationDTO build(GameEngine engine, Player bluePlayer, Player redPlayer) {
    GameState state = engine.getGameState();

    PlayerObsDTO blueObs = buildPlayerObs(bluePlayer, state);
    PlayerObsDTO redObs = buildPlayerObs(redPlayer, state);
    List<EntityDTO> entities = buildEntityList(state);

    return new ObservationDTO(
        state.getFrameCount(),
        state.getGameTimeSeconds(),
        engine.isOvertime(),
        blueObs,
        redObs,
        entities);
  }

  private static PlayerObsDTO buildPlayerObs(Player player, GameState state) {
    Hand hand = player.getHand();

    // Build hand cards
    List<HandCardDTO> handCards = new ArrayList<>(Hand.HAND_SIZE);
    for (int i = 0; i < Hand.HAND_SIZE; i++) {
      Card card = hand.getCard(i);
      if (card != null) {
        handCards.add(toHandCardDTO(card));
      }
    }

    // Next card
    HandCardDTO nextCard = null;
    if (hand.getNextCard() != null) {
      nextCard = toHandCardDTO(hand.getNextCard());
    }

    // Towers
    List<TowerDTO> towers = new ArrayList<>();
    for (Tower tower : state.getTowers().get(player.getTeam())) {
      towers.add(
          new TowerDTO(
              tower.getId(),
              tower.isCrownTower() ? "crown" : "princess",
              tower.getHealth().getCurrent(),
              tower.getHealth().getMax(),
              tower.getPosition().getX(),
              tower.getPosition().getY(),
              tower.isAlive()));
    }

    int crowns = state.getCrownCount(player.getTeam());

    return new PlayerObsDTO(player.getElixir().getCurrent(), handCards, nextCard, towers, crowns);
  }

  private static HandCardDTO toHandCardDTO(Card card) {
    int cardIndex = CardRegistry.getIndex(card.getId());
    return new HandCardDTO(
        card.getId(), card.getName(), card.getType().name(), card.getCost(), cardIndex);
  }

  private static List<EntityDTO> buildEntityList(GameState state) {
    List<Entity> alive = state.getAliveEntities();
    List<EntityDTO> entities = new ArrayList<>(alive.size());

    for (Entity entity : alive) {
      entities.add(toEntityDTO(entity));
    }

    return entities;
  }

  private static EntityDTO toEntityDTO(Entity entity) {
    // A2: Combat state
    float attackCooldownFraction = 0f;
    boolean isAttacking = false;
    boolean hasTarget = false;

    Combat combat = entity.getCombat();
    if (combat != null) {
      // Readiness: 1.0 = ready to attack, 0.0 = just attacked (cooldown at max)
      if (combat.getAttackCooldown() > 0) {
        float remaining = Math.max(0, combat.getCurrentCooldown());
        attackCooldownFraction = 1.0f - (remaining / combat.getAttackCooldown());
        attackCooldownFraction = Math.max(0f, Math.min(1f, attackCooldownFraction));
      } else {
        attackCooldownFraction = 1.0f;
      }
      isAttacking = combat.isAttacking();
      hasTarget = combat.hasTarget();
    }

    // A3: Status effects
    boolean stunned = false;
    boolean slowed = false;
    boolean raged = false;
    boolean frozen = false;
    boolean poisoned = false;

    for (AppliedEffect effect : entity.getAppliedEffects()) {
      switch (effect.getType()) {
        case STUN -> stunned = true;
        case SLOW -> slowed = true;
        case RAGE -> raged = true;
        case FREEZE -> frozen = true;
        case POISON -> poisoned = true;
        default -> {
          // Other effects (BURN, VULNERABILITY, CURSE, EARTHQUAKE, TORNADO, KNOCKBACK)
          // not exposed as individual flags -- add if needed
        }
      }
    }

    // A4: Building lifetime
    float lifetimeFraction = 0f;
    if (entity instanceof Building building && building.hasLifetime()) {
      lifetimeFraction = building.getRemainingLifetime() / building.getLifetime();
      lifetimeFraction = Math.max(0f, Math.min(1f, lifetimeFraction));
    }

    return new EntityDTO(
        entity.getId(),
        entity.getName(),
        entity.getTeam().name(),
        entity.getEntityType().name(),
        entity.getMovementType().name(),
        entity.getPosition().getX(),
        entity.getPosition().getY(),
        entity.getHealth().getCurrent(),
        entity.getHealth().getMax(),
        entity.getHealth().getShield(),
        attackCooldownFraction,
        isAttacking,
        hasTarget,
        stunned,
        slowed,
        raged,
        frozen,
        poisoned,
        lifetimeFraction);
  }
}
