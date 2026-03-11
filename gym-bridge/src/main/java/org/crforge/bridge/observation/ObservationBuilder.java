package org.crforge.bridge.observation;

import java.util.ArrayList;
import java.util.List;
import org.crforge.bridge.dto.EntityDTO;
import org.crforge.bridge.dto.HandCardDTO;
import org.crforge.bridge.dto.ObservationDTO;
import org.crforge.bridge.dto.PlayerObsDTO;
import org.crforge.bridge.dto.TowerDTO;
import org.crforge.core.card.Card;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.player.Hand;
import org.crforge.core.player.Player;

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
    return new HandCardDTO(card.getId(), card.getName(), card.getType().name(), card.getCost());
  }

  private static List<EntityDTO> buildEntityList(GameState state) {
    List<Entity> alive = state.getAliveEntities();
    List<EntityDTO> entities = new ArrayList<>(alive.size());

    for (Entity entity : alive) {
      entities.add(
          new EntityDTO(
              entity.getId(),
              entity.getName(),
              entity.getTeam().name(),
              entity.getEntityType().name(),
              entity.getMovementType().name(),
              entity.getPosition().getX(),
              entity.getPosition().getY(),
              entity.getHealth().getCurrent(),
              entity.getHealth().getMax(),
              entity.getHealth().getShield()));
    }

    return entities;
  }
}
