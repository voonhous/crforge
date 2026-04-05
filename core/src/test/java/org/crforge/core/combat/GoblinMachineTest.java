package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.ability.AbilityType;
import org.crforge.core.ability.RangedAttackAbility;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Tower;
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
 * Tests for Goblin Machine: a 5-elixir Legendary troop with dual independent attacks. Primary
 * attack is a melee ground punch. Secondary attack is a ranged AoE rocket (RANGED_ATTACK ability)
 * that fires independently at targets in the [minimumRange, range] window.
 *
 * <p>Edge-to-edge distance = center distance - source.collisionRadius(0.75) -
 * target.collisionRadius(0.5) = center distance - 1.25. Rocket fires when edge distance is in [2.5,
 * 5.0], so center distance must be in [3.75, 6.25].
 *
 * <p>Rocket timing: 1 tick pending + 45 ticks WINDING_UP (1.5s) + 30 ticks ATTACK_DELAY (1.0s) +
 * ~31 ticks travel (4.25 tiles at 4.17 tiles/sec) = ~107 ticks to first hit.
 */
class GoblinMachineTest {

  private GameEngine engine;
  private Player bluePlayer;

  private static final Card GOBLIN_MACHINE = CardRegistry.get("goblinmachine");

  private static final float DEPLOY_X = 9f;
  private static final float DEPLOY_Y = 10f;

  // Center distance that gives edge distance 3.0 (within rocket range [2.5, 5.0])
  // edge = 4.25 - 0.75 - 0.5 = 3.0
  private static final float ROCKET_RANGE_OFFSET = 4.25f;

  // Enough ticks for the rocket to fire AND travel to the target (~3.57s + margin)
  private static final int ROCKET_HIT_TICKS = 4 * GameEngine.TICKS_PER_SECOND;

  // 1.0s placement sync delay
  private static final int SYNC_DELAY_TICKS = GameEngine.TICKS_PER_SECOND;
  // 1.0s deploy time
  private static final int DEPLOY_TICKS = GameEngine.TICKS_PER_SECOND;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    engine = new GameEngine();

    Deck deck = buildDeckWith(GOBLIN_MACHINE);
    bluePlayer = new Player(Team.BLUE, deck, false);
    Player redPlayer = new Player(Team.RED, deck, false);

    Standard1v1Match match = new Standard1v1Match(1);
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);
    engine.setMatch(match);
    engine.initMatch();

    bluePlayer.getElixir().add(10);
  }

  @Test
  void cardDataLoadsCorrectly() {
    assertThat(GOBLIN_MACHINE).as("Card should be loaded").isNotNull();
    assertThat(GOBLIN_MACHINE.getType()).as("Card type").isEqualTo(CardType.TROOP);
    assertThat(GOBLIN_MACHINE.getCost()).as("Elixir cost").isEqualTo(5);
    assertThat(GOBLIN_MACHINE.getRarity()).as("Rarity").isEqualTo(Rarity.LEGENDARY);

    TroopStats stats = GOBLIN_MACHINE.getUnitStats();
    assertThat(stats).as("Unit stats").isNotNull();
    assertThat(stats.getHealth()).as("Health").isEqualTo(840);
    assertThat(stats.getDamage()).as("Damage").isEqualTo(83);
    assertThat(stats.getRange()).as("Range").isEqualTo(1.2f);
    assertThat(stats.getTargetType()).as("Primary target type").isEqualTo(TargetType.GROUND);
    assertThat(stats.isIgnorePushback()).as("Ignore pushback").isTrue();

    // RANGED_ATTACK ability
    assertThat(stats.getAbility()).as("Ability").isNotNull();
    assertThat(stats.getAbility().type()).as("Ability type").isEqualTo(AbilityType.RANGED_ATTACK);
    assertThat(stats.getAbility()).isInstanceOf(RangedAttackAbility.class);

    RangedAttackAbility ra = (RangedAttackAbility) stats.getAbility();
    assertThat(ra.projectile()).as("Rocket projectile").isNotNull();
    assertThat(ra.projectile().getName())
        .as("Projectile name")
        .isEqualTo("GoblinMachineRocketProjectile");
    assertThat(ra.range()).as("Rocket range").isEqualTo(5.0f);
    assertThat(ra.minimumRange()).as("Rocket minimum range").isEqualTo(2.5f);
    assertThat(ra.loadTime()).as("Rocket load time").isEqualTo(1.5f);
    assertThat(ra.attackDelay()).as("Rocket attack delay").isEqualTo(1.0f);
    assertThat(ra.attackCooldown()).as("Rocket attack cooldown").isEqualTo(2.5f);
    assertThat(ra.targetType()).as("Rocket target type").isEqualTo(TargetType.ALL);
  }

  @Test
  void goblinMachineSpawnsAsTroopWithAbility() {
    Troop machine = deployAndGetTroop();

    assertThat(machine.getAbility()).as("Ability component").isNotNull();
    assertThat(machine.getAbility().getData().type())
        .as("Ability type on entity")
        .isEqualTo(AbilityType.RANGED_ATTACK);
  }

  @Test
  void primaryMeleeAttackHitsGroundTarget() {
    Troop machine = deployAndGetTroop();

    // Place enemy within melee range (range=1.2, edge distance = 1.0 - 1.25 = -0.25 -> overlap)
    Troop enemy =
        createDummyEnemy(
            Team.RED,
            machine.getPosition().getX() + 1.0f,
            machine.getPosition().getY(),
            500,
            MovementType.GROUND);
    engine.getGameState().spawnEntity(enemy);

    int hpBefore = enemy.getHealth().getCurrent();
    engine.tick(60);

    assertThat(enemy.getHealth().getCurrent())
        .as("Enemy should have taken melee damage")
        .isLessThan(hpBefore);
  }

  @Test
  void rocketFiresAtTargetInRange() {
    Troop machine = deployAndGetTroop();
    disableBlueTowers();

    // Use AIR target so only the rocket can hit (melee targets GROUND only)
    Troop airEnemy =
        createDummyEnemy(
            Team.RED,
            machine.getPosition().getX() + ROCKET_RANGE_OFFSET,
            machine.getPosition().getY(),
            1000,
            MovementType.AIR);
    engine.getGameState().spawnEntity(airEnemy);
    machine.getMovement().setMovementDisabled(ModifierSource.ABILITY_TUNNEL, true);

    engine.tick(ROCKET_HIT_TICKS);

    assertThat(airEnemy.getHealth().getCurrent())
        .as("Rocket should have fired and hit the target")
        .isLessThan(1000);
  }

  @Test
  void rocketDoesNotFireAtTargetInDeadZone() {
    Troop machine = deployAndGetTroop();
    disableBlueTowers();

    // AIR target in the dead zone (edge distance = 2.0 - 1.25 = 0.75, below minimumRange 2.5)
    Troop airEnemy =
        createDummyEnemy(
            Team.RED,
            machine.getPosition().getX() + 2.0f,
            machine.getPosition().getY(),
            1000,
            MovementType.AIR);
    engine.getGameState().spawnEntity(airEnemy);
    machine.getMovement().setMovementDisabled(ModifierSource.ABILITY_TUNNEL, true);

    engine.tick(ROCKET_HIT_TICKS);

    // 0 damage: melee can't hit AIR, rocket can't fire in dead zone
    assertThat(airEnemy.getHealth().getCurrent())
        .as("No damage to air target in dead zone")
        .isEqualTo(1000);
  }

  @Test
  void rocketTargetsAirUnits() {
    Troop machine = deployAndGetTroop();
    disableBlueTowers();

    // AIR enemy at rocket range -- melee can't hit AIR, so any damage = rocket
    Troop airEnemy =
        createDummyEnemy(
            Team.RED,
            machine.getPosition().getX() + ROCKET_RANGE_OFFSET,
            machine.getPosition().getY(),
            1000,
            MovementType.AIR);
    engine.getGameState().spawnEntity(airEnemy);
    machine.getMovement().setMovementDisabled(ModifierSource.ABILITY_TUNNEL, true);

    engine.tick(ROCKET_HIT_TICKS);

    assertThat(airEnemy.getHealth().getCurrent())
        .as("Air enemy should take rocket damage")
        .isLessThan(1000);
  }

  @Test
  void rocketProjectileHasCorrectStats() {
    Troop machine = deployAndGetTroop();
    disableBlueTowers();

    Troop enemy =
        createDummyEnemy(
            Team.RED,
            machine.getPosition().getX() + ROCKET_RANGE_OFFSET,
            machine.getPosition().getY(),
            5000,
            MovementType.AIR);
    engine.getGameState().spawnEntity(enemy);
    machine.getMovement().setMovementDisabled(ModifierSource.ABILITY_TUNNEL, true);

    // Tick just past the fire point (76 ticks) to catch the projectile in-flight.
    // Rocket speed ~4.17 tiles/sec, travel ~4.25 tiles = ~31 ticks. So at tick 80 it's in-flight.
    engine.tick(80);

    List<Projectile> projectiles = engine.getGameState().getProjectiles();
    Projectile rocket =
        projectiles.stream()
            .filter(p -> p.getTeam() == Team.BLUE && p.getAoeRadius() > 0)
            .findFirst()
            .orElse(null);

    if (rocket != null) {
      assertThat(rocket.getAoeRadius()).as("Rocket AOE radius").isEqualTo(1.5f);
      assertThat(rocket.getCrownTowerDamagePercent())
          .as("Crown tower damage percent")
          .isEqualTo(-50);
    } else {
      // Rocket already hit (unlikely at 80 ticks) -- verify damage was dealt
      assertThat(enemy.getHealth().getCurrent())
          .as("Enemy should have taken rocket damage")
          .isLessThan(5000);
    }
  }

  @Test
  void dualAttackIndependence() {
    Troop machine = deployAndGetTroop();
    disableBlueTowers();

    // Melee-range GROUND enemy (melee hits GROUND, rocket can't reach melee range)
    Troop meleeTarget =
        createDummyEnemy(
            Team.RED,
            machine.getPosition().getX() + 1.0f,
            machine.getPosition().getY(),
            2000,
            MovementType.GROUND);
    engine.getGameState().spawnEntity(meleeTarget);

    // Rocket-range AIR enemy (only rocket can hit AIR, melee is GROUND-only)
    Troop rocketTarget =
        createDummyEnemy(
            Team.RED,
            machine.getPosition().getX() + ROCKET_RANGE_OFFSET,
            machine.getPosition().getY(),
            2000,
            MovementType.AIR);
    engine.getGameState().spawnEntity(rocketTarget);

    engine.tick(ROCKET_HIT_TICKS);

    assertThat(meleeTarget.getHealth().getCurrent())
        .as("Melee target should take melee damage")
        .isLessThan(2000);
    assertThat(rocketTarget.getHealth().getCurrent())
        .as("Rocket target should take rocket damage")
        .isLessThan(2000);
  }

  @Test
  void rocketCooldownPreventsFiring() {
    Troop machine = deployAndGetTroop();
    disableBlueTowers();

    // AIR target so only the rocket deals damage (melee is GROUND-only)
    Troop airEnemy =
        createDummyEnemy(
            Team.RED,
            machine.getPosition().getX() + ROCKET_RANGE_OFFSET,
            machine.getPosition().getY(),
            10000,
            MovementType.AIR);
    engine.getGameState().spawnEntity(airEnemy);
    machine.getMovement().setMovementDisabled(ModifierSource.ABILITY_TUNNEL, true);

    // Wait for first rocket to hit (~107 ticks + margin)
    engine.tick(ROCKET_HIT_TICKS);
    int hpAfterFirst = airEnemy.getHealth().getCurrent();
    assertThat(hpAfterFirst).as("First rocket hit").isLessThan(10000);
    int firstRocketDamage = 10000 - hpAfterFirst;

    // Tick 1 second -- deep in cooldown (2.5s), no second rocket yet
    engine.tick(GameEngine.TICKS_PER_SECOND);
    int hpAfterCooldownWait = airEnemy.getHealth().getCurrent();
    assertThat(hpAfterCooldownWait)
        .as("No additional rocket damage during cooldown")
        .isEqualTo(hpAfterFirst);

    // Wait for second cycle: cooldown(2.5) + loadTime(1.5) + attackDelay(1.0) + travel(~1.0)
    // = 6.0s. Already 1s past first hit, so need ~5s more.
    engine.tick(6 * GameEngine.TICKS_PER_SECOND);

    int hpAfterSecond = airEnemy.getHealth().getCurrent();
    assertThat(hpAfterSecond)
        .as("Second rocket should have hit after cooldown")
        .isLessThan(hpAfterCooldownWait);

    int secondRocketDamage = hpAfterCooldownWait - hpAfterSecond;
    assertThat(secondRocketDamage)
        .as("Second rocket deals same damage as first")
        .isEqualTo(firstRocketDamage);
  }

  @Test
  void ignorePushbackIsSet() {
    Troop machine = deployAndGetTroop();

    assertThat(machine.getMovement().isIgnorePushback())
        .as("Ignore pushback should be set")
        .isTrue();
  }

  // -- Helpers --

  private Troop deployAndGetTroop() {
    PlayerActionDTO action = PlayerActionDTO.builder().handIndex(0).x(DEPLOY_X).y(DEPLOY_Y).build();
    engine.queueAction(bluePlayer, action);
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    Troop troop = findGoblinMachine();
    assertThat(troop).as("GoblinMachine should be deployed").isNotNull();
    return troop;
  }

  private Troop findGoblinMachine() {
    return engine.getGameState().getEntitiesOfType(Troop.class).stream()
        .filter(t -> "GoblinMachine".equals(t.getName()) && t.getTeam() == Team.BLUE)
        .filter(Entity::isAlive)
        .findFirst()
        .orElse(null);
  }

  private Troop createDummyEnemy(Team team, float x, float y, int hp, MovementType movementType) {
    return Troop.builder()
        .name("DummyTarget")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(hp))
        .movement(new Movement(0, 10, 0.5f, 0.5f, movementType))
        .combat(Combat.builder().build())
        .deployTime(0f)
        .deployTimer(0f)
        .build();
  }

  private void disableBlueTowers() {
    for (Entity e : engine.getGameState().getAliveEntities()) {
      if (e instanceof Tower tower && tower.getTeam() == Team.BLUE && tower.getCombat() != null) {
        tower.getCombat().setCombatDisabled(ModifierSource.ABILITY_TUNNEL, true);
      }
    }
  }

  private Deck buildDeckWith(Card card) {
    List<Card> cards = new java.util.ArrayList<>();
    for (int i = 0; i < 8; i++) {
      cards.add(card);
    }
    return new Deck(cards);
  }
}
