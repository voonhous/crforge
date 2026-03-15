package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.ability.StealthAbility;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.DeathSpawnEntry;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.base.AbstractEntity;
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
 * Tests for Suspicious Bush: a 2-elixir Rare troop with permanent invisibility, kamikaze (damage=0,
 * dies on attack), targetOnlyBuildings, and staggered flanking death spawns of 2 BushGoblins.
 */
class SuspiciousBushTest {

  private GameEngine engine;
  private Player bluePlayer;

  private static final Card SUSPICIOUS_BUSH = CardRegistry.get("suspiciousbush");

  // Deploy deep in blue territory so bush walks toward red towers
  private static final float DEPLOY_X = 9f;
  private static final float DEPLOY_Y = 14f;

  // 1.0s deploy time = 30 ticks
  private static final int DEPLOY_TICKS = 30;
  // Sync delay for card deployment = 30 ticks
  private static final int SYNC_DELAY_TICKS = 30;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    engine = new GameEngine();

    Deck blueDeck = buildDeckWith(SUSPICIOUS_BUSH);
    Deck redDeck = buildDeckWith(SUSPICIOUS_BUSH);

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
    assertThat(SUSPICIOUS_BUSH).as("Card should be loaded").isNotNull();
    assertThat(SUSPICIOUS_BUSH.getType()).as("Card type").isEqualTo(CardType.TROOP);
    assertThat(SUSPICIOUS_BUSH.getCost()).as("Elixir cost").isEqualTo(2);
    assertThat(SUSPICIOUS_BUSH.getRarity()).as("Rarity").isEqualTo(Rarity.RARE);
    assertThat(SUSPICIOUS_BUSH.getUnitStats().getName())
        .as("Unit name")
        .isEqualTo("SuspiciousBush");
  }

  @Test
  void unitStatsLoadCorrectly() {
    TroopStats stats = SUSPICIOUS_BUSH.getUnitStats();
    assertThat(stats.getHealth()).as("Health").isEqualTo(32);
    assertThat(stats.getDamage()).as("Damage").isEqualTo(0);
    assertThat(stats.isKamikaze()).as("Kamikaze").isTrue();
    assertThat(stats.isTargetOnlyBuildings()).as("Target only buildings").isTrue();
    assertThat(stats.getAbility()).as("Has stealth ability").isInstanceOf(StealthAbility.class);
  }

  @Test
  void bushGoblinStatsLoadCorrectly() {
    // BushGoblin stats are accessible through the death spawn entries
    List<DeathSpawnEntry> deathSpawns = SUSPICIOUS_BUSH.getUnitStats().getDeathSpawns();
    assertThat(deathSpawns).as("Should have death spawn entries").isNotEmpty();

    TroopStats bushGoblin = deathSpawns.get(0).stats();
    assertThat(bushGoblin.getName()).as("Name").isEqualTo("BushGoblin");
    assertThat(bushGoblin.getHealth()).as("Health").isEqualTo(119);
    assertThat(bushGoblin.getDamage()).as("Damage").isEqualTo(100);
    assertThat(bushGoblin.getDeployDelay()).as("Deploy delay").isEqualTo(0.4f);
  }

  @Test
  void deathSpawnEntriesLoadCorrectly() {
    List<DeathSpawnEntry> deathSpawns = SUSPICIOUS_BUSH.getUnitStats().getDeathSpawns();
    assertThat(deathSpawns).as("Death spawn entry count").hasSize(2);

    // Entry 0: BushGoblin at (-1, 0) with 0.675s delay
    DeathSpawnEntry entry0 = deathSpawns.get(0);
    assertThat(entry0.stats().getName()).isEqualTo("BushGoblin");
    assertThat(entry0.count()).as("Spawn number").isEqualTo(1);
    assertThat(entry0.spawnDelay()).as("Spawn delay").isEqualTo(0.675f);
    assertThat(entry0.relativeX()).as("Relative X").isEqualTo(-1.0f);
    assertThat(entry0.relativeY()).as("Relative Y").isEqualTo(0.0f);

    // Entry 1: BushGoblin at (+1, 0) with 0.625s delay
    DeathSpawnEntry entry1 = deathSpawns.get(1);
    assertThat(entry1.stats().getName()).isEqualTo("BushGoblin");
    assertThat(entry1.count()).as("Spawn number").isEqualTo(1);
    assertThat(entry1.spawnDelay()).as("Spawn delay").isEqualTo(0.625f);
    assertThat(entry1.relativeX()).as("Relative X").isEqualTo(1.0f);
    assertThat(entry1.relativeY()).as("Relative Y").isEqualTo(0.0f);
  }

  @Test
  void bushIsInvisibleFromDeploy() {
    deployBush(DEPLOY_X, DEPLOY_Y);
    // Tick past sync + deploy
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    Troop bush = findBush();
    assertThat(bush).as("Bush should exist").isNotNull();
    assertThat(bush.isInvisible()).as("Bush should be invisible after deploy").isTrue();
  }

  @Test
  void bushStaysInvisiblePermanently() {
    deployBush(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    Troop bush = findBush();
    assertThat(bush.isInvisible()).as("Invisible initially").isTrue();

    // Tick for 5 more seconds (150 ticks) - should still be invisible
    engine.tick(150);
    assertThat(bush.isInvisible()).as("Still invisible after 5s").isTrue();
  }

  @Test
  void invisibleBushCannotBeTargeted() {
    // Deploy bush in blue territory
    deployBush(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    Troop bush = findBush();
    assertThat(bush).as("Bush should exist").isNotNull();
    assertThat(bush.isInvisible()).as("Bush is invisible").isTrue();

    // Spawn a red melee troop right next to the bush
    Troop redTroop =
        createMeleeTroop(Team.RED, bush.getPosition().getX(), bush.getPosition().getY() + 1f, 100);
    engine.getGameState().spawnEntity(redTroop);
    engine.tick(5);

    // Red troop should not target the invisible bush
    assertThat(redTroop.getCombat().getCurrentTarget())
        .as("Red troop should not target invisible bush")
        .isNotEqualTo(bush);
  }

  @Test
  void kamikazeDiesAfterAttackingBuilding() {
    // Deploy bush very close to a red tower so it reaches quickly
    // Red princess tower is at approximately x=3, y=29
    // Deploy near it so it reaches fast
    deployBush(9f, 26f);

    // Tick until bush reaches tower and attacks. Kamikaze should kill it.
    // Give it plenty of time to walk and attack
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 300);

    Troop bush = findBush();
    // Bush should be dead after reaching and attacking a building
    assertThat(bush).as("Bush should be dead (kamikaze after attack)").isNull();
  }

  @Test
  void kamikazeDealZeroDamage() {
    // Get initial tower HP before deploying
    Tower redTower = findRedPrincessTower();
    int initialHp = redTower.getHealth().getCurrent();

    // Deploy bush close to tower
    deployBush(9f, 26f);

    // Tick enough for bush to reach tower and attack
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 300);

    // Tower HP should be unchanged (bush does 0 damage)
    assertThat(redTower.getHealth().getCurrent())
        .as("Tower HP should be unchanged (bush deals 0 damage)")
        .isEqualTo(initialHp);
  }

  @Test
  void deathSpawnsTwoBushGoblinsWithDelay() {
    deployBush(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    Troop bush = findBush();
    assertThat(bush).isNotNull();

    // Kill the bush
    bush.getHealth().takeDamage(bush.getHealth().getCurrent());

    // Tick 1: processDeaths -> onDeath queues pending spawns
    engine.tick(1);
    assertThat(countBushGoblins()).as("No goblins yet (delayed)").isEqualTo(0);

    // Max delay is 0.675s. Need ceil(0.675*30) = 21 decrements + 1 tick for processPending = 22.
    engine.tick(22);

    assertThat(countBushGoblins()).as("Both BushGoblins should have spawned").isEqualTo(2);
  }

  @Test
  void deathSpawnAtFlankingPositions() {
    deployBush(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    Troop bush = findBush();
    float deathX = bush.getPosition().getX();
    float deathY = bush.getPosition().getY();

    bush.getHealth().takeDamage(bush.getHealth().getCurrent());
    // 1 tick for death + 22 ticks for max delay + processPending
    engine.tick(23);

    List<Troop> goblins =
        engine.getGameState().getEntitiesOfType(Troop.class).stream()
            .filter(t -> "BushGoblin".equals(t.getName()) && t.getTeam() == Team.BLUE)
            .toList();
    assertThat(goblins).hasSize(2);

    // One should be at (deathX-1, deathY), one at (deathX+1, deathY)
    // Goblins are still deploying (0.4s deploy delay) so they haven't moved yet
    boolean hasLeft =
        goblins.stream()
            .anyMatch(
                g ->
                    Math.abs(g.getPosition().getX() - (deathX - 1.0f)) < 0.01f
                        && Math.abs(g.getPosition().getY() - deathY) < 0.01f);
    boolean hasRight =
        goblins.stream()
            .anyMatch(
                g ->
                    Math.abs(g.getPosition().getX() - (deathX + 1.0f)) < 0.01f
                        && Math.abs(g.getPosition().getY() - deathY) < 0.01f);

    assertThat(hasLeft).as("Should have BushGoblin at left offset (-1, 0)").isTrue();
    assertThat(hasRight).as("Should have BushGoblin at right offset (+1, 0)").isTrue();
  }

  @Test
  void deathSpawnStaggeredTiming() {
    deployBush(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    Troop bush = findBush();
    bush.getHealth().takeDamage(bush.getHealth().getCurrent());

    // 1 tick for death processing (onDeath queues pending spawns)
    engine.tick(1);

    // 0.625s delay needs ceil(0.625*30) = 19 decrements + 1 for processPending = 20 ticks
    engine.tick(20);
    assertThat(countBushGoblins())
        .as("After 0.625s delay, the faster goblin should have spawned")
        .isEqualTo(1);

    // 0.675s delay needs ceil(0.675*30) = 21 decrements + 1 for processPending = 22 total
    // from death. We've done 21, need 2 more (1 decrement + 1 processPending).
    engine.tick(2);
    assertThat(countBushGoblins())
        .as("After 0.675s delay, both goblins should have spawned")
        .isEqualTo(2);
  }

  @Test
  void bushGoblinsHaveDeployDelay() {
    deployBush(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    Troop bush = findBush();
    bush.getHealth().takeDamage(bush.getHealth().getCurrent());

    // 1 tick death + 22 ticks for max delay + processPending
    engine.tick(23);

    List<Troop> goblins =
        engine.getGameState().getEntitiesOfType(Troop.class).stream()
            .filter(t -> "BushGoblin".equals(t.getName()) && t.getTeam() == Team.BLUE)
            .toList();
    assertThat(goblins).as("BushGoblins should exist").hasSize(2);

    // Each goblin should still be deploying (deployDelay=0.4s = 12 ticks, only ~2 updates so far)
    for (Troop goblin : goblins) {
      assertThat(goblin.isDeploying())
          .as("BushGoblin should be in deploy phase (0.4s deploy delay)")
          .isTrue();
    }
  }

  @Test
  void killedByAreaDamageStillSpawnsGoblins() {
    deployBush(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    Troop bush = findBush();
    assertThat(bush).isNotNull();

    // Kill bush with direct damage (simulating area damage hit)
    bush.getHealth().takeDamage(bush.getHealth().getCurrent());

    // 1 tick death + 22 ticks for max delay + processPending
    engine.tick(23);

    assertThat(countBushGoblins())
        .as("Goblins should spawn even when killed by area damage")
        .isEqualTo(2);
  }

  @Test
  void dummyDeathAreaEffectNotSpawned() {
    // Verify the death area effect is marked as dummy
    assertThat(SUSPICIOUS_BUSH.getUnitStats().getDeathAreaEffect()).as("Has death AEO").isNotNull();
    assertThat(SUSPICIOUS_BUSH.getUnitStats().getDeathAreaEffect().isDummy())
        .as("Death AEO should be dummy (radius=0, !hitsGround, !hitsAir)")
        .isTrue();

    // Deploy and kill the bush
    deployBush(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    Troop bush = findBush();
    bush.getHealth().takeDamage(bush.getHealth().getCurrent());
    engine.tick(2);

    // No AreaEffect entities should have been spawned for the dummy AEO
    long areaEffects =
        engine
            .getGameState()
            .getEntitiesOfType(org.crforge.core.entity.effect.AreaEffect.class)
            .stream()
            .filter(ae -> ae.getName().contains("SuspiciousBush"))
            .count();
    assertThat(areaEffects).as("Dummy AEO should not spawn an AreaEffect entity").isEqualTo(0);
  }

  // -- Helpers --

  private void deployBush(float x, float y) {
    PlayerActionDTO action = PlayerActionDTO.builder().handIndex(0).x(x).y(y).build();
    engine.queueAction(bluePlayer, action);
  }

  private Troop findBush() {
    return engine.getGameState().getEntitiesOfType(Troop.class).stream()
        .filter(t -> "SuspiciousBush".equals(t.getName()) && t.getTeam() == Team.BLUE)
        .filter(t -> !t.getHealth().isDead())
        .findFirst()
        .orElse(null);
  }

  private long countBushGoblins() {
    return engine.getGameState().getEntitiesOfType(Troop.class).stream()
        .filter(t -> "BushGoblin".equals(t.getName()) && t.getTeam() == Team.BLUE)
        .count();
  }

  private Tower findRedPrincessTower() {
    return engine.getGameState().getEntitiesOfType(Tower.class).stream()
        .filter(t -> t.getTeam() == Team.RED && !t.getName().contains("King"))
        .findFirst()
        .orElse(null);
  }

  private Troop createMeleeTroop(Team team, float x, float y, int damage) {
    TroopStats stats =
        TroopStats.builder()
            .name("TestTroop")
            .health(100)
            .damage(damage)
            .speed(1.0f)
            .mass(4.0f)
            .collisionRadius(0.5f)
            .visualRadius(0.5f)
            .range(0.8f)
            .sightRange(5.5f)
            .attackCooldown(1.0f)
            .movementType(org.crforge.core.entity.base.MovementType.GROUND)
            .targetType(org.crforge.core.entity.base.TargetType.GROUND)
            .build();

    return Troop.builder()
        .name("TestTroop")
        .team(team)
        .position(new org.crforge.core.component.Position(x, y))
        .health(new org.crforge.core.component.Health(100))
        .movement(
            new org.crforge.core.component.Movement(
                1.0f, 4.0f, 0.5f, 0.5f, org.crforge.core.entity.base.MovementType.GROUND))
        .combat(
            org.crforge.core.component.Combat.builder()
                .damage(damage)
                .range(0.8f)
                .sightRange(5.5f)
                .attackCooldown(1.0f)
                .targetType(org.crforge.core.entity.base.TargetType.GROUND)
                .build())
        .deployTime(0f)
        .deployTimer(0f)
        .build();
  }

  private Deck buildDeckWith(Card card) {
    List<Card> cards = new java.util.ArrayList<>();
    for (int i = 0; i < 8; i++) {
      cards.add(card);
    }
    return new Deck(cards);
  }
}
