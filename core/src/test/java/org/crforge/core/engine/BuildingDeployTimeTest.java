package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BuildingDeployTimeTest {

  private GameState gameState;
  private DeploymentSystem deploymentSystem;
  private Player player;

  @BeforeEach
  void setUp() {
    gameState = new GameState();
    CombatSystem combatSystem = new CombatSystem(gameState);
    deploymentSystem = new DeploymentSystem(gameState, combatSystem);
  }

  @Test
  void buildingShouldInheritDeployTimeFromStats() {
    // 1. Create a Card with specific deploy time (e.g. 5.0s for X-Bow)
    float customDeployTime = 5.0f;

    Card buildingCard = Card.builder()
        .id("test-building")
        .name("Test Building")
        .type(CardType.BUILDING)
        .cost(3)
        .buildingHealth(100)
        .troop(TroopStats.builder()
            .name("Test Building")
            .deployTime(customDeployTime)
            .build())
        .build();

    // 2. Setup Player with this card
    player = new Player(Team.BLUE, new Deck(Collections.nCopies(8, buildingCard)), false);
    player.getElixir().update(10f); // Max elixir

    // 3. Deploy
    PlayerActionDTO action = PlayerActionDTO.builder()
        .handIndex(0)
        .x(10f)
        .y(10f)
        .build();

    deploymentSystem.queueAction(player, action);
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);
    gameState.processPending();

    // 4. Verify
    assertThat(gameState.getEntities()).hasSize(1);
    Entity entity = gameState.getEntities().get(0);
    assertThat(entity).isInstanceOf(Building.class);

    Building building = (Building) entity;
    assertThat(building.getDeployTime()).isEqualTo(customDeployTime);
    assertThat(building.getDeployTimer()).isEqualTo(customDeployTime);
    assertThat(building.isDeploying()).isTrue();

    // 5. Verify it ticks down
    building.update(1.0f);
    assertThat(building.getDeployTimer()).isEqualTo(customDeployTime - 1.0f);
  }

  @Test
  void buildingWithZeroDeployTimeShouldBeActiveImmediately() {
    // 1. Create a Card with 0 deploy time
    float customDeployTime = 0.0f;

    Card buildingCard = Card.builder()
        .id("instant-building")
        .name("Instant Building")
        .type(CardType.BUILDING)
        .cost(3)
        .buildingHealth(100)
        .troop(TroopStats.builder()
            .name("Instant Building")
            .deployTime(customDeployTime)
            .build())
        .build();

    player = new Player(Team.BLUE, new Deck(Collections.nCopies(8, buildingCard)), false);
    player.getElixir().update(10f);

    PlayerActionDTO action = PlayerActionDTO.builder()
        .handIndex(0)
        .x(10f)
        .y(10f)
        .build();

    deploymentSystem.queueAction(player, action);
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);
    gameState.processPending();

    Entity entity = gameState.getEntities().get(0);
    Building building = (Building) entity;

    assertThat(building.getDeployTime()).isEqualTo(0.0f);
    assertThat(building.getDeployTimer()).isEqualTo(0.0f);
    assertThat(building.isDeploying()).isFalse();
  }
}
