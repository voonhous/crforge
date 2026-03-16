package org.crforge.core.entity.effect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.Card;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.base.AbstractEntity;
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
 * Tests for the DarkMagic (Void) spell: a 3-elixir Epic spell with a laser ball mechanic. The AOE
 * persists for 4.0s, scans every 1.0s (after a 1.0s delay), selects a damage tier based on target
 * count, and applies 100ms-interval damage ticks to locked targets.
 *
 * <p>Base damage tiers (Epic L6, no scaling applied):
 *
 * <ul>
 *   <li>Tier 1 (1 target): 1330 DPS -> 133/tick, CT 19/tick
 *   <li>Tier 2 (2-4 targets): 625 DPS -> 63/tick, CT 10/tick (rounded)
 *   <li>Tier 3 (5+ targets): 297 DPS -> 30/tick, CT 7/tick (rounded)
 * </ul>
 *
 * Each scan period = 1.0s = 10 laser ticks at 100ms interval. 3 scan periods -> 30 total ticks.
 */
class DarkMagicSpellTest {

  private GameEngine engine;
  private Player bluePlayer;

  private static final Card DARK_MAGIC = CardRegistry.get("darkmagic");

  // Deploy at y=18 (near river, well out of princess tower range at y=6.5)
  private static final float DEPLOY_X = 9f;
  private static final float DEPLOY_Y = 18f;

  // Placement sync delay (1.0s = 30 ticks)
  private static final int SYNC_DELAY_TICKS = 30;

  // First hit delay (1.0s = 30 ticks)
  private static final int FIRST_HIT_DELAY_TICKS = 30;

  // Laser ticks per scan (1.0s / 0.1s = 10)
  private static final int LASER_TICKS_PER_SCAN = 10;

  // Game ticks per scan (1.0s * 30 FPS = 30)
  private static final int GAME_TICKS_PER_SCAN = 30;

  // Total laser ticks across all 3 scans
  private static final int TOTAL_LASER_TICKS = 30;

  // Total game ticks for all 3 scan periods (3.0s * 30 FPS = 90)
  private static final int TOTAL_ACTIVE_GAME_TICKS = 90;

  // Tier 1 base damage per tick (1330 DPS * 0.1s = 133)
  private static final int TIER1_DAMAGE_PER_TICK = 133;

  // Tier 2 base damage per tick (625 DPS * 0.1s = 63, since round(62.5) = 63)
  private static final int TIER2_DAMAGE_PER_TICK = 63;

  // Tier 3 base damage per tick (297 DPS * 0.1s = 30, since round(29.7) = 30)
  private static final int TIER3_DAMAGE_PER_TICK = 30;

  // Crown tower damage per tick (tier 1)
  private static final int TIER1_CT_DAMAGE_PER_TICK = 19;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    engine = new GameEngine();

    Deck blueDeck = buildDeckWith(DARK_MAGIC);
    Deck redDeck = buildDeckWith(DARK_MAGIC);

    bluePlayer = new Player(Team.BLUE, blueDeck, false);
    Player redPlayer = new Player(Team.RED, redDeck, false);

    // Use tower level 1 to minimize tower interference
    Standard1v1Match match = new Standard1v1Match(1);
    match.addPlayer(bluePlayer);
    match.addPlayer(redPlayer);
    engine.setMatch(match);
    engine.initMatch();

    // Give blue player enough elixir
    bluePlayer.getElixir().add(10);
  }

  @Test
  void darkMagicCardDataLoaded() {
    assertThat(DARK_MAGIC).as("DarkMagic card should be loaded from CardRegistry").isNotNull();
    assertThat(DARK_MAGIC.getAreaEffect()).as("DarkMagic should have an areaEffect").isNotNull();

    AreaEffectStats ae = DARK_MAGIC.getAreaEffect();
    assertThat(ae.getFirstHitDelay()).isEqualTo(1.0f);
    assertThat(ae.getScanInterval()).isEqualTo(1.0f);
    assertThat(ae.getLifeDuration()).isEqualTo(4.0f);
    assertThat(ae.getDamageTiers()).hasSize(3);
    assertThat(ae.isHitsGround()).isFalse();
    assertThat(ae.isHitsAir()).isFalse();
  }

  @Test
  void isDummyReturnsFalse() {
    AreaEffectStats ae = DARK_MAGIC.getAreaEffect();
    assertThat(ae.isDummy()).as("DarkMagic should NOT be a dummy area effect").isFalse();
  }

  @Test
  void noDamageBeforeFirstHitDelay() {
    Troop enemy = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 5000, "Enemy");

    deployDarkMagic(DEPLOY_X, DEPLOY_Y);

    // Tick 29 frames (0.967s) -- still within the 1.0s first-hit delay
    engine.tick(SYNC_DELAY_TICKS + 29);

    assertThat(enemy.getHealth().getCurrent())
        .as("No damage should be dealt before first hit delay expires")
        .isEqualTo(5000);
  }

  @Test
  void singleTarget_tier1Damage() {
    Troop enemy = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 50000, "Enemy");

    deployDarkMagic(DEPLOY_X, DEPLOY_Y);

    // Tick past sync delay + first hit delay + enough time for all 30 laser ticks
    // firstHitDelay=1.0s, then 3 scan periods of 1.0s each = 3.0s active
    // Total: sync(1.0s) + delay(1.0s) + active(3.0s) = 5.0s = 150 ticks
    engine.tick(SYNC_DELAY_TICKS + FIRST_HIT_DELAY_TICKS + TOTAL_ACTIVE_GAME_TICKS + 5);

    int damageTaken = 50000 - enemy.getHealth().getCurrent();
    int expectedDamage = TIER1_DAMAGE_PER_TICK * TOTAL_LASER_TICKS; // 133 * 30 = 3990
    assertThat(damageTaken)
        .as(
            "Single target should take Tier 1 damage: %d per tick * %d ticks = %d total",
            TIER1_DAMAGE_PER_TICK, TOTAL_LASER_TICKS, expectedDamage)
        .isEqualTo(expectedDamage);
  }

  @Test
  void multipleTargets_tier2Damage() {
    Troop e1 = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 50000, "Enemy1");
    Troop e2 = spawnEnemyAt(DEPLOY_X + 0.5f, DEPLOY_Y, 50000, "Enemy2");
    Troop e3 = spawnEnemyAt(DEPLOY_X - 0.5f, DEPLOY_Y, 50000, "Enemy3");

    deployDarkMagic(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + FIRST_HIT_DELAY_TICKS + TOTAL_ACTIVE_GAME_TICKS + 5);

    int expectedPerTarget = TIER2_DAMAGE_PER_TICK * TOTAL_LASER_TICKS; // 63 * 30 = 1890
    for (Troop e : List.of(e1, e2, e3)) {
      int damageTaken = 50000 - e.getHealth().getCurrent();
      assertThat(damageTaken)
          .as("Each of 3 targets should take Tier 2 damage: %d", expectedPerTarget)
          .isEqualTo(expectedPerTarget);
    }
  }

  @Test
  void manyTargets_tier3Damage() {
    List<Troop> enemies = new java.util.ArrayList<>();
    for (int i = 0; i < 5; i++) {
      float offsetX = (i - 2) * 0.4f;
      enemies.add(spawnEnemyAt(DEPLOY_X + offsetX, DEPLOY_Y, 50000, "Enemy" + i));
    }

    deployDarkMagic(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + FIRST_HIT_DELAY_TICKS + TOTAL_ACTIVE_GAME_TICKS + 5);

    int expectedPerTarget = TIER3_DAMAGE_PER_TICK * TOTAL_LASER_TICKS; // 30 * 30 = 900
    for (Troop e : enemies) {
      int damageTaken = 50000 - e.getHealth().getCurrent();
      assertThat(damageTaken)
          .as("Each of 5+ targets should take Tier 3 damage: %d", expectedPerTarget)
          .isEqualTo(expectedPerTarget);
    }
  }

  @Test
  void tierReEvaluatesOnScan() {
    // Start with 3 targets -> Tier 2 (63/tick)
    Troop e1 = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 50000, "Survivor");
    Troop e2 = spawnEnemyAt(DEPLOY_X + 0.5f, DEPLOY_Y, 50, "Fragile1");
    Troop e3 = spawnEnemyAt(DEPLOY_X - 0.5f, DEPLOY_Y, 50, "Fragile2");

    deployDarkMagic(DEPLOY_X, DEPLOY_Y);

    // Tick past sync delay + first hit delay + exactly one scan period (30 game ticks = 1.0s)
    // The fragile enemies (50 HP) will die on the first laser tick from Tier 2 damage (63/tick)
    engine.tick(SYNC_DELAY_TICKS + FIRST_HIT_DELAY_TICKS + GAME_TICKS_PER_SCAN);

    // Fragile enemies should be dead (50 HP < 63 damage per tick)
    assertThat(e2.isAlive()).as("Fragile1 should be dead after first scan").isFalse();
    assertThat(e3.isAlive()).as("Fragile2 should be dead after first scan").isFalse();

    int damageAfterFirstScan = 50000 - e1.getHealth().getCurrent();
    // First scan: 10 laser ticks at tier 2 = 63 * 10 = 630
    assertThat(damageAfterFirstScan)
        .as("Survivor should take 10 laser ticks of Tier 2 damage in first scan")
        .isEqualTo(TIER2_DAMAGE_PER_TICK * LASER_TICKS_PER_SCAN);

    // Now tick through remaining 2 scans (60 game ticks = 2.0s) + buffer
    // Only 1 target left -> rescan at scan boundary switches to Tier 1
    engine.tick(GAME_TICKS_PER_SCAN * 2 + 5);

    int totalDamage = 50000 - e1.getHealth().getCurrent();
    int damageInRemainingScans = totalDamage - damageAfterFirstScan;

    // The remaining scans should use Tier 1 damage (133/tick * 20 ticks = 2660)
    int expectedRemainingDamage = TIER1_DAMAGE_PER_TICK * 20;
    assertThat(damageInRemainingScans)
        .as("After fragile enemies die, survivor should take Tier 1 damage for remaining scans")
        .isEqualTo(expectedRemainingDamage);
  }

  @Test
  void crownTowerDamage() {
    // Deploy near a red princess tower. Red princess towers at ~(3.5, 25.5) and (14.5, 25.5)
    float towerX = 14.5f;
    float towerY = 25.5f;

    bluePlayer.getElixir().add(10);

    // Find a red princess tower
    var redTower =
        engine.getGameState().getAliveEntities().stream()
            .filter(
                e ->
                    e.getTeam() == Team.RED
                        && e instanceof Tower
                        && e.getName().contains("Princess")
                        && e.getPosition().distanceTo(new Position(towerX, towerY)) < 2f)
            .findFirst();

    if (redTower.isEmpty()) {
      return; // Skip if tower not found
    }

    int towerHpBefore = redTower.get().getHealth().getCurrent();

    deployDarkMagic(towerX, towerY);
    engine.tick(SYNC_DELAY_TICKS + FIRST_HIT_DELAY_TICKS + TOTAL_ACTIVE_GAME_TICKS + 5);

    int towerHpAfter = redTower.get().getHealth().getCurrent();
    int damageTaken = towerHpBefore - towerHpAfter;

    // Crown tower should take CT damage (19/tick * 30 ticks = 570)
    int expectedCtDamage = TIER1_CT_DAMAGE_PER_TICK * TOTAL_LASER_TICKS;
    assertThat(damageTaken)
        .as(
            "Crown tower should take CT damage: %d/tick * %d ticks = %d",
            TIER1_CT_DAMAGE_PER_TICK, TOTAL_LASER_TICKS, expectedCtDamage)
        .isEqualTo(expectedCtDamage);
  }

  @Test
  void hitsGroundAndAirTargets() {
    // Ground target
    Troop groundEnemy = spawnEnemyAt(DEPLOY_X - 0.5f, DEPLOY_Y, 50000, "GroundEnemy");

    // Air target
    Troop airEnemy =
        Troop.builder()
            .name("AirEnemy")
            .team(Team.RED)
            .position(new Position(DEPLOY_X + 0.5f, DEPLOY_Y))
            .health(new Health(50000))
            .movement(new Movement(0f, 4.0f, 0.5f, 0.5f, MovementType.AIR))
            .combat(
                Combat.builder()
                    .damage(0)
                    .range(1.0f)
                    .sightRange(5.0f)
                    .attackCooldown(1.0f)
                    .targetType(TargetType.ALL)
                    .build())
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    engine.spawn(airEnemy);

    deployDarkMagic(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + FIRST_HIT_DELAY_TICKS + TOTAL_ACTIVE_GAME_TICKS + 5);

    // Both should take damage (Tier 2 since 2 targets: 63/tick * 30 = 1890)
    int expectedDamage = TIER2_DAMAGE_PER_TICK * TOTAL_LASER_TICKS;
    assertThat(50000 - groundEnemy.getHealth().getCurrent())
        .as("Ground target should take laser damage despite hitsGround=false")
        .isEqualTo(expectedDamage);
    assertThat(50000 - airEnemy.getHealth().getCurrent())
        .as("Air target should take laser damage despite hitsAir=false")
        .isEqualTo(expectedDamage);
  }

  @Test
  void effectExpiresAfterAllTicks() {
    spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 50000, "Enemy");

    deployDarkMagic(DEPLOY_X, DEPLOY_Y);

    // Let the full effect play out: sync(1.0) + delay(1.0) + active(3.0) + buffer
    engine.tick(SYNC_DELAY_TICKS + FIRST_HIT_DELAY_TICKS + TOTAL_ACTIVE_GAME_TICKS + 10);

    List<AreaEffect> effects = engine.getGameState().getEntitiesOfType(AreaEffect.class);
    boolean darkMagicAlive =
        effects.stream().anyMatch(e -> "DarkMagicAOE".equals(e.getName()) && e.isAlive());
    assertThat(darkMagicAlive)
        .as("DarkMagic area effect should be dead after all ticks are consumed")
        .isFalse();
  }

  @Test
  void newTargetsNotDamagedUntilNextScan() {
    // Start with 1 target
    Troop original = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 50000, "Original");

    deployDarkMagic(DEPLOY_X, DEPLOY_Y);

    // Tick past sync delay + first hit delay + half of first scan (15 game ticks = 5 laser ticks)
    engine.tick(SYNC_DELAY_TICKS + FIRST_HIT_DELAY_TICKS + 15);

    // Spawn a new enemy mid-scan
    Troop newcomer = spawnEnemyAt(DEPLOY_X + 0.5f, DEPLOY_Y, 50000, "Newcomer");

    // Tick through rest of first scan (15 game ticks = 5 laser ticks) + buffer
    engine.tick(17);

    int newcomerDamageAfterFirstScan = 50000 - newcomer.getHealth().getCurrent();
    assertThat(newcomerDamageAfterFirstScan)
        .as("Newcomer should take zero damage during the scan they entered")
        .isEqualTo(0);

    // Tick through the second scan (30 game ticks = 10 laser ticks) + buffer
    engine.tick(GAME_TICKS_PER_SCAN + 5);

    int newcomerDamageAfterSecondScan = 50000 - newcomer.getHealth().getCurrent();
    assertThat(newcomerDamageAfterSecondScan)
        .as("Newcomer should take damage after the rescan includes them")
        .isGreaterThan(0);
  }

  // -- Helpers --

  private void deployDarkMagic(float x, float y) {
    PlayerActionDTO action = PlayerActionDTO.builder().handIndex(0).x(x).y(y).build();
    engine.queueAction(bluePlayer, action);
  }

  private Troop spawnEnemyAt(float x, float y, int hp, String name) {
    Troop enemy =
        Troop.builder()
            .name(name)
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

  private Deck buildDeckWith(Card card) {
    List<Card> cards = new java.util.ArrayList<>();
    for (int i = 0; i < 8; i++) {
      cards.add(card);
    }
    return new Deck(cards);
  }
}
