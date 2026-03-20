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
 * count, and applies exactly 1 hit per scan to locked targets.
 *
 * <p>Damage tiers at default level 6 (Common L1 base scaled with COMMON rarity, 5 steps of x1.10):
 *
 * <ul>
 *   <li>Tier 1 (1 target): 1330 DPS -> scaled 2128 -> per-hit 212, CT 30/hit
 *   <li>Tier 2 (2-4 targets): 625 DPS -> scaled 1000 -> per-hit 100, CT 16/hit
 *   <li>Tier 3 (5+ targets): 297 DPS -> scaled 475 -> per-hit 47, CT 11/hit
 * </ul>
 *
 * 3 scans (at t=1.0, 2.0, 3.0) -> 3 total hits per target.
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

  // Number of scans over the lifetime (at t=1.0, 2.0, 3.0)
  private static final int TOTAL_SCANS = 3;

  // Game ticks per scan (1.0s * 30 FPS = 30)
  private static final int GAME_TICKS_PER_SCAN = 30;

  // Total game ticks for all 3 scan periods (3.0s * 30 FPS = 90)
  private static final int TOTAL_ACTIVE_GAME_TICKS = 90;

  // Tier 1 per-hit damage: scaleCard(1330, 6) = 2128, * 0.1 = 212
  private static final int TIER1_DAMAGE_PER_HIT = 212;

  // Tier 2 per-hit: scaleCard(625, 6) = 1000, * 0.1 = 100
  private static final int TIER2_DAMAGE_PER_HIT = 100;

  // Tier 3 per-hit: scaleCard(297, 6) = 475, * 0.1 = 47
  private static final int TIER3_DAMAGE_PER_HIT = 47;

  // Crown tower per-hit (tier 1): scaleCard(19, 6) = 30
  private static final int TIER1_CT_DAMAGE_PER_HIT = 30;

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

    // Tick past sync delay + first hit delay + enough time for all 3 scans
    // firstHitDelay=1.0s, then 3 scan periods of 1.0s each = 3.0s active
    // Total: sync(1.0s) + delay(1.0s) + active(3.0s) = 5.0s = 150 ticks
    engine.tick(SYNC_DELAY_TICKS + FIRST_HIT_DELAY_TICKS + TOTAL_ACTIVE_GAME_TICKS + 5);

    int damageTaken = 50000 - enemy.getHealth().getCurrent();
    int expectedDamage = TIER1_DAMAGE_PER_HIT * TOTAL_SCANS; // 212 * 3 = 636
    assertThat(damageTaken)
        .as(
            "Single target should take Tier 1 damage: %d per hit * %d scans = %d total",
            TIER1_DAMAGE_PER_HIT, TOTAL_SCANS, expectedDamage)
        .isEqualTo(expectedDamage);
  }

  @Test
  void multipleTargets_tier2Damage() {
    Troop e1 = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 50000, "Enemy1");
    Troop e2 = spawnEnemyAt(DEPLOY_X + 0.5f, DEPLOY_Y, 50000, "Enemy2");
    Troop e3 = spawnEnemyAt(DEPLOY_X - 0.5f, DEPLOY_Y, 50000, "Enemy3");

    deployDarkMagic(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + FIRST_HIT_DELAY_TICKS + TOTAL_ACTIVE_GAME_TICKS + 5);

    int expectedPerTarget = TIER2_DAMAGE_PER_HIT * TOTAL_SCANS; // 100 * 3 = 300
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

    int expectedPerTarget = TIER3_DAMAGE_PER_HIT * TOTAL_SCANS; // 47 * 3 = 141
    for (Troop e : enemies) {
      int damageTaken = 50000 - e.getHealth().getCurrent();
      assertThat(damageTaken)
          .as("Each of 5+ targets should take Tier 3 damage: %d", expectedPerTarget)
          .isEqualTo(expectedPerTarget);
    }
  }

  @Test
  void tierReEvaluatesOnScan() {
    // Start with 3 targets -> Tier 2 (100/hit)
    Troop e1 = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 50000, "Survivor");
    Troop e2 = spawnEnemyAt(DEPLOY_X + 0.5f, DEPLOY_Y, 50, "Fragile1");
    Troop e3 = spawnEnemyAt(DEPLOY_X - 0.5f, DEPLOY_Y, 50, "Fragile2");

    deployDarkMagic(DEPLOY_X, DEPLOY_Y);

    // Tick past sync delay + first hit delay + small buffer (2 ticks past delay boundary).
    // The first scan fires right at the firstHitDelay boundary, so +2 ensures exactly 1 scan
    // has occurred without reaching the next scan boundary (30 ticks away).
    engine.tick(SYNC_DELAY_TICKS + FIRST_HIT_DELAY_TICKS + 2);

    // Fragile enemies should be dead (50 HP < 100 damage per hit)
    assertThat(e2.isAlive()).as("Fragile1 should be dead after first scan").isFalse();
    assertThat(e3.isAlive()).as("Fragile2 should be dead after first scan").isFalse();

    int damageAfterFirstScan = 50000 - e1.getHealth().getCurrent();
    // First scan: 1 hit at tier 2 = 100
    assertThat(damageAfterFirstScan)
        .as("Survivor should take 1 hit of Tier 2 damage in first scan")
        .isEqualTo(TIER2_DAMAGE_PER_HIT);

    // Now tick through remaining 2 scans + buffer.
    // Only 1 target left -> rescan at scan boundary switches to Tier 1.
    engine.tick(GAME_TICKS_PER_SCAN * 2 + 5);

    int totalDamage = 50000 - e1.getHealth().getCurrent();
    int damageInRemainingScans = totalDamage - damageAfterFirstScan;

    // The remaining 2 scans should use Tier 1 damage (212/hit * 2 hits = 424)
    int expectedRemainingDamage = TIER1_DAMAGE_PER_HIT * 2;
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

    // Crown tower should take CT damage (30/hit * 3 scans = 90)
    int expectedCtDamage = TIER1_CT_DAMAGE_PER_HIT * TOTAL_SCANS;
    assertThat(damageTaken)
        .as(
            "Crown tower should take CT damage: %d/hit * %d scans = %d",
            TIER1_CT_DAMAGE_PER_HIT, TOTAL_SCANS, expectedCtDamage)
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

    // Both should take damage (Tier 2 since 2 targets: 100/hit * 3 = 300)
    int expectedDamage = TIER2_DAMAGE_PER_HIT * TOTAL_SCANS;
    assertThat(50000 - groundEnemy.getHealth().getCurrent())
        .as("Ground target should take laser damage despite hitsGround=false")
        .isEqualTo(expectedDamage);
    assertThat(50000 - airEnemy.getHealth().getCurrent())
        .as("Air target should take laser damage despite hitsAir=false")
        .isEqualTo(expectedDamage);
  }

  @Test
  void effectExpiresAfterAllScans() {
    spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 50000, "Enemy");

    deployDarkMagic(DEPLOY_X, DEPLOY_Y);

    // Let the full effect play out: sync(1.0) + delay(1.0) + active(3.0) + buffer
    engine.tick(SYNC_DELAY_TICKS + FIRST_HIT_DELAY_TICKS + TOTAL_ACTIVE_GAME_TICKS + 10);

    List<AreaEffect> effects = engine.getGameState().getEntitiesOfType(AreaEffect.class);
    boolean darkMagicAlive =
        effects.stream().anyMatch(e -> "DarkMagicAOE".equals(e.getName()) && e.isAlive());
    assertThat(darkMagicAlive)
        .as("DarkMagic area effect should be dead after all scans are consumed")
        .isFalse();
  }

  @Test
  void newTargetsNotDamagedUntilNextScan() {
    // Start with 1 target
    Troop original = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 50000, "Original");

    deployDarkMagic(DEPLOY_X, DEPLOY_Y);

    // Tick past sync delay + first hit delay + some buffer into the scan period (15 ticks = 0.5s).
    // The first scan has already fired at the delay boundary; we're now between scan 1 and scan 2.
    engine.tick(SYNC_DELAY_TICKS + FIRST_HIT_DELAY_TICKS + 15);

    // Spawn a new enemy mid-scan period (scan accumulator ~0.5, second scan at 1.0)
    Troop newcomer = spawnEnemyAt(DEPLOY_X + 0.5f, DEPLOY_Y, 50000, "Newcomer");

    // Tick a few more ticks (5) -- still before the second scan boundary (scan acc ~0.67)
    engine.tick(5);

    int newcomerDamageAfterFirstScan = 50000 - newcomer.getHealth().getCurrent();
    assertThat(newcomerDamageAfterFirstScan)
        .as("Newcomer should take zero damage during the scan they entered")
        .isEqualTo(0);

    // Tick past the second scan boundary (30 more ticks ensures scan acc >= 1.0)
    engine.tick(GAME_TICKS_PER_SCAN);

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
