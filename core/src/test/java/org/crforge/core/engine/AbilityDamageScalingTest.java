package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.ChargeAbility;
import org.crforge.core.ability.DashAbility;
import org.crforge.core.ability.ReflectAbility;
import org.crforge.core.ability.VariableDamageAbility;
import org.crforge.core.ability.VariableDamageStage;
import org.crforge.core.card.Card;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.LevelConfig;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that ability damage (DASH, CHARGE, REFLECT, VARIABLE_DAMAGE) is level-scaled when troops
 * and buildings are created via the factory pipeline.
 */
class AbilityDamageScalingTest {

  private static final int TEST_LEVEL = 11;

  private GameEngine engine;
  private Player bluePlayer;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
  }

  /** Deploys a card at the given level and returns the first non-tower entity matching the name. */
  private Entity deployCard(String cardId, float x, float y) {
    Card card = Objects.requireNonNull(CardRegistry.get(cardId), cardId + " not found");
    List<Card> deckCards = new ArrayList<>(Collections.nCopies(8, card));

    bluePlayer = new Player(Team.BLUE, new Deck(deckCards), false, new LevelConfig(TEST_LEVEL));
    // Give enough elixir to deploy
    bluePlayer.getElixir().update(100f);

    Player redPlayer =
        new Player(
            Team.RED, new Deck(new ArrayList<>(deckCards)), false, new LevelConfig(TEST_LEVEL));

    Standard1v1Match match = new Standard1v1Match();
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);

    engine = new GameEngine();
    engine.setMatch(match);
    engine.initMatch();

    // Queue card deployment
    engine.queueAction(bluePlayer, PlayerActionDTO.play(0, x, y));

    // Tick past the placement sync delay + 1 tick to process pending spawns
    int syncTicks = (int) (DeploymentSystem.PLACEMENT_SYNC_DELAY * GameEngine.TICKS_PER_SECOND);
    engine.tick(syncTicks + 1);

    // Find the spawned entity by name (skip towers)
    return engine.getGameState().getEntities().stream()
        .filter(e -> e.getTeam() == Team.BLUE)
        .filter(e -> !(e instanceof org.crforge.core.entity.structure.Tower))
        .findFirst()
        .orElse(null);
  }

  @Test
  void dashDamage_shouldBeScaledByLevel() {
    Entity entity = deployCard("megaknight", 9f, 10f);
    assertThat(entity).isInstanceOf(Troop.class);

    Troop troop = (Troop) entity;
    AbilityComponent ability = troop.getAbility();
    assertThat(ability).isNotNull();
    assertThat(ability.getData()).isInstanceOf(DashAbility.class);

    DashAbility dashData = (DashAbility) ability.getData();
    Card card = Objects.requireNonNull(CardRegistry.get("megaknight"));
    int expectedScaled =
        LevelScaling.scaleCard(dashData.dashDamage(), card.getRarity(), TEST_LEVEL);

    assertThat(ability.getScaledDashDamage()).isEqualTo(expectedScaled);
    // Scaled should be significantly higher than base level-1 value
    assertThat(ability.getScaledDashDamage()).isGreaterThan(dashData.dashDamage());
  }

  @Test
  void chargeDamage_shouldBeScaledByLevel() {
    Entity entity = deployCard("prince", 9f, 10f);
    assertThat(entity).isInstanceOf(Troop.class);

    Troop troop = (Troop) entity;
    AbilityComponent ability = troop.getAbility();
    assertThat(ability).isNotNull();
    assertThat(ability.getData()).isInstanceOf(ChargeAbility.class);

    ChargeAbility chargeData = (ChargeAbility) ability.getData();
    Card card = Objects.requireNonNull(CardRegistry.get("prince"));
    int expectedScaled =
        LevelScaling.scaleCard(chargeData.chargeDamage(), card.getRarity(), TEST_LEVEL);

    assertThat(ability.getScaledChargeDamage()).isEqualTo(expectedScaled);
    assertThat(ability.getScaledChargeDamage()).isGreaterThan(chargeData.chargeDamage());
  }

  @Test
  void reflectDamage_shouldBeScaledByLevel() {
    Entity entity = deployCard("electrogiant", 9f, 10f);
    assertThat(entity).isInstanceOf(Troop.class);

    Troop troop = (Troop) entity;
    AbilityComponent ability = troop.getAbility();
    assertThat(ability).isNotNull();
    assertThat(ability.getData()).isInstanceOf(ReflectAbility.class);

    ReflectAbility reflectData = (ReflectAbility) ability.getData();
    Card card = Objects.requireNonNull(CardRegistry.get("electrogiant"));
    int expectedScaled =
        LevelScaling.scaleCard(reflectData.reflectDamage(), card.getRarity(), TEST_LEVEL);

    assertThat(ability.getScaledReflectDamage()).isEqualTo(expectedScaled);
    assertThat(ability.getScaledReflectDamage()).isGreaterThan(reflectData.reflectDamage());
  }

  @Test
  void variableDamage_shouldBeScaledByLevel() {
    Entity entity = deployCard("infernodragon", 9f, 10f);
    assertThat(entity).isInstanceOf(Troop.class);

    Troop troop = (Troop) entity;
    AbilityComponent ability = troop.getAbility();
    assertThat(ability).isNotNull();
    assertThat(ability.getData()).isInstanceOf(VariableDamageAbility.class);

    VariableDamageAbility varDmgData = (VariableDamageAbility) ability.getData();
    List<VariableDamageStage> stages = varDmgData.stages();
    assertThat(stages).isNotEmpty();

    List<Integer> scaledDamages = ability.getScaledVariableDamageStageDamages();
    assertThat(scaledDamages).hasSameSizeAs(stages);

    Card card = Objects.requireNonNull(CardRegistry.get("infernodragon"));
    for (int i = 0; i < stages.size(); i++) {
      int expectedScaled =
          LevelScaling.scaleCard(stages.get(i).damage(), card.getRarity(), TEST_LEVEL);
      assertThat(scaledDamages.get(i)).isEqualTo(expectedScaled);
      assertThat(scaledDamages.get(i)).isGreaterThan(stages.get(i).damage());
    }

    // getCurrentStageDamage() should return scaled value for stage 0
    assertThat(ability.getCurrentStageDamage()).isEqualTo(scaledDamages.get(0));
  }

  @Test
  void buildingVariableDamage_shouldBeScaledByLevel() {
    Entity entity = deployCard("infernotower", 9f, 10f);
    assertThat(entity).isInstanceOf(Building.class);

    Building building = (Building) entity;
    AbilityComponent ability = building.getAbility();
    assertThat(ability).isNotNull();
    assertThat(ability.getData()).isInstanceOf(VariableDamageAbility.class);

    VariableDamageAbility varDmgData = (VariableDamageAbility) ability.getData();
    List<VariableDamageStage> stages = varDmgData.stages();
    assertThat(stages).isNotEmpty();

    List<Integer> scaledDamages = ability.getScaledVariableDamageStageDamages();
    assertThat(scaledDamages).hasSameSizeAs(stages);

    Card card = Objects.requireNonNull(CardRegistry.get("infernotower"));
    for (int i = 0; i < stages.size(); i++) {
      int expectedScaled =
          LevelScaling.scaleCard(stages.get(i).damage(), card.getRarity(), TEST_LEVEL);
      assertThat(scaledDamages.get(i)).isEqualTo(expectedScaled);
      assertThat(scaledDamages.get(i)).isGreaterThan(stages.get(i).damage());
    }
  }
}
