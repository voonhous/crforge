package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.effect.AreaEffect;
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
 * Tests for Royal Delivery spell: a 3-elixir Common spell with a delayed area effect (2.0s tick
 * dealing 171 base damage) followed by a DeliveryRecruit troop spawn at 2.05s. The area effect has
 * ignoreBuildings=true and onlyEnemies=true.
 */
class RoyalDeliveryTest {

  private GameEngine engine;
  private Player bluePlayer;

  private static final Card ROYAL_DELIVERY = CardRegistry.get("royaldelivery");
  private static final int BASE_DAMAGE = 171;

  // Deploy at y=14 to avoid tower aggro (towers are at y=3-6, range ~7.5 tiles)
  private static final float DEPLOY_X = 9f;
  private static final float DEPLOY_Y = 14f;

  // Placement sync delay (1.0s) before the area effect entity appears
  private static final int SYNC_DELAY_TICKS = GameEngine.TICKS_PER_SECOND;

  // Area effect hitSpeed is 2.0s (damage fires at this delay after AE creation)
  private static final int DAMAGE_DELAY_TICKS = 2 * GameEngine.TICKS_PER_SECOND;

  // Spawn fires at 2.05s after AE creation
  private static final int SPAWN_DELAY_TICKS = (int) (2.05f * GameEngine.TICKS_PER_SECOND) + 1;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    engine = new GameEngine();

    Deck blueDeck = buildDeckWith(ROYAL_DELIVERY);
    Deck redDeck = buildDeckWith(ROYAL_DELIVERY);

    bluePlayer = new Player(Team.BLUE, blueDeck, false);
    Player redPlayer = new Player(Team.RED, redDeck, false);

    // Use tower level 1 to minimize tower interference in tests
    Standard1v1Match match = new Standard1v1Match(1);
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);
    engine.setMatch(match);
    engine.initMatch();

    // Give blue player enough elixir
    bluePlayer.getElixir().add(10);
  }

  @Test
  void areaEffectCreatedOnDeploy() {
    deployRoyalDelivery(DEPLOY_X, DEPLOY_Y);

    // After sync delay, an AreaEffect entity should appear
    engine.tick(SYNC_DELAY_TICKS + 2);

    List<AreaEffect> effects = engine.getGameState().getEntitiesOfType(AreaEffect.class);
    assertThat(effects)
        .as("Royal Delivery should create an AreaEffect named RoyalDeliveryArea")
        .anyMatch(e -> "RoyalDeliveryArea".equals(e.getName()));
  }

  @Test
  void damageDealtAfterHitSpeedDelay() {
    Troop enemy = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 1000);

    deployRoyalDelivery(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DAMAGE_DELAY_TICKS + 2);

    assertThat(enemy.getHealth().getCurrent())
        .as("Enemy should take %d base damage from Royal Delivery area effect", BASE_DAMAGE)
        .isEqualTo(1000 - BASE_DAMAGE);
  }

  @Test
  void deliveryRecruitSpawnsAfterDelay() {
    deployRoyalDelivery(DEPLOY_X, DEPLOY_Y);

    // Tick past sync delay + spawn delay + extra for processing
    engine.tick(SYNC_DELAY_TICKS + SPAWN_DELAY_TICKS + 5);

    long recruitCount = countRecruits();
    assertThat(recruitCount)
        .as("A DeliveryRecruit troop should spawn for the blue team")
        .isEqualTo(1);
  }

  @Test
  void deliveryRecruitHasCorrectBaseStats() {
    deployRoyalDelivery(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + SPAWN_DELAY_TICKS + 5);

    Troop recruit = findRecruit();

    // Level 1 base stats from units.json
    assertThat(recruit.getHealth().getMax()).as("HP").isEqualTo(214);
    assertThat(recruit.getCombat().getDamage()).as("Damage").isEqualTo(52);
    assertThat(recruit.getHealth().getShieldMax()).as("Shield HP").isEqualTo(94);
    assertThat(recruit.getMovementType()).as("Movement type").isEqualTo(MovementType.GROUND);
  }

  @Test
  void cardDataHasIgnoreBuildingsFlag() {
    assertThat(ROYAL_DELIVERY).as("Card should be loaded").isNotNull();
    assertThat(ROYAL_DELIVERY.getAreaEffect()).as("Area effect should exist").isNotNull();
    assertThat(ROYAL_DELIVERY.getAreaEffect().isIgnoreBuildings())
        .as("Area effect should have ignoreBuildings=true")
        .isTrue();
    assertThat(ROYAL_DELIVERY.getAreaEffect().getSpawnCharacter())
        .as("Area effect should have a spawn character")
        .isNotNull();
    assertThat(ROYAL_DELIVERY.getAreaEffect().getSpawnCharacter().getName())
        .as("Spawn character should be DeliveryRecruit")
        .isEqualTo("DeliveryRecruit");
  }

  @Test
  void damageIgnoresBuildings() {
    // Use lifetime=0 to prevent building HP decay from interfering with the test
    Building building =
        Building.builder()
            .name("Cannon")
            .team(Team.RED)
            .position(new Position(DEPLOY_X, DEPLOY_Y))
            .health(new Health(500))
            .movement(new Movement(0, 0, 0.5f, 0.5f, MovementType.BUILDING))
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    engine.spawn(building);

    deployRoyalDelivery(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DAMAGE_DELAY_TICKS + 2);

    assertThat(building.getHealth().getCurrent())
        .as("Building should NOT take damage (ignoreBuildings=true)")
        .isEqualTo(500);
  }

  @Test
  void damageHitsAirAndGround() {
    // Ground enemy
    Troop groundEnemy = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 1000);

    // Air enemy
    Troop airEnemy =
        Troop.builder()
            .name("AirEnemy")
            .team(Team.RED)
            .position(new Position(DEPLOY_X, DEPLOY_Y + 0.1f))
            .health(new Health(1000))
            .movement(new Movement(1.0f, 1.0f, 0.5f, 0.5f, MovementType.AIR))
            .combat(
                Combat.builder()
                    .damage(0)
                    .range(1.0f)
                    .sightRange(5.0f)
                    .attackCooldown(1.0f)
                    .targetType(TargetType.GROUND)
                    .build())
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    engine.spawn(airEnemy);

    deployRoyalDelivery(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DAMAGE_DELAY_TICKS + 2);

    assertThat(groundEnemy.getHealth().getCurrent())
        .as("Ground enemy should take damage")
        .isLessThan(1000);
    assertThat(airEnemy.getHealth().getCurrent())
        .as("Air enemy should take damage")
        .isLessThan(1000);
  }

  @Test
  void onlyDamagesEnemies() {
    // Friendly troop at target location
    Troop friendly =
        Troop.builder()
            .name("FriendlyKnight")
            .team(Team.BLUE)
            .position(new Position(DEPLOY_X, DEPLOY_Y))
            .health(new Health(1000))
            .movement(new Movement(0f, 1.0f, 0.5f, 0.5f, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .damage(0)
                    .range(1.0f)
                    .sightRange(5.0f)
                    .attackCooldown(1.0f)
                    .targetType(TargetType.GROUND)
                    .build())
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    engine.spawn(friendly);

    deployRoyalDelivery(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DAMAGE_DELAY_TICKS + 2);

    assertThat(friendly.getHealth().getCurrent())
        .as("Friendly troop should NOT take damage (onlyEnemies=true)")
        .isEqualTo(1000);
  }

  @Test
  void singleRecruitSpawned() {
    deployRoyalDelivery(DEPLOY_X, DEPLOY_Y);

    // Tick well past spawn time
    engine.tick(SYNC_DELAY_TICKS + SPAWN_DELAY_TICKS + 30);

    assertThat(countRecruits())
        .as("Exactly 1 DeliveryRecruit should spawn per Royal Delivery cast")
        .isEqualTo(1);
  }

  @Test
  void levelScalingApplied() {
    bluePlayer.getLevelConfig().withCardLevel(ROYAL_DELIVERY.getId(), 11);

    Troop enemy = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 5000);

    deployRoyalDelivery(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DAMAGE_DELAY_TICKS + 2);

    int expectedDamage = LevelScaling.scaleCard(BASE_DAMAGE, 11);
    assertThat(enemy.getHealth().getCurrent())
        .as("Enemy should take level-scaled damage (%d)", expectedDamage)
        .isEqualTo(5000 - expectedDamage);

    // Wait for recruit spawn
    engine.tick(SPAWN_DELAY_TICKS);

    Troop recruit = findRecruit();
    int expectedHp = LevelScaling.scaleCard(214, 11);
    assertThat(recruit.getHealth().getMax())
        .as("DeliveryRecruit HP should be level-scaled (%d)", expectedHp)
        .isEqualTo(expectedHp);
  }

  // -- Helpers --

  private void deployRoyalDelivery(float x, float y) {
    PlayerActionDTO action = PlayerActionDTO.builder().handIndex(0).x(x).y(y).build();
    engine.queueAction(bluePlayer, action);
  }

  /**
   * Spawns a deployed (deploy timer=0) enemy troop at the given position. Speed is 0 to prevent
   * walking away from the area effect.
   */
  private Troop spawnEnemyAt(float x, float y, int hp) {
    Troop enemy =
        Troop.builder()
            .name("Victim")
            .team(Team.RED)
            .position(new Position(x, y))
            .health(new Health(hp))
            .movement(new Movement(0f, 1.0f, 0.5f, 0.5f, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .damage(0)
                    .range(1.0f)
                    .sightRange(5.0f)
                    .attackCooldown(1.0f)
                    .targetType(TargetType.GROUND)
                    .build())
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    engine.spawn(enemy);
    return enemy;
  }

  private long countRecruits() {
    return engine.getGameState().getEntitiesOfType(Troop.class).stream()
        .filter(t -> "DeliveryRecruit".equals(t.getName()) && t.getTeam() == Team.BLUE)
        .count();
  }

  private Troop findRecruit() {
    return engine.getGameState().getEntitiesOfType(Troop.class).stream()
        .filter(t -> "DeliveryRecruit".equals(t.getName()) && t.getTeam() == Team.BLUE)
        .findFirst()
        .orElseThrow(() -> new AssertionError("No DeliveryRecruit found"));
  }

  /**
   * Builds a deck of 8 copies of the given card. This guarantees the card is always in the starting
   * hand regardless of shuffle order.
   */
  private Deck buildDeckWith(Card card) {
    List<Card> cards = new java.util.ArrayList<>();
    for (int i = 0; i < 8; i++) {
      cards.add(card);
    }
    return new Deck(cards);
  }
}
