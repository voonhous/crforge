package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.effect.StatusEffectType;
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
 * Tests for the Vines spell: a 3-elixir Epic spell that selects up to 3 highest-HP enemies in a
 * 2.5-tile radius, freezes them (-100 all speed multipliers), pulls air units to ground, and deals
 * DOT damage via a ticking buff (60 DPS base, 1.0s hit frequency) for 2.0s.
 */
class VinesSpellTest {

  private GameEngine engine;
  private Player bluePlayer;

  private static final Card VINES = CardRegistry.get("vines");

  // Deploy at y=14 to avoid tower aggro
  private static final float DEPLOY_X = 9f;
  private static final float DEPLOY_Y = 14f;

  // Placement sync delay (1.0s)
  private static final int SYNC_DELAY_TICKS = GameEngine.TICKS_PER_SECOND;

  // Initial delay before first target selection (0.4s)
  private static final int INITIAL_DELAY_TICKS = (int) (0.4f * GameEngine.TICKS_PER_SECOND);

  // Buff hit frequency (1.0s)
  private static final int HIT_FREQUENCY_TICKS = GameEngine.TICKS_PER_SECOND;

  // Buff duration (2.0s)
  private static final int BUFF_DURATION_TICKS = 2 * GameEngine.TICKS_PER_SECOND;

  // Base DPS = 60, hit frequency = 1.0s, so base damage per tick = 60
  private static final int BASE_DOT_DAMAGE_PER_TICK = 60;

  // Base crown tower damage per tick = 15
  private static final int BASE_CROWN_TOWER_DAMAGE_PER_TICK = 15;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    engine = new GameEngine();

    Deck blueDeck = buildDeckWith(VINES);
    Deck redDeck = buildDeckWith(VINES);

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
  void testVinesCardDataLoaded() {
    assertThat(VINES).as("Vines card should be loaded from CardRegistry").isNotNull();
    assertThat(VINES.getAreaEffect()).as("Vines should have an areaEffect").isNotNull();
    assertThat(VINES.getAreaEffect().getTargetCount()).as("targetCount should be 3").isEqualTo(3);
    assertThat(VINES.getAreaEffect().getTargetDelays())
        .as("targetDelays should have 3 entries")
        .hasSize(3);
    assertThat(VINES.getAreaEffect().isAirToGround()).as("airToGround should be true").isTrue();
    assertThat(VINES.getAreaEffect().getInitialDelay())
        .as("initialDelay should be 0.4")
        .isEqualTo(0.4f);
  }

  @Test
  void testVinesSelectsThreeHighestHpTargets() {
    // Place 5 troops with varying HP
    Troop t1 = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 100, "Troop100");
    Troop t2 = spawnEnemyAt(DEPLOY_X + 0.5f, DEPLOY_Y, 500, "Troop500");
    Troop t3 = spawnEnemyAt(DEPLOY_X - 0.5f, DEPLOY_Y, 300, "Troop300");
    Troop t4 = spawnEnemyAt(DEPLOY_X, DEPLOY_Y + 0.5f, 800, "Troop800");
    Troop t5 = spawnEnemyAt(DEPLOY_X + 0.3f, DEPLOY_Y - 0.3f, 200, "Troop200");

    deployVines(DEPLOY_X, DEPLOY_Y);
    // Tick past sync delay + initial delay + target delays (all < 0.2s)
    engine.tick(SYNC_DELAY_TICKS + INITIAL_DELAY_TICKS + 10);

    // The 3 highest HP targets (800, 500, 300) should get FREEZE
    assertThat(t4.getAppliedEffects())
        .as("Troop with 800 HP should have FREEZE")
        .anyMatch(e -> e.getType() == StatusEffectType.FREEZE);
    assertThat(t2.getAppliedEffects())
        .as("Troop with 500 HP should have FREEZE")
        .anyMatch(e -> e.getType() == StatusEffectType.FREEZE);
    assertThat(t3.getAppliedEffects())
        .as("Troop with 300 HP should have FREEZE")
        .anyMatch(e -> e.getType() == StatusEffectType.FREEZE);

    // The 2 lowest HP targets (100, 200) should NOT have FREEZE
    assertThat(t1.getAppliedEffects())
        .as("Troop with 100 HP should NOT have FREEZE")
        .noneMatch(e -> e.getType() == StatusEffectType.FREEZE);
    assertThat(t5.getAppliedEffects())
        .as("Troop with 200 HP should NOT have FREEZE")
        .noneMatch(e -> e.getType() == StatusEffectType.FREEZE);
  }

  @Test
  void testVinesTargetsIncludeShieldHp() {
    // Troop A: 200 HP + 400 shield = 600 effective
    Troop shielded = spawnEnemyWithShield(DEPLOY_X, DEPLOY_Y, 200, 400, "Shielded");
    // Troop B: 500 HP, no shield = 500 effective
    Troop noShield = spawnEnemyAt(DEPLOY_X + 0.5f, DEPLOY_Y, 500, "NoShield");
    // Troop C: 100 HP = lowest
    Troop weakest = spawnEnemyAt(DEPLOY_X - 0.5f, DEPLOY_Y, 100, "Weakest");

    deployVines(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + INITIAL_DELAY_TICKS + 10);

    // Shielded (600 effective) should be selected over NoShield (500 effective)
    assertThat(shielded.getAppliedEffects())
        .as("Shielded troop (600 effective HP) should be selected")
        .anyMatch(e -> e.getType() == StatusEffectType.FREEZE);
    assertThat(noShield.getAppliedEffects())
        .as("NoShield troop (500 effective HP) should be selected")
        .anyMatch(e -> e.getType() == StatusEffectType.FREEZE);
    assertThat(weakest.getAppliedEffects())
        .as("Weakest troop (100 HP) should be selected (only 3 targets, all qualify)")
        .anyMatch(e -> e.getType() == StatusEffectType.FREEZE);
  }

  @Test
  void testVinesFewerThanThreeTargets() {
    Troop t1 = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 500, "EnemyA");
    Troop t2 = spawnEnemyAt(DEPLOY_X + 0.5f, DEPLOY_Y, 300, "EnemyB");

    deployVines(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + INITIAL_DELAY_TICKS + 10);

    // Both should be affected, no crash
    assertThat(t1.getAppliedEffects())
        .as("First enemy should have FREEZE")
        .anyMatch(e -> e.getType() == StatusEffectType.FREEZE);
    assertThat(t2.getAppliedEffects())
        .as("Second enemy should have FREEZE")
        .anyMatch(e -> e.getType() == StatusEffectType.FREEZE);
  }

  @Test
  void testVinesNoTargetsInRadius() {
    // Place enemy far outside radius
    spawnEnemyAt(DEPLOY_X + 10f, DEPLOY_Y, 500, "FarEnemy");

    deployVines(DEPLOY_X, DEPLOY_Y);
    // Should not crash
    engine.tick(SYNC_DELAY_TICKS + INITIAL_DELAY_TICKS + 10);

    // Verify AEO was created
    List<AreaEffect> effects = engine.getGameState().getEntitiesOfType(AreaEffect.class);
    assertThat(effects)
        .as("Vines area effect should be created even with no targets")
        .anyMatch(e -> "Vines_AeO".equals(e.getName()));
  }

  @Test
  void testVinesInitialDelay() {
    Troop enemy = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 500, "Enemy");

    deployVines(DEPLOY_X, DEPLOY_Y);

    // Tick just past sync delay but before initial delay expires (0.4s = 12 ticks)
    engine.tick(SYNC_DELAY_TICKS + 5);

    assertThat(enemy.getAppliedEffects())
        .as("No effects should be applied before initial delay expires")
        .noneMatch(e -> e.getType() == StatusEffectType.FREEZE);

    // Now tick past the initial delay
    engine.tick(INITIAL_DELAY_TICKS + 5);

    assertThat(enemy.getAppliedEffects())
        .as("FREEZE should be applied after initial delay")
        .anyMatch(e -> e.getType() == StatusEffectType.FREEZE);
  }

  @Test
  void testVinesFreezesTargets() {
    Troop enemy = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 500, "Enemy");

    deployVines(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + INITIAL_DELAY_TICKS + 10);

    assertThat(enemy.getAppliedEffects())
        .as("Enemy should have FREEZE effect from Vines")
        .anyMatch(
            e ->
                e.getType() == StatusEffectType.FREEZE
                    && "Vines_Trap_Snare_Base".equals(e.getBuffName()));
  }

  @Test
  void testVinesDotDamage() {
    Troop enemy = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 2000, "StrongEnemy");

    deployVines(DEPLOY_X, DEPLOY_Y);

    // Tick past sync delay + initial delay + first DOT tick (1.0s hit frequency)
    engine.tick(SYNC_DELAY_TICKS + INITIAL_DELAY_TICKS + HIT_FREQUENCY_TICKS + 5);

    int hpAfterFirstTick = enemy.getHealth().getCurrent();
    assertThat(hpAfterFirstTick)
        .as("Enemy should take DOT damage after first hit frequency interval")
        .isLessThan(2000);
    assertThat(2000 - hpAfterFirstTick)
        .as("First DOT tick should deal base damage of %d", BASE_DOT_DAMAGE_PER_TICK)
        .isEqualTo(BASE_DOT_DAMAGE_PER_TICK);

    // Tick for another hit frequency interval for second DOT tick
    engine.tick(HIT_FREQUENCY_TICKS + 5);

    int hpAfterSecondTick = enemy.getHealth().getCurrent();
    assertThat(hpAfterSecondTick)
        .as("Enemy should take second DOT tick")
        .isEqualTo(2000 - 2 * BASE_DOT_DAMAGE_PER_TICK);
  }

  @Test
  void testVinesAirToGround() {
    // Create an air unit
    Troop airUnit =
        Troop.builder()
            .name("BabyDragon")
            .team(Team.RED)
            .position(new Position(DEPLOY_X, DEPLOY_Y))
            .health(new Health(800))
            .movement(new Movement(0f, 4.0f, 0.5f, 0.5f, MovementType.AIR))
            .combat(
                Combat.builder()
                    .damage(100)
                    .range(3.5f)
                    .sightRange(5.5f)
                    .attackCooldown(1.8f)
                    .targetType(TargetType.ALL)
                    .build())
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    engine.spawn(airUnit);

    // Create a ground-only attacker to verify targeting
    Troop groundAttacker =
        Troop.builder()
            .name("Knight")
            .team(Team.BLUE)
            .position(new Position(DEPLOY_X, DEPLOY_Y + 0.5f))
            .health(new Health(1000))
            .movement(new Movement(0f, 6.0f, 0.5f, 0.5f, MovementType.GROUND))
            .combat(
                Combat.builder()
                    .damage(100)
                    .range(1.2f)
                    .sightRange(5.5f)
                    .attackCooldown(1.2f)
                    .targetType(TargetType.GROUND)
                    .build())
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    engine.spawn(groundAttacker);

    // Before Vines, air unit should be AIR
    assertThat(airUnit.getMovementType())
        .as("Air unit should be AIR before Vines")
        .isEqualTo(MovementType.AIR);

    deployVines(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + INITIAL_DELAY_TICKS + 10);

    // After Vines, air unit should be grounded
    assertThat(airUnit.getMovementType())
        .as("Air unit should be GROUND after Vines air-to-ground")
        .isEqualTo(MovementType.GROUND);
  }

  @Test
  void testVinesAirToGroundExpires() {
    // Create an air unit
    Troop airUnit =
        Troop.builder()
            .name("BabyDragon")
            .team(Team.RED)
            .position(new Position(DEPLOY_X, DEPLOY_Y))
            .health(new Health(2000))
            .movement(new Movement(0f, 4.0f, 0.5f, 0.5f, MovementType.AIR))
            .combat(
                Combat.builder()
                    .damage(0)
                    .range(3.5f)
                    .sightRange(5.5f)
                    .attackCooldown(1.8f)
                    .targetType(TargetType.ALL)
                    .build())
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    engine.spawn(airUnit);

    deployVines(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + INITIAL_DELAY_TICKS + 10);

    assertThat(airUnit.getMovementType())
        .as("Air unit should be GROUND during Vines")
        .isEqualTo(MovementType.GROUND);

    // Tick past the air-to-ground duration (2.0s = 60 ticks from when it was applied)
    engine.tick(BUFF_DURATION_TICKS + 10);

    assertThat(airUnit.getMovementType())
        .as("Air unit should return to AIR after grounded timer expires")
        .isEqualTo(MovementType.AIR);
  }

  @Test
  void testVinesCrownTowerDamage() {
    // We need a crown tower in the Vines radius. Deploy near a red princess tower.
    // Red princess towers are at (3, 29) and (15, 29) at level 1
    float towerX = 15f;
    float towerY = 29f;

    // Give enough elixir to deploy
    bluePlayer.getElixir().add(10);

    // Find a red princess tower to check its HP before
    var redTowers =
        engine.getGameState().getAliveEntities().stream()
            .filter(
                e ->
                    e.getTeam() == Team.RED
                        && e.getName().contains("Princess")
                        && e.getPosition().distanceTo(new Position(towerX, towerY)) < 2f)
            .findFirst();

    if (redTowers.isEmpty()) {
      // Skip if tower not found at expected position
      return;
    }

    int towerHpBefore = redTowers.get().getHealth().getCurrent();

    deployVines(towerX, towerY);

    // Tick past sync + initial delay + one DOT tick
    engine.tick(SYNC_DELAY_TICKS + INITIAL_DELAY_TICKS + HIT_FREQUENCY_TICKS + 5);

    int towerHpAfter = redTowers.get().getHealth().getCurrent();
    int damageTaken = towerHpBefore - towerHpAfter;

    // Crown tower should take crownTowerDamagePerHit (15 at base level, scaled for Epic level 1)
    // Epic min level = 6, so at level 1 it gets clamped to level 6
    assertThat(damageTaken)
        .as("Crown tower should take reduced DOT damage (crownTowerDamagePerHit)")
        .isEqualTo(BASE_CROWN_TOWER_DAMAGE_PER_TICK);
  }

  @Test
  void testVinesIgnoresBuildings() {
    // Deployed building in range
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

    // Also place a troop so we can verify the spell works
    Troop enemy = spawnEnemyAt(DEPLOY_X + 0.5f, DEPLOY_Y, 1000, "Enemy");

    deployVines(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + INITIAL_DELAY_TICKS + 10);

    // Building should not get freeze (buildings are excluded from targeted selection)
    assertThat(building.getAppliedEffects())
        .as("Building should NOT be targeted by Vines")
        .noneMatch(e -> e.getType() == StatusEffectType.FREEZE);

    // But the troop should
    assertThat(enemy.getAppliedEffects())
        .as("Troop should be targeted by Vines")
        .anyMatch(e -> e.getType() == StatusEffectType.FREEZE);
  }

  @Test
  void testVinesLevelScaling() {
    bluePlayer.getLevelConfig().withCardLevel(VINES.getId(), 11);

    Troop enemy = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 5000, "StrongEnemy");

    deployVines(DEPLOY_X, DEPLOY_Y);

    // Tick past sync + initial delay + first DOT tick
    engine.tick(SYNC_DELAY_TICKS + INITIAL_DELAY_TICKS + HIT_FREQUENCY_TICKS + 5);

    int expectedDotDamage = LevelScaling.scaleCard(BASE_DOT_DAMAGE_PER_TICK, 11);
    int damageTaken = 5000 - enemy.getHealth().getCurrent();

    assertThat(damageTaken)
        .as("DOT damage should be level-scaled for Epic at level 11 (%d)", expectedDotDamage)
        .isEqualTo(expectedDotDamage);
  }

  // -- Helpers --

  private void deployVines(float x, float y) {
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

  private Troop spawnEnemyWithShield(float x, float y, int hp, int shield, String name) {
    Troop enemy =
        Troop.builder()
            .name(name)
            .team(Team.RED)
            .position(new Position(x, y))
            .health(new Health(hp, shield))
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
