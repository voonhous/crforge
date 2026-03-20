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
 * Tests for Graveyard spell: a 5-elixir Legendary spell that places a 9-second area effect spawning
 * 13 Skeletons at 8 predefined positions with staggered delays from 2.2s to 8.7s.
 */
class GraveyardTest {

  private GameEngine engine;
  private Player bluePlayer;

  private static final Card GRAVEYARD = CardRegistry.get("graveyard");

  // Deploy at y=14 to avoid tower aggro
  private static final float DEPLOY_X = 9f;
  private static final float DEPLOY_Y = 14f;

  // Placement sync delay (1.0s = 30 ticks)
  private static final int SYNC_DELAY_TICKS = 30;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    engine = new GameEngine();

    Deck blueDeck = buildDeckWith(GRAVEYARD);
    Deck redDeck = buildDeckWith(GRAVEYARD);

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
  void cardDataLoaded() {
    assertThat(GRAVEYARD).as("Graveyard card should be loaded").isNotNull();
    assertThat(GRAVEYARD.getAreaEffect()).as("Should have an area effect").isNotNull();
    assertThat(GRAVEYARD.getAreaEffect().getSpawnSequence())
        .as("Should have 13 spawn sequence entries")
        .hasSize(13);
    assertThat(GRAVEYARD.getAreaEffect().getSpawnCharacter())
        .as("Spawn character should be resolved")
        .isNotNull();
    assertThat(GRAVEYARD.getAreaEffect().getSpawnCharacter().getName())
        .as("Spawn character should be Skeleton")
        .isEqualTo("Skeleton");
  }

  @Test
  void areaEffectCreatedOnDeploy() {
    deployGraveyard(DEPLOY_X, DEPLOY_Y);

    // After sync delay, an AreaEffect entity should appear
    engine.tick(SYNC_DELAY_TICKS + 2);

    List<AreaEffect> effects = engine.getGameState().getEntitiesOfType(AreaEffect.class);
    assertThat(effects)
        .as("Graveyard should create an AreaEffect named Graveyard_rework")
        .anyMatch(e -> "Graveyard_rework".equals(e.getName()));
  }

  @Test
  void areaEffectDoesNoDamage() {
    Troop enemy = spawnEnemyAt(DEPLOY_X, DEPLOY_Y, 1000);

    deployGraveyard(DEPLOY_X, DEPLOY_Y);
    // Tick just before the first skeleton spawns (2.2s = 66 ticks) so skeletons cannot attack
    engine.tick(SYNC_DELAY_TICKS + 60);

    assertThat(enemy.getHealth().getCurrent())
        .as(
            "Enemy should take 0 damage from the area effect itself (hitsGround=false, hitsAir=false)")
        .isEqualTo(1000);
  }

  @Test
  void noSkeletonsBeforeFirstSpawnDelay() {
    deployGraveyard(DEPLOY_X, DEPLOY_Y);

    // First skeleton spawns at 2.2s after AE creation. Tick sync delay + just under 2.2s (65 ticks)
    engine.tick(SYNC_DELAY_TICKS + 65);

    long skeletonCount = countSkeletons();
    assertThat(skeletonCount).as("No skeletons should spawn before 2.2s").isEqualTo(0);
  }

  @Test
  void firstSkeletonSpawnsOnTime() {
    deployGraveyard(DEPLOY_X, DEPLOY_Y);

    // First skeleton at 2.2s = 66 ticks. Add a couple extra for processing.
    engine.tick(SYNC_DELAY_TICKS + 68);

    long skeletonCount = countSkeletons();
    assertThat(skeletonCount).as("1 skeleton should spawn after 2.2s").isEqualTo(1);
  }

  @Test
  void all13SkeletonsSpawnByEnd() {
    deployGraveyard(DEPLOY_X, DEPLOY_Y);

    // Last skeleton at 8.7s = 261 ticks. Add extra buffer.
    engine.tick(SYNC_DELAY_TICKS + 270);

    long skeletonCount = countSkeletons();
    assertThat(skeletonCount).as("All 13 skeletons should have spawned by 8.7s").isEqualTo(13);
  }

  @Test
  void spawnTimingProgression() {
    deployGraveyard(DEPLOY_X, DEPLOY_Y);

    // After 4.0s = 120 ticks: entries with delay <= 4.0s are 2.2, 2.7, 3.3, 3.8 = 4 skeletons
    engine.tick(SYNC_DELAY_TICKS + 122);
    assertThat(countSkeletons()).as("4 skeletons should spawn by 4.0s").isEqualTo(4);

    // After 6.0s = 180 ticks: entries with delay <= 6.0s are indices 0-7 = 8 skeletons
    engine.tick(60);
    assertThat(countSkeletons()).as("8 skeletons should spawn by 6.0s").isEqualTo(8);
  }

  @Test
  void skeletonHasCorrectBaseStats() {
    deployGraveyard(DEPLOY_X, DEPLOY_Y);

    // Wait for first skeleton to finish deploying
    engine.tick(SYNC_DELAY_TICKS + 100);

    Troop skeleton = findFirstSkeleton();
    assertThat(skeleton.getHealth().getMax()).as("Skeleton HP").isEqualTo(32);
    assertThat(skeleton.getCombat().getDamage()).as("Skeleton damage").isEqualTo(32);
  }

  @Test
  void positionMirroringLeftSide() {
    // Deploy on left side (x < 9)
    float leftX = 5f;
    deployGraveyard(leftX, DEPLOY_Y);

    // Wait for first skeleton to spawn (delay=2.2s, relativeX=0, relativeY=-3.5)
    engine.tick(SYNC_DELAY_TICKS + 68);

    Troop skeleton = findFirstSkeleton();
    // BLUE team: yMirror=+1, left side: xMirror=+1
    // First entry: relativeX=0, relativeY=-3.5 -> spawnY = 14 + (-3.5)*1 = 10.5
    assertThat(skeleton.getPosition().getX()).as("Skeleton X on left side").isEqualTo(leftX);
    assertThat(skeleton.getPosition().getY())
        .as("Skeleton Y on left side")
        .isEqualTo(DEPLOY_Y - 3.5f);
  }

  @Test
  void positionMirroringRightSide() {
    // Deploy on right side (x > 9)
    float rightX = 13f;
    deployGraveyard(rightX, DEPLOY_Y);

    // Wait for second skeleton (delay=2.7s, relativeX=-3.5, relativeY=0)
    engine.tick(SYNC_DELAY_TICKS + 83);

    List<Troop> skeletons = findAllSkeletons();
    assertThat(skeletons)
        .as("At least 2 skeletons should have spawned")
        .hasSizeGreaterThanOrEqualTo(2);
    // Second skeleton: relativeX=-3.5, xMirror=-1 -> spawnX = 13 + (-3.5)*(-1) = 16.5
    Troop second = skeletons.get(1);
    assertThat(second.getPosition().getX())
        .as("X offset should be negated on right side")
        .isEqualTo(16.5f);
  }

  @Test
  void levelScalingAppliedToSkeletons() {
    bluePlayer.getLevelConfig().withCardLevel(GRAVEYARD.getId(), 11);

    deployGraveyard(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + 100);

    Troop skeleton = findFirstSkeleton();
    int expectedHp = LevelScaling.scaleCard(32, 11);
    assertThat(skeleton.getHealth().getMax())
        .as("Skeleton HP should be level-scaled (%d)", expectedHp)
        .isEqualTo(expectedHp);
  }

  @Test
  void multipleGraveyardsIndependent() {
    // Deploy first graveyard
    deployGraveyard(DEPLOY_X, DEPLOY_Y);
    engine.tick(1);

    // Replenish elixir and deploy second
    bluePlayer.getElixir().add(10);
    deployGraveyard(DEPLOY_X, 20f);

    // Tick well past all spawn delays
    engine.tick(SYNC_DELAY_TICKS + 270);

    long totalSkeletons = countSkeletons();
    assertThat(totalSkeletons).as("Two graveyards should produce 26 total skeletons").isEqualTo(26);
  }

  @Test
  void areaEffectDiesAfterAllSpawns() {
    deployGraveyard(DEPLOY_X, DEPLOY_Y);

    // Tick past all spawns and lifetime (9s = 270 ticks + sync + buffer)
    engine.tick(SYNC_DELAY_TICKS + 280);

    List<AreaEffect> effects = engine.getGameState().getEntitiesOfType(AreaEffect.class);
    boolean anyAliveGraveyard =
        effects.stream().anyMatch(e -> "Graveyard_rework".equals(e.getName()) && e.isAlive());
    assertThat(anyAliveGraveyard)
        .as("Graveyard area effect should be dead after all spawns complete")
        .isFalse();
  }

  // -- Helpers --

  private void deployGraveyard(float x, float y) {
    PlayerActionDTO action = PlayerActionDTO.builder().handIndex(0).x(x).y(y).build();
    engine.queueAction(bluePlayer, action);
  }

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

  private long countSkeletons() {
    return engine.getGameState().getEntitiesOfType(Troop.class).stream()
        .filter(t -> "Skeleton".equals(t.getName()) && t.getTeam() == Team.BLUE)
        .count();
  }

  private Troop findFirstSkeleton() {
    return engine.getGameState().getEntitiesOfType(Troop.class).stream()
        .filter(t -> "Skeleton".equals(t.getName()) && t.getTeam() == Team.BLUE)
        .findFirst()
        .orElseThrow(() -> new AssertionError("No Skeleton found"));
  }

  private List<Troop> findAllSkeletons() {
    return engine.getGameState().getEntitiesOfType(Troop.class).stream()
        .filter(t -> "Skeleton".equals(t.getName()) && t.getTeam() == Team.BLUE)
        .toList();
  }

  private Deck buildDeckWith(Card card) {
    List<Card> cards = new java.util.ArrayList<>();
    for (int i = 0; i < 8; i++) {
      cards.add(card);
    }
    return new Deck(cards);
  }
}
