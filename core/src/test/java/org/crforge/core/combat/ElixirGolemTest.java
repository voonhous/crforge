package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.crforge.core.ability.DefaultCombatAbilityBridge;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.DeathSpawnEntry;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.DeathHandlingSystem;
import org.crforge.core.entity.SpawnFactory;
import org.crforge.core.entity.SpawnerSystem;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for Elixir Golem's split chain and manaOnDeathForOpponent mechanic. ElixirGolem1 -> 2x
 * ElixirGolem2 -> 4x ElixirGolem4. Each death grants elixir to the opponent: 1 + 2*0.5 + 4*0.5 = 4
 * total.
 */
class ElixirGolemTest {

  private GameState gameState;
  private CombatSystem combatSystem;
  private SpawnerSystem spawnerSystem;
  private DeathHandlingSystem deathHandlingSystem;

  // Players for elixir grant testing
  private Player bluePlayer;
  private Player redPlayer;

  // ElixirGolem4: final form, no death spawns
  private static final TroopStats ELIXIR_GOLEM4_STATS =
      TroopStats.builder()
          .name("ElixirGolem4")
          .health(200)
          .damage(36)
          .speed(1.0f)
          .range(0.7f)
          .attackCooldown(1.3f)
          .movementType(MovementType.GROUND)
          .targetType(TargetType.GROUND)
          .targetOnlyBuildings(true)
          .manaOnDeathForOpponent(500)
          .build();

  // ElixirGolem2: mid form, death spawns 2 ElixirGolem4s
  private static final TroopStats ELIXIR_GOLEM2_STATS =
      TroopStats.builder()
          .name("ElixirGolem2")
          .health(400)
          .damage(36)
          .speed(1.0f)
          .range(0.7f)
          .attackCooldown(1.3f)
          .movementType(MovementType.GROUND)
          .targetType(TargetType.GROUND)
          .targetOnlyBuildings(true)
          .manaOnDeathForOpponent(500)
          .deathSpawns(
              List.of(new DeathSpawnEntry(ELIXIR_GOLEM4_STATS, 2, 0.6f, 0f, 0f, null, null)))
          .build();

  // ElixirGolem1: main form, death spawns 2 ElixirGolem2s
  private static final TroopStats ELIXIR_GOLEM1_STATS =
      TroopStats.builder()
          .name("ElixirGolem1")
          .health(800)
          .damage(36)
          .speed(1.0f)
          .range(0.7f)
          .attackCooldown(1.3f)
          .movementType(MovementType.GROUND)
          .targetType(TargetType.GROUND)
          .targetOnlyBuildings(true)
          .manaOnDeathForOpponent(1000)
          .deathSpawns(
              List.of(new DeathSpawnEntry(ELIXIR_GOLEM2_STATS, 2, 0.6f, 0f, 0f, null, null)))
          .build();

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    DefaultCombatAbilityBridge abilityBridge = new DefaultCombatAbilityBridge();
    AoeDamageService aoeDamageService = new AoeDamageService(gameState, abilityBridge);
    ProjectileSystem projectileSystem =
        new ProjectileSystem(gameState, aoeDamageService, abilityBridge);
    combatSystem = new CombatSystem(gameState, aoeDamageService, projectileSystem, abilityBridge);
    SpawnFactory spawnFactory = new SpawnFactory(gameState);
    spawnerSystem = new SpawnerSystem(gameState, spawnFactory);
    deathHandlingSystem = new DeathHandlingSystem(gameState, aoeDamageService, spawnFactory);
    gameState.setDeathHandler(deathHandlingSystem::onDeath);

    // Create a minimal match with players for elixir grant testing
    Standard1v1Match match = new Standard1v1Match();
    Deck emptyDeck =
        new Deck(
            List.of(
                Card.builder().id("test").name("Test").type(CardType.TROOP).cost(1).build(),
                Card.builder().id("test2").name("Test2").type(CardType.TROOP).cost(1).build(),
                Card.builder().id("test3").name("Test3").type(CardType.TROOP).cost(1).build(),
                Card.builder().id("test4").name("Test4").type(CardType.TROOP).cost(1).build(),
                Card.builder().id("test5").name("Test5").type(CardType.TROOP).cost(1).build(),
                Card.builder().id("test6").name("Test6").type(CardType.TROOP).cost(1).build(),
                Card.builder().id("test7").name("Test7").type(CardType.TROOP).cost(1).build(),
                Card.builder().id("test8").name("Test8").type(CardType.TROOP).cost(1).build()));

    bluePlayer = new Player(Team.BLUE, emptyDeck, false);
    redPlayer = new Player(Team.RED, emptyDeck, false);
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);
    deathHandlingSystem.setMatch(match);
  }

  @Test
  void deathSpawnChain_producesAllSevenEntities() {
    Troop golem = createElixirGolem(Team.BLUE, 5, 5, ELIXIR_GOLEM1_STATS);

    gameState.spawnEntity(golem);
    gameState.processPending();

    // Kill ElixirGolem1
    golem.getHealth().takeDamage(10000);
    gameState.processDeaths();
    gameState.processPending();

    // Should have 2 ElixirGolem2s
    long golem2Count =
        gameState.getAliveEntities().stream()
            .filter(e -> e instanceof Troop t && "ElixirGolem2".equals(t.getName()))
            .count();
    assertThat(golem2Count).as("2 ElixirGolem2 should spawn from ElixirGolem1 death").isEqualTo(2);

    // Kill both ElixirGolem2s
    gameState.getAliveEntities().stream()
        .filter(e -> e instanceof Troop t && "ElixirGolem2".equals(t.getName()))
        .forEach(e -> e.getHealth().takeDamage(10000));
    gameState.processDeaths();
    gameState.processPending();

    // Should have 4 ElixirGolem4s
    long golem4Count =
        gameState.getAliveEntities().stream()
            .filter(e -> e instanceof Troop t && "ElixirGolem4".equals(t.getName()))
            .count();
    assertThat(golem4Count)
        .as("4 ElixirGolem4 should spawn from 2 ElixirGolem2 deaths")
        .isEqualTo(4);
  }

  @Test
  void elixirGrantOnDeath_opponentGetsElixir() {
    // Blue deploys ElixirGolem, Red should get elixir when it dies
    Troop golem = createElixirGolem(Team.BLUE, 5, 5, ELIXIR_GOLEM1_STATS);

    // Set red player's elixir to a known value
    redPlayer.getElixir().spend(5); // Now at 0
    float redElixirBefore = redPlayer.getElixir().getCurrent();
    assertThat(redElixirBefore).isEqualTo(0f);

    gameState.spawnEntity(golem);
    gameState.processPending();

    // Kill ElixirGolem1 (grants 1000 milli-elixir = 1.0 elixir to opponent)
    golem.getHealth().takeDamage(10000);
    gameState.processDeaths();

    assertThat(redPlayer.getElixir().getCurrent())
        .as("Red should receive 1.0 elixir from ElixirGolem1 death")
        .isCloseTo(1.0f, within(0.01f));
  }

  @Test
  void fullChainElixir_opponentReceives4Total() {
    Troop golem = createElixirGolem(Team.BLUE, 5, 5, ELIXIR_GOLEM1_STATS);

    // Reset red elixir to 0
    redPlayer.getElixir().spend(5);
    assertThat(redPlayer.getElixir().getCurrent()).isEqualTo(0f);

    gameState.spawnEntity(golem);
    gameState.processPending();

    // Kill ElixirGolem1 -> +1.0 elixir
    golem.getHealth().takeDamage(10000);
    gameState.processDeaths();
    gameState.processPending();

    assertThat(redPlayer.getElixir().getCurrent())
        .as("After ElixirGolem1 death: +1.0")
        .isCloseTo(1.0f, within(0.01f));

    // Kill both ElixirGolem2s -> +0.5 each = +1.0
    gameState.getAliveEntities().stream()
        .filter(e -> e instanceof Troop t && "ElixirGolem2".equals(t.getName()))
        .forEach(e -> e.getHealth().takeDamage(10000));
    gameState.processDeaths();
    gameState.processPending();

    assertThat(redPlayer.getElixir().getCurrent())
        .as("After 2 ElixirGolem2 deaths: +1.0 total")
        .isCloseTo(2.0f, within(0.01f));

    // Kill all 4 ElixirGolem4s -> +0.5 each = +2.0
    gameState.getAliveEntities().stream()
        .filter(e -> e instanceof Troop t && "ElixirGolem4".equals(t.getName()))
        .forEach(e -> e.getHealth().takeDamage(10000));
    gameState.processDeaths();

    assertThat(redPlayer.getElixir().getCurrent())
        .as("Full chain should grant 4.0 total elixir to opponent")
        .isCloseTo(4.0f, within(0.01f));
  }

  @Test
  void elixirCapAt10_doesNotExceedMax() {
    Troop golem = createElixirGolem(Team.BLUE, 5, 5, ELIXIR_GOLEM1_STATS);

    // Set red elixir to 9.5
    redPlayer.getElixir().spend(5); // 0
    redPlayer.getElixir().add(9.5f); // 9.5

    gameState.spawnEntity(golem);
    gameState.processPending();

    // Kill ElixirGolem1 -> would add 1.0, but should cap at 10
    golem.getHealth().takeDamage(10000);
    gameState.processDeaths();

    assertThat(redPlayer.getElixir().getCurrent())
        .as("Elixir should cap at 10.0")
        .isCloseTo(10.0f, within(0.01f));
  }

  @Test
  void buildingTargeting_elixirGolemTargetsBuildings() {
    assertThat(ELIXIR_GOLEM1_STATS.isTargetOnlyBuildings())
        .as("ElixirGolem1 should target only buildings")
        .isTrue();
    assertThat(ELIXIR_GOLEM2_STATS.isTargetOnlyBuildings())
        .as("ElixirGolem2 should target only buildings")
        .isTrue();
    assertThat(ELIXIR_GOLEM4_STATS.isTargetOnlyBuildings())
        .as("ElixirGolem4 should target only buildings")
        .isTrue();
  }

  @Test
  void noElixirToOwnTeam_elixirGoesToOpponent() {
    // Blue deploys ElixirGolem -- blue should NOT get elixir
    Troop golem = createElixirGolem(Team.BLUE, 5, 5, ELIXIR_GOLEM1_STATS);

    float blueElixirBefore = bluePlayer.getElixir().getCurrent();

    gameState.spawnEntity(golem);
    gameState.processPending();

    golem.getHealth().takeDamage(10000);
    gameState.processDeaths();

    assertThat(bluePlayer.getElixir().getCurrent())
        .as("Blue (owner) should not receive elixir from own ElixirGolem death")
        .isCloseTo(blueElixirBefore, within(0.01f));
  }

  private Troop createElixirGolem(Team team, float x, float y, TroopStats stats) {
    SpawnerComponent spawner =
        SpawnerComponent.builder()
            .deathSpawns(stats.getDeathSpawns())
            .manaOnDeathForOpponent(stats.getManaOnDeathForOpponent())
            .level(1)
            .build();

    Combat combat =
        Combat.builder()
            .damage(stats.getDamage())
            .range(stats.getRange())
            .sightRange(stats.getSightRange())
            .attackCooldown(stats.getAttackCooldown())
            .targetOnlyBuildings(stats.isTargetOnlyBuildings())
            .targetType(stats.getTargetType())
            .build();

    return Troop.builder()
        .name(stats.getName())
        .team(team)
        .position(new Position(x, y))
        .health(new Health(stats.getHealth()))
        .deployTime(1.0f)
        .movement(
            new Movement(
                stats.getSpeed(),
                stats.getMass(),
                stats.getCollisionRadius(),
                stats.getVisualRadius(),
                stats.getMovementType()))
        .combat(combat)
        .spawner(spawner)
        .build();
  }
}
