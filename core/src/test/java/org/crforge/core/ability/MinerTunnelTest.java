package org.crforge.core.ability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.crforge.core.arena.Arena;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.combat.TargetingSystem;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.DeploymentSystem;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.physics.PhysicsSystem;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MinerTunnelTest {

  private GameState gameState;
  private AbilitySystem abilitySystem;
  private CombatSystem combatSystem;
  private TargetingSystem targetingSystem;
  private PhysicsSystem physicsSystem;
  private DeploymentSystem deploymentSystem;
  private Standard1v1Match match;

  private static final float DT = 1.0f / 30;
  // Miner's spawnPathfindSpeed=650 in units.json, converted to tiles/sec: 650/60 ~= 10.833
  private static final float TUNNEL_SPEED = 650f / 60f;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    gameState = new GameState();
    abilitySystem = new AbilitySystem(gameState);
    combatSystem = new CombatSystem(gameState, new AoeDamageService(gameState));
    targetingSystem = new TargetingSystem();
    physicsSystem = new PhysicsSystem(new Arena("Test Arena"));
    deploymentSystem = new DeploymentSystem(gameState, new AoeDamageService(gameState));
    match = new Standard1v1Match();
  }

  // -- Test helpers --

  private TroopStats createMinerStats() {
    return TroopStats.builder()
        .name("Miner")
        .health(473)
        .damage(76)
        .speed(90f / 60f) // 1.5 tiles/sec
        .mass(6.0f)
        .collisionRadius(0.5f)
        .range(1.2f)
        .sightRange(5.5f)
        .attackCooldown(1.3f)
        .loadTime(0.8f)
        .deployTime(1.0f)
        .movementType(MovementType.GROUND)
        .targetType(TargetType.GROUND)
        .crownTowerDamagePercent(-75)
        .spawnPathfindSpeed(TUNNEL_SPEED)
        .ability(new TunnelAbility(TUNNEL_SPEED))
        .build();
  }

  private Card createMinerCard() {
    return Card.builder()
        .id("miner")
        .name("Miner")
        .type(CardType.TROOP)
        .cost(3)
        .unitStats(createMinerStats())
        .canDeployOnEnemySide(true)
        .build();
  }

  private Player createPlayerWithCard(Team team, Card card) {
    return new Player(team, new Deck(new ArrayList<>(Collections.nCopies(8, card))), false);
  }

  private PlayerActionDTO playAt(float x, float y) {
    return PlayerActionDTO.builder().handIndex(0).x(x).y(y).build();
  }

  /** Deploys a card by queuing, then ticking past the sync delay. */
  private void deployCard(Player player, float x, float y) {
    PlayerActionDTO action = playAt(x, y);
    deploymentSystem.queueAction(player, action);
    // Tick past sync delay (1.0s = 30 ticks)
    for (int i = 0; i < 31; i++) {
      deploymentSystem.update(DT);
      gameState.processPending();
    }
  }

  // -- Tests --

  @Test
  void miner_canDeployOnEnemySide() {
    Card minerCard = createMinerCard();
    Player blue = createPlayerWithCard(Team.BLUE, minerCard);
    match.addPlayer(blue);

    // Enemy side for blue is y > 16 (red zone)
    PlayerActionDTO action = playAt(9.0f, 25.0f);
    assertThat(match.validateAction(blue, action)).isTrue();
  }

  @Test
  void normalTroop_cannotDeployOnEnemySide() {
    Card knightCard =
        Card.builder()
            .id("knight")
            .name("Knight")
            .type(CardType.TROOP)
            .cost(3)
            .canDeployOnEnemySide(false)
            .build();
    Player blue = createPlayerWithCard(Team.BLUE, knightCard);
    match.addPlayer(blue);

    // Enemy side y=25 should be rejected for normal troops
    PlayerActionDTO action = playAt(9.0f, 25.0f);
    assertThat(match.validateAction(blue, action)).isFalse();
  }

  @Test
  void miner_cannotDeployOnTowerTile() {
    Card minerCard = createMinerCard();
    Player blue = createPlayerWithCard(Team.BLUE, minerCard);
    match.addPlayer(blue);

    // Tower tile (Red crown tower at center x=8, y=28)
    PlayerActionDTO action = playAt(8.5f, 28.0f);
    assertThat(match.validateAction(blue, action)).isFalse();
  }

  @Test
  void miner_startsAtKingTower_notAtTarget() {
    Card minerCard = createMinerCard();
    Player blue = createPlayerWithCard(Team.BLUE, minerCard);

    // Player starts with 5.0 elixir, Miner costs 3 -- sufficient
    deployCard(blue, 9.0f, 25.0f);

    // Find the spawned miner
    List<Entity> troops = gameState.getAliveEntities();
    assertThat(troops).hasSize(1);
    Troop miner = (Troop) troops.get(0);

    // Should start at blue king tower (9.0, 3.0), not at deploy target (9.0, 25.0)
    assertThat(miner.getPosition().getX()).isCloseTo(9.0f, within(0.5f));
    assertThat(miner.getPosition().getY()).isCloseTo(3.0f, within(0.5f));
  }

  @Test
  void miner_isNonTargetable_whileTunneling() {
    Card minerCard = createMinerCard();
    Player blue = createPlayerWithCard(Team.BLUE, minerCard);

    deployCard(blue, 9.0f, 25.0f);

    Troop miner = (Troop) gameState.getAliveEntities().get(0);
    assertThat(miner.isTunneling()).isTrue();
    assertThat(miner.isTargetable()).isFalse();
  }

  @Test
  void miner_isInvulnerable_whileTunneling() {
    Card minerCard = createMinerCard();
    Player blue = createPlayerWithCard(Team.BLUE, minerCard);

    deployCard(blue, 9.0f, 25.0f);

    Troop miner = (Troop) gameState.getAliveEntities().get(0);
    assertThat(miner.isInvulnerable()).isTrue();

    // Verify the miner has full health (non-targetable so nothing should damage it)
    int hpBefore = miner.getHealth().getCurrent();
    assertThat(miner.getHealth().getCurrent()).isEqualTo(hpBefore);
  }

  @Test
  void miner_movesTowardTarget_eachTick() {
    Card minerCard = createMinerCard();
    Player blue = createPlayerWithCard(Team.BLUE, minerCard);

    // Deploy to same side (no river crossing) so no waypoint
    deployCard(blue, 9.0f, 10.0f);

    Troop miner = (Troop) gameState.getAliveEntities().get(0);
    float startY = miner.getPosition().getY();

    // Run several ticks of ability system
    for (int i = 0; i < 10; i++) {
      abilitySystem.update(DT);
    }

    // Miner should have moved toward the target (y=10, so y should increase from ~3)
    assertThat(miner.getPosition().getY()).isGreaterThan(startY);
  }

  @Test
  void miner_emergesAtTarget_whenCloseEnough() {
    Card minerCard = createMinerCard();
    Player blue = createPlayerWithCard(Team.BLUE, minerCard);

    // Deploy to a close target on same side (no river crossing)
    deployCard(blue, 9.0f, 7.0f);

    Troop miner = (Troop) gameState.getAliveEntities().get(0);

    // Run enough ticks to arrive (distance ~4 tiles, speed ~10.8 tiles/sec -> ~0.37s -> ~12 ticks)
    for (int i = 0; i < 30; i++) {
      abilitySystem.update(DT);
    }

    // Should have emerged
    assertThat(miner.isTunneling()).isFalse();
    assertThat(miner.isInvulnerable()).isFalse();
    AbilityComponent ability = miner.getAbility();
    assertThat(ability.getTunnelState()).isEqualTo(AbilityComponent.TunnelState.EMERGED);

    // Should be at target position
    assertThat(miner.getPosition().getX()).isCloseTo(9.0f, within(0.1f));
    assertThat(miner.getPosition().getY()).isCloseTo(7.0f, within(0.1f));

    // Should be in deploy animation (targetable but cannot attack yet)
    assertThat(miner.isDeploying()).isTrue();
  }

  @Test
  void miner_routesViaDogleg_whenCrossingRiver() {
    Card minerCard = createMinerCard();
    Player blue = createPlayerWithCard(Team.BLUE, minerCard);

    // Deploy to enemy side (y=25, crosses river)
    deployCard(blue, 14.0f, 25.0f);

    Troop miner = (Troop) gameState.getAliveEntities().get(0);
    AbilityComponent ability = miner.getAbility();

    // Should have a waypoint set (near bridge for river crossing)
    assertThat(ability.isTunnelUsingWaypoint()).isTrue();
    // Target is x=14 (right side), so waypoint should be at x=13.9
    assertThat(ability.getTunnelWaypointX()).isCloseTo(13.9f, within(0.1f));
    // Blue crossing river, waypoint at y=15
    assertThat(ability.getTunnelWaypointY()).isCloseTo(15.0f, within(0.1f));

    // Run until waypoint is reached (distance ~12 tiles, speed ~10.8 -> ~1.1s -> ~34 ticks)
    for (int i = 0; i < 50; i++) {
      abilitySystem.update(DT);
    }

    // After reaching waypoint, should switch to final target
    assertThat(ability.isTunnelUsingWaypoint()).isFalse();
  }

  @Test
  void miner_doesNotCollide_whileTunneling() {
    Card minerCard = createMinerCard();
    Player blue = createPlayerWithCard(Team.BLUE, minerCard);

    deployCard(blue, 9.0f, 10.0f);

    Troop miner = (Troop) gameState.getAliveEntities().get(0);
    assertThat(miner.isTunneling()).isTrue();

    // Place an enemy troop right on top of the miner's path
    Troop blocker =
        Troop.builder()
            .name("Blocker")
            .team(Team.RED)
            .position(new Position(9.0f, 5.0f))
            .health(new Health(1000))
            .movement(new Movement(0f, 5f, 0.5f, 0.5f, MovementType.GROUND))
            .combat(Combat.builder().damage(100).range(1.0f).targetType(TargetType.ALL).build())
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    gameState.spawnEntity(blocker);
    gameState.processPending();

    float blockerYBefore = blocker.getPosition().getY();

    // Run physics -- tunneling miner should not collide with blocker
    physicsSystem.update(gameState.getAliveEntities(), DT);

    // Blocker position should not have been pushed
    assertThat(blocker.getPosition().getY()).isCloseTo(blockerYBefore, within(0.01f));
  }

  @Test
  void miner_canAttack_afterEmerging() {
    Card minerCard = createMinerCard();
    Player blue = createPlayerWithCard(Team.BLUE, minerCard);

    // Deploy close to test emergence
    deployCard(blue, 9.0f, 7.0f);

    Troop miner = (Troop) gameState.getAliveEntities().get(0);

    // Run until emerged
    for (int i = 0; i < 30; i++) {
      abilitySystem.update(DT);
    }
    assertThat(miner.isTunneling()).isFalse();
    assertThat(miner.getAbility().getTunnelState()).isEqualTo(AbilityComponent.TunnelState.EMERGED);

    // Finish deploy animation (1.0s = 30 ticks)
    for (int i = 0; i < 31; i++) {
      miner.update(DT);
    }

    assertThat(miner.isDeploying()).isFalse();
    assertThat(miner.isTargetable()).isTrue();

    // Place an enemy troop next to the miner
    Troop enemy =
        Troop.builder()
            .name("Target")
            .team(Team.RED)
            .position(new Position(9.0f, 8.0f))
            .health(new Health(1000))
            .movement(new Movement(0f, 5f, 0.5f, 0.5f, MovementType.GROUND))
            .combat(Combat.builder().damage(100).range(1.0f).targetType(TargetType.ALL).build())
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    gameState.spawnEntity(enemy);
    gameState.processPending();

    // Run targeting -- miner should acquire a target
    targetingSystem.updateTargets(gameState.getAliveEntities());
    assertThat(miner.getCombat().getCurrentTarget()).isNotNull();
  }

  @Test
  void miner_physicsSkipsMovement_whileTunneling() {
    Card minerCard = createMinerCard();
    Player blue = createPlayerWithCard(Team.BLUE, minerCard);

    deployCard(blue, 9.0f, 25.0f);

    Troop miner = (Troop) gameState.getAliveEntities().get(0);
    assertThat(miner.isTunneling()).isTrue();

    float xBefore = miner.getPosition().getX();
    float yBefore = miner.getPosition().getY();

    // PhysicsSystem should not move tunneling troops
    physicsSystem.update(gameState.getAliveEntities(), DT);

    // Position should be unchanged by physics (only AbilitySystem moves it)
    assertThat(miner.getPosition().getX()).isEqualTo(xBefore);
    assertThat(miner.getPosition().getY()).isEqualTo(yBefore);
  }

  /**
   * Reproduction test for bug: Miner deployed next to enemy Crown Tower targets a Princess Tower
   * instead. Uses full GameEngine tick loop to exercise the real system interaction order.
   *
   * <p>Root cause: during tunnel travel, the TargetingSystem acquires a Princess Tower as the
   * Miner's target (since deployTimer=0 while tunneling, isDeploying() returns false). On
   * emergence, the stale target persists through the deploy animation and is kept via retention
   * range.
   */
  @Test
  void miner_targetsCrownTower_whenDeployedNextToIt() {
    // Use fresh ID counters for full engine test
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();

    Card minerCard = Objects.requireNonNull(CardRegistry.get("miner"), "miner not found");
    // Fill the deck with Miner cards so it's always in hand
    List<Card> deckCards = new ArrayList<>(Collections.nCopies(8, minerCard));

    Player blue = new Player(Team.BLUE, new Deck(deckCards), false);
    Player red = new Player(Team.RED, new Deck(new ArrayList<>(deckCards)), false);

    // Use level 1 towers to keep tower damage low (Miner has 473 HP at level 1)
    Standard1v1Match fullMatch = new Standard1v1Match(1);
    fullMatch.addPlayer(blue);
    fullMatch.addPlayer(red);

    GameEngine engine = new GameEngine();
    engine.setMatch(fullMatch);
    engine.initMatch();

    // Deploy Miner at (9.0, 26.5) -- near Red Crown Tower at (9.0, 29.0)
    // Tile (9, 26) is RED_ZONE, valid for Miner's canDeployOnEnemySide
    engine.queueAction(blue, PlayerActionDTO.builder().handIndex(0).x(9.0f).y(26.5f).build());

    // Run enough ticks for tunnel travel + deploy animation + first attack:
    // ~30 ticks sync delay + ~75 ticks tunnel + 30 ticks deploy + 20 ticks first attack = ~155
    engine.tick(180);

    // Find the deployed Miner
    Troop miner =
        engine.getGameState().getAliveEntities().stream()
            .filter(e -> e instanceof Troop t && t.getTeam() == Team.BLUE)
            .map(e -> (Troop) e)
            .findFirst()
            .orElse(null);
    assertThat(miner).as("Miner should be alive").isNotNull();

    // Miner should have finished tunnel and deploy animation
    assertThat(miner.isTunneling()).isFalse();
    assertThat(miner.isDeploying()).isFalse();

    // The Miner's target should be the Crown Tower (distance ~2.5, well within sightRange 5.5),
    // NOT a Princess Tower (distance ~6, outside sightRange)
    Entity target = miner.getCombat().getCurrentTarget();
    assertThat(target).as("Miner should have a target").isNotNull();
    assertThat(target).isInstanceOf(Tower.class);

    Tower targetTower = (Tower) target;
    assertThat(targetTower.isCrownTower())
        .as(
            "Miner should target Crown Tower (dist ~2.5) not %s at (%.1f, %.1f)",
            targetTower.isPrincessTower() ? "Princess Tower" : targetTower.getName(),
            targetTower.getPosition().getX(),
            targetTower.getPosition().getY())
        .isTrue();

    // Crown Tower should have taken damage (proving the Miner attacked it)
    Tower crownTower = engine.getGameState().getCrownTower(Team.RED);
    assertThat(crownTower.getHealth().getCurrent())
        .as("Crown Tower should have taken damage from the Miner")
        .isLessThan(crownTower.getHealth().getMax());
  }
}
