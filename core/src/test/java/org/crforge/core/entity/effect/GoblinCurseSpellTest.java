package org.crforge.core.entity.effect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.Card;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.Rarity;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.structure.Tower;
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
 * Tests for the GoblinCurse spell: a 2-elixir Epic spell that places a 3.0-tile-radius ticking area
 * effect lasting 6.0s. Each 1.0s tick applies 14 base damage (DPS-derived) and a CURSE buff (1.5s
 * duration). Enemies that die while cursed spawn a GoblinCurseGoblin for the caster's team. Crown
 * towers take absolute 4 base damage per tick.
 *
 * <p>At default level (Epic min = 6, 0 scaling steps): 14 damage/tick, 4 CT damage/tick.
 *
 * <p>At level 11 (5 scaling steps of x1.10): 22 damage/tick, 6 CT damage/tick.
 */
class GoblinCurseSpellTest {

  private GameEngine engine;
  private Player bluePlayer;

  private static final Card GOBLIN_CURSE = CardRegistry.get("goblincurse");

  // Deploy at y=18 (near river, out of princess tower range)
  private static final float DEPLOY_X = 9f;
  private static final float DEPLOY_Y = 18f;

  // Placement sync delay (1.0s)
  private static final int SYNC_DELAY_TICKS = GameEngine.TICKS_PER_SECOND;

  // Hit speed interval (1.0s)
  private static final int HIT_SPEED_TICKS = GameEngine.TICKS_PER_SECOND;

  // Total lifetime (6.0s)
  private static final int LIFETIME_TICKS = 6 * GameEngine.TICKS_PER_SECOND;

  // Base damage per tick at default level (scaleCard(14, 1) = 14)
  private static final int BASE_DAMAGE_PER_TICK = 14;

  // Base crown tower damage per tick (scaleCard(4, 1) = 4)
  private static final int BASE_CT_DAMAGE_PER_TICK = 4;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    engine = new GameEngine();

    Deck blueDeck = buildDeckWith(GOBLIN_CURSE);
    Deck redDeck = buildDeckWith(GOBLIN_CURSE);

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
  void goblincurseCardDataLoaded() {
    assertThat(GOBLIN_CURSE).as("GoblinCurse card should be loaded from CardRegistry").isNotNull();
    assertThat(GOBLIN_CURSE.getType().name()).isEqualTo("SPELL");
    assertThat(GOBLIN_CURSE.getRarity()).isEqualTo(Rarity.EPIC);
    assertThat(GOBLIN_CURSE.getCost()).isEqualTo(2);

    AreaEffectStats ae = GOBLIN_CURSE.getAreaEffect();
    assertThat(ae).as("GoblinCurse should have an areaEffect").isNotNull();
    assertThat(ae.getRadius()).isEqualTo(3.0f);
    assertThat(ae.getLifeDuration()).isEqualTo(6.0f);
    assertThat(ae.getHitSpeed()).isEqualTo(1.0f);
    assertThat(ae.isHitsGround()).isTrue();
    assertThat(ae.isHitsAir()).isTrue();
    assertThat(ae.isOnlyEnemies()).isTrue();
    assertThat(ae.getBuff()).isEqualTo("GoblinCurse");
    assertThat(ae.getBuffDuration()).isEqualTo(1.5f);
    assertThat(ae.getCurseSpawnStats()).as("curseSpawnStats should be resolved").isNotNull();
    assertThat(ae.getCurseSpawnStats().getName()).isEqualTo("GoblinCurseGoblin");
  }

  @Test
  void dotDamageAppliedPerTick() {
    Troop enemy = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 5000, "Enemy");

    deployGoblinCurse(DEPLOY_X, DEPLOY_Y);

    // Tick past sync delay + one full hitSpeed interval + small buffer
    engine.tick(SYNC_DELAY_TICKS + HIT_SPEED_TICKS + 2);

    int damageTaken = 5000 - enemy.getHealth().getCurrent();
    assertThat(damageTaken)
        .as("Enemy should take exactly one tick of damage: %d", BASE_DAMAGE_PER_TICK)
        .isEqualTo(BASE_DAMAGE_PER_TICK);
  }

  @Test
  void totalDamageOverFullDuration() {
    Troop enemy = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 5000, "Enemy");

    deployGoblinCurse(DEPLOY_X, DEPLOY_Y);

    // Tick through the entire lifetime plus buffer
    engine.tick(SYNC_DELAY_TICKS + LIFETIME_TICKS + 10);

    int damageTaken = 5000 - enemy.getHealth().getCurrent();
    // With hitSpeed=1.0 and lifetime=6.0, ticks fire at t=1,2,3,4,5,6 (6 ticks).
    int expectedTotal = BASE_DAMAGE_PER_TICK * 6;
    assertThat(damageTaken)
        .as(
            "Total damage over full duration should be %d (%d per tick * 6 ticks)",
            expectedTotal, BASE_DAMAGE_PER_TICK)
        .isEqualTo(expectedTotal);
  }

  @Test
  void crownTowerReducedDamage() {
    // Deploy near a red princess tower at (14.5, 25.5)
    float towerX = 14.5f;
    float towerY = 25.5f;

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

    deployGoblinCurse(towerX, towerY);

    // Tick through sync + one hitSpeed tick + buffer
    engine.tick(SYNC_DELAY_TICKS + HIT_SPEED_TICKS + 2);

    int damageTaken = towerHpBefore - redTower.get().getHealth().getCurrent();
    assertThat(damageTaken)
        .as("Crown tower should take absolute CT damage: %d per tick", BASE_CT_DAMAGE_PER_TICK)
        .isEqualTo(BASE_CT_DAMAGE_PER_TICK);
  }

  @Test
  void curseBuffAppliedToEnemies() {
    Troop enemy = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 5000, "Enemy");

    deployGoblinCurse(DEPLOY_X, DEPLOY_Y);

    // Tick past sync delay + one hitSpeed tick + buffer
    engine.tick(SYNC_DELAY_TICKS + HIT_SPEED_TICKS + 2);

    boolean hasCurse =
        enemy.getAppliedEffects().stream()
            .anyMatch(
                e ->
                    e.getType() == StatusEffectType.CURSE && "GoblinCurse".equals(e.getBuffName()));
    assertThat(hasCurse).as("Enemy should have GoblinCurse CURSE effect").isTrue();
  }

  @Test
  void cursedEnemySpawnsGoblinOnDeath() {
    // Use a low-HP enemy that will die from the curse damage
    Troop enemy = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 10, "CursedVictim");

    deployGoblinCurse(DEPLOY_X, DEPLOY_Y);

    // Tick past sync + hitSpeed (enemy takes 14 damage, dies at 10 HP)
    engine.tick(SYNC_DELAY_TICKS + HIT_SPEED_TICKS + 5);

    assertThat(enemy.isAlive()).as("Enemy should be dead").isFalse();

    // Check that a GoblinCurseGoblin was spawned for BLUE team (the caster)
    List<Troop> goblins =
        engine.getGameState().getEntitiesOfType(Troop.class).stream()
            .filter(t -> "GoblinCurseGoblin".equals(t.getName()))
            .toList();

    assertThat(goblins).as("A GoblinCurseGoblin should spawn on cursed enemy death").isNotEmpty();
  }

  @Test
  void spawnedGoblinFightsForCaster() {
    Troop enemy = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 10, "CursedVictim");

    deployGoblinCurse(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + HIT_SPEED_TICKS + 5);

    List<Troop> goblins =
        engine.getGameState().getEntitiesOfType(Troop.class).stream()
            .filter(t -> "GoblinCurseGoblin".equals(t.getName()))
            .toList();

    assertThat(goblins).isNotEmpty();
    // The spawned Goblin should fight for the BLUE team (caster), not RED (dying enemy)
    assertThat(goblins.get(0).getTeam())
        .as("Spawned Goblin should be on the caster's team (BLUE)")
        .isEqualTo(Team.BLUE);
  }

  @Test
  void buildingsNotCursed() {
    // Create a building in the curse radius
    Building building =
        Building.builder()
            .name("TestBuilding")
            .team(Team.RED)
            .position(new Position(DEPLOY_X, DEPLOY_Y))
            .health(new Health(100))
            .movement(new Movement(0f, 0f, 1.0f, 1.0f, MovementType.BUILDING))
            .lifetime(30f)
            .remainingLifetime(30f)
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    engine.spawn(building);

    deployGoblinCurse(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + HIT_SPEED_TICKS + 2);

    // Building should take damage
    assertThat(building.getHealth().getCurrent())
        .as("Building should take damage from GoblinCurse")
        .isLessThan(100);

    // But building should NOT have the CURSE buff
    boolean hasCurse =
        building.getAppliedEffects().stream().anyMatch(e -> e.getType() == StatusEffectType.CURSE);
    assertThat(hasCurse).as("Buildings should not receive CURSE buff").isFalse();
  }

  @Test
  void hitsGroundAndAirTargets() {
    Troop groundEnemy = spawnEnemyAt(DEPLOY_X - 0.5f, DEPLOY_Y, 5000, "GroundEnemy");

    Troop airEnemy =
        Troop.builder()
            .name("AirEnemy")
            .team(Team.RED)
            .position(new Position(DEPLOY_X + 0.5f, DEPLOY_Y))
            .health(new Health(5000))
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

    deployGoblinCurse(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + HIT_SPEED_TICKS + 2);

    assertThat(5000 - groundEnemy.getHealth().getCurrent())
        .as("Ground enemy should take damage")
        .isEqualTo(BASE_DAMAGE_PER_TICK);
    assertThat(5000 - airEnemy.getHealth().getCurrent())
        .as("Air enemy should take damage")
        .isEqualTo(BASE_DAMAGE_PER_TICK);

    // Both should have CURSE buff
    assertThat(
            groundEnemy.getAppliedEffects().stream()
                .anyMatch(e -> e.getType() == StatusEffectType.CURSE))
        .as("Ground enemy should have CURSE")
        .isTrue();
    assertThat(
            airEnemy.getAppliedEffects().stream()
                .anyMatch(e -> e.getType() == StatusEffectType.CURSE))
        .as("Air enemy should have CURSE")
        .isTrue();
  }

  @Test
  void overlappingCursesDontStackSpawn() {
    // Deploy two GoblinCurse on the same enemy
    Troop enemy = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 25, "CursedVictim");

    deployGoblinCurse(DEPLOY_X, DEPLOY_Y);
    bluePlayer.getElixir().add(10);
    engine.tick(1); // Process first deploy
    deployGoblinCurse(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + HIT_SPEED_TICKS + 10);

    // Enemy should be dead (takes 14*2 = 28 damage from two AEOs)
    assertThat(enemy.isAlive()).as("Enemy should be dead").isFalse();

    // Only 1 GoblinCurseGoblin should spawn (CURSE is non-stackable, same buff name -> refresh)
    long goblinCount =
        engine.getGameState().getEntitiesOfType(Troop.class).stream()
            .filter(t -> "GoblinCurseGoblin".equals(t.getName()))
            .count();
    assertThat(goblinCount)
        .as("Only 1 Goblin should spawn despite overlapping curses (same buff name)")
        .isEqualTo(1);
  }

  @Test
  void overlappingCursesStackDamage() {
    Troop enemy = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 5000, "Enemy");

    // Deploy two GoblinCurse overlapping
    deployGoblinCurse(DEPLOY_X, DEPLOY_Y);
    bluePlayer.getElixir().add(10);
    engine.tick(1);
    deployGoblinCurse(DEPLOY_X, DEPLOY_Y);

    // Tick past both sync delays + hitSpeed tick.
    // First AEO sync at tick 1, second AEO sync at tick 2. Both fire at their own hitSpeed.
    engine.tick(SYNC_DELAY_TICKS + HIT_SPEED_TICKS + 5);

    int damageTaken = 5000 - enemy.getHealth().getCurrent();
    // Both AEOs independently apply 14 damage per tick
    assertThat(damageTaken)
        .as(
            "Overlapping GoblinCurses should stack damage: 2 * %d = %d",
            BASE_DAMAGE_PER_TICK, BASE_DAMAGE_PER_TICK * 2)
        .isEqualTo(BASE_DAMAGE_PER_TICK * 2);
  }

  @Test
  void goblinCurseAndVoodooCurseCoexist() {
    Troop enemy = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 5000, "Enemy");

    // Apply VoodooCurse manually (as if from Mother Witch)
    enemy.addEffect(new AppliedEffect(StatusEffectType.CURSE, 5.0f, "VoodooCurse"));

    // Deploy GoblinCurse
    deployGoblinCurse(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + HIT_SPEED_TICKS + 2);

    // Both CURSE buffs should coexist (different buff names)
    long curseCount =
        enemy.getAppliedEffects().stream()
            .filter(e -> e.getType() == StatusEffectType.CURSE)
            .count();
    assertThat(curseCount).as("Both GoblinCurse and VoodooCurse should coexist").isEqualTo(2);
  }

  @Test
  void effectExpiresAfterDuration() {
    spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 5000, "Enemy");

    deployGoblinCurse(DEPLOY_X, DEPLOY_Y);

    // Tick past sync + full lifetime + buffer
    engine.tick(SYNC_DELAY_TICKS + LIFETIME_TICKS + 10);

    boolean goblinCurseAlive =
        engine.getGameState().getEntitiesOfType(AreaEffect.class).stream()
            .anyMatch(e -> "GoblinCurse".equals(e.getName()) && e.isAlive());
    assertThat(goblinCurseAlive).as("GoblinCurse area effect should be dead after 6.0s").isFalse();
  }

  @Test
  void levelScalingDamage() {
    // Reset and set up with level 11
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    engine = new GameEngine();

    Deck blueDeck = buildDeckWith(GOBLIN_CURSE);
    Deck redDeck = buildDeckWith(GOBLIN_CURSE);

    LevelConfig blueConfig = new LevelConfig(11);
    Player bluePlayerL11 = new Player(Team.BLUE, blueDeck, false, blueConfig);
    Player redPlayerL11 = new Player(Team.RED, redDeck, false);

    Standard1v1Match match = new Standard1v1Match(1);
    match.addPlayer(bluePlayerL11);
    match.addPlayer(redPlayerL11);
    engine.setMatch(match);
    engine.initMatch();
    bluePlayerL11.getElixir().add(10);

    Troop enemy = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 5000, "Enemy");

    // Deploy at level 11
    PlayerActionDTO action = PlayerActionDTO.builder().handIndex(0).x(DEPLOY_X).y(DEPLOY_Y).build();
    engine.queueAction(bluePlayerL11, action);
    engine.tick(SYNC_DELAY_TICKS + HIT_SPEED_TICKS + 2);

    // Level 11 damage: scaleCard(14, 11) -> 10 steps of x1.10 -> 35
    int expectedDamage = LevelScaling.scaleCard(14, 11);
    int damageTaken = 5000 - enemy.getHealth().getCurrent();
    assertThat(damageTaken)
        .as("Level 11 damage per tick should be %d", expectedDamage)
        .isEqualTo(expectedDamage);

    // Also verify CT damage at level 11
    int expectedCtDamage = LevelScaling.scaleCard(4, 11);
    assertThat(expectedCtDamage).as("CT damage at level 11 should be 10").isEqualTo(10);
  }

  // -- Helpers --

  private void deployGoblinCurse(float x, float y) {
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
