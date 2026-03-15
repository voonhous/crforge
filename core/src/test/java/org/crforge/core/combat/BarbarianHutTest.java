package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.Rarity;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for Barbarian Hut: a 6-elixir Rare spawner building that produces waves of 3 Barbarians
 * every 14s (0.5s interval between units in a wave), and spawns 1 Barbarian on death. The building
 * has no attack (damage=0) and a 30s lifetime.
 */
class BarbarianHutTest {

  private GameEngine engine;
  private Player bluePlayer;

  private static final Card BARBARIAN_HUT = CardRegistry.get("barbarianhut");

  // Deploy at y=10 on blue side, away from towers
  private static final float DEPLOY_X = 9f;
  private static final float DEPLOY_Y = 10f;

  // 1.0s placement sync delay = 30 ticks
  private static final int SYNC_DELAY_TICKS = 30;
  // 1.0s building deploy time = 30 ticks
  private static final int DEPLOY_TICKS = 30;
  // 0.5s interval between units in a wave = 16 ticks (float 0.5/deltaTime rounds up)
  private static final int SPAWN_INTERVAL_TICKS = 16;
  // 14.0s pause between waves = 420 ticks
  private static final int WAVE_PAUSE_TICKS = 420;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    engine = new GameEngine();

    Deck blueDeck = buildDeckWith(BARBARIAN_HUT);
    Deck redDeck = buildDeckWith(BARBARIAN_HUT);

    bluePlayer = new Player(Team.BLUE, blueDeck, false);
    Player redPlayer = new Player(Team.RED, redDeck, false);

    Standard1v1Match match = new Standard1v1Match(1);
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);
    engine.setMatch(match);
    engine.initMatch();

    bluePlayer.getElixir().add(10);
  }

  @Test
  void cardDataLoadsCorrectly() {
    assertThat(BARBARIAN_HUT).as("Card should be loaded").isNotNull();
    assertThat(BARBARIAN_HUT.getType()).as("Card type").isEqualTo(CardType.BUILDING);
    assertThat(BARBARIAN_HUT.getCost()).as("Elixir cost").isEqualTo(6);
    assertThat(BARBARIAN_HUT.getRarity()).as("Rarity").isEqualTo(Rarity.RARE);

    assertThat(BARBARIAN_HUT.getUnitStats()).as("Unit stats").isNotNull();
    assertThat(BARBARIAN_HUT.getUnitStats().getHealth()).as("Health").isEqualTo(455);
    assertThat(BARBARIAN_HUT.getUnitStats().getDamage()).as("Damage").isEqualTo(0);
    assertThat(BARBARIAN_HUT.getUnitStats().getLifeTime()).as("Lifetime").isEqualTo(30.0f);

    assertThat(BARBARIAN_HUT.getUnitStats().getLiveSpawn()).as("Live spawn config").isNotNull();
    assertThat(BARBARIAN_HUT.getUnitStats().getLiveSpawn().spawnCharacter())
        .as("Spawn character")
        .isEqualTo("Barbarian");
    assertThat(BARBARIAN_HUT.getUnitStats().getLiveSpawn().spawnNumber())
        .as("Spawn number per wave")
        .isEqualTo(3);
    assertThat(BARBARIAN_HUT.getUnitStats().getLiveSpawn().spawnPauseTime())
        .as("Wave pause time")
        .isEqualTo(14.0f);
    assertThat(BARBARIAN_HUT.getUnitStats().getLiveSpawn().spawnInterval())
        .as("Spawn interval")
        .isEqualTo(0.5f);

    assertThat(BARBARIAN_HUT.getUnitStats().getDeathSpawns()).as("Death spawns").hasSize(1);
  }

  @Test
  void buildingCreatedOnDeploy() {
    deployBarbarianHut(DEPLOY_X, DEPLOY_Y);

    // Tick past sync delay so the building is spawned
    engine.tick(SYNC_DELAY_TICKS + 2);

    Building building = findBuilding();
    assertThat(building).as("BarbarianHut building should exist").isNotNull();
    assertThat(building.getTeam()).as("Team").isEqualTo(Team.BLUE);
    assertThat(building.getHealth().getMax()).as("Max HP").isEqualTo(455);
    assertThat(building.getSpawner()).as("SpawnerComponent").isNotNull();
  }

  @Test
  void buildingHasNoAttack() {
    deployBarbarianHut(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + 2);

    Building building = findBuilding();
    assertThat(building.getCombat())
        .as("Building should have no combat component (damage=0)")
        .isNull();
  }

  @Test
  void firstWaveSpawnsThreeBarbarians() {
    deployBarbarianHut(DEPLOY_X, DEPLOY_Y);

    // Tick past sync + deploy + 2 extra ticks:
    // tick 61 = spawner fires (barbarian queued), tick 62 = processPending adds to alive list
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);
    assertThat(countBarbarians()).as("After deploy ends, 1st Barbarian").isEqualTo(1);

    // 0.5s later: second barbarian (spawned tick 77, visible tick 78)
    engine.tick(SPAWN_INTERVAL_TICKS);
    assertThat(countBarbarians()).as("After 0.5s interval, 2nd Barbarian").isEqualTo(2);

    // 0.5s later: third barbarian (wave complete, spawned tick 93, visible tick 94)
    engine.tick(SPAWN_INTERVAL_TICKS);
    assertThat(countBarbarians()).as("After another 0.5s, 3rd Barbarian").isEqualTo(3);

    // Verify all are blue team Barbarians
    List<Troop> barbarians =
        engine.getGameState().getEntitiesOfType(Troop.class).stream()
            .filter(t -> "Barbarian".equals(t.getName()) && t.getTeam() == Team.BLUE)
            .toList();
    assertThat(barbarians).as("All spawned units should be blue Barbarians").hasSize(3);
  }

  @Test
  void secondWaveSpawnsAfterPauseTime() {
    deployBarbarianHut(DEPLOY_X, DEPLOY_Y);

    // Tick to end of first wave: 3rd barbarian visible at tick 94
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2 + SPAWN_INTERVAL_TICKS + SPAWN_INTERVAL_TICKS);
    assertThat(countBarbarians()).as("Wave 1 complete").isEqualTo(3);

    // Tick through most of the 14s pause - wave 2 should not have started yet
    // 3rd barbarian spawned at tick 93, pause timer = 14.0s = 420 ticks
    // Wave 2 first spawn at tick 513, visible at tick 514
    engine.tick(WAVE_PAUSE_TICKS - 5);
    assertThat(countBarbarians()).as("Before wave 2 starts").isEqualTo(3);

    // Tick past the pause to trigger wave 2
    engine.tick(10);
    assertThat(countBarbarians()).as("Wave 2 started, 4th Barbarian").isGreaterThanOrEqualTo(4);

    // Complete wave 2
    engine.tick(SPAWN_INTERVAL_TICKS + SPAWN_INTERVAL_TICKS);
    assertThat(countBarbarians()).as("Wave 2 complete, 6 total").isGreaterThanOrEqualTo(6);
  }

  @Test
  void deathSpawnOneBarbarian() {
    deployBarbarianHut(DEPLOY_X, DEPLOY_Y);

    // Tick past sync + deploy + first wave (3rd barbarian visible at tick 94)
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2 + SPAWN_INTERVAL_TICKS + SPAWN_INTERVAL_TICKS);
    long beforeDeath = countBarbarians();
    assertThat(beforeDeath).as("Wave 1 barbarians").isEqualTo(3);

    // Kill the building
    Building building = findBuilding();
    building.getHealth().takeDamage(building.getHealth().getCurrent());

    // Tick 1: processDeaths fires onDeath handler, death spawn queued
    // Tick 2: processPending adds death-spawned Barbarian to alive list
    engine.tick(2);

    assertThat(countBarbarians())
        .as("Death spawn should add exactly 1 Barbarian")
        .isEqualTo(beforeDeath + 1);
  }

  @Test
  void buildingDiesAfterLifetime() {
    deployBarbarianHut(DEPLOY_X, DEPLOY_Y);

    // Tick past sync + deploy
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    Building building = findBuilding();
    assertThat(building.getHealth().isDead()).as("Building alive after deploy").isFalse();

    // Tick through 30s lifetime (900 ticks) plus a bit extra
    engine.tick(900 + 5);

    assertThat(building.getHealth().isDead())
        .as("Building should be dead after 30s lifetime")
        .isTrue();

    // Death spawn barbarian should have appeared
    // Wave spawns during lifetime + 1 death spawn
    long barbarians = countBarbarians();
    assertThat(barbarians).as("Should have barbarians from waves + 1 death spawn").isGreaterThan(3);
  }

  // -- Helpers --

  private void deployBarbarianHut(float x, float y) {
    PlayerActionDTO action = PlayerActionDTO.builder().handIndex(0).x(x).y(y).build();
    engine.queueAction(bluePlayer, action);
  }

  private long countBarbarians() {
    return engine.getGameState().getEntitiesOfType(Troop.class).stream()
        .filter(t -> "Barbarian".equals(t.getName()) && t.getTeam() == Team.BLUE)
        .count();
  }

  private Building findBuilding() {
    return engine.getGameState().getEntitiesOfType(Building.class).stream()
        .filter(b -> "BarbarianHut".equals(b.getName()) && b.getTeam() == Team.BLUE)
        .findFirst()
        .orElse(null);
  }

  private Deck buildDeckWith(Card card) {
    List<Card> cards = new java.util.ArrayList<>();
    for (int i = 0; i < 8; i++) {
      cards.add(card);
    }
    return new Deck(cards);
  }
}
