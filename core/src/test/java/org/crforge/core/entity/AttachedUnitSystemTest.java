package org.crforge.core.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.crforge.core.util.GameUnits.tiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.ability.DefaultCombatAbilityBridge;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.LiveSpawnConfig;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.combat.ProjectileSystem;
import org.crforge.core.component.AttachedComponent;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.engine.DeploymentSystem;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.base.TargetType;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Deck;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AttachedUnitSystemTest {

  private GameState gameState;
  private AttachedUnitSystem attachedUnitSystem;

  @BeforeEach
  void setUp() {
    gameState = new GameState();
    attachedUnitSystem = new AttachedUnitSystem(gameState);
  }

  /** Creates a parent troop at tile coordinates. */
  private Troop createParent(Team team, float x, float y) {
    return Troop.builder()
        .name("Ram")
        .team(team)
        .position(new Position(tiles(x), tiles(y)))
        .health(new Health(1000))
        .movement(new Movement(tiles(1.0), 8.0f, tiles(0.5), tiles(0.5), MovementType.GROUND))
        .combat(
            Combat.builder().damage(100).range(tiles(1.2)).targetType(TargetType.GROUND).build())
        .deployTime(0f)
        .deployTimer(0f)
        .build();
  }

  /** Creates a child attached at a local offset given in tiles. */
  private Troop createAttachedChild(Troop parent, String name, float offsetX, float offsetY) {
    int offsetXUnits = tiles(offsetX);
    int offsetYUnits = tiles(offsetY);
    AttachedComponent attached = new AttachedComponent(parent, offsetXUnits, offsetYUnits);
    return Troop.builder()
        .name(name)
        .team(parent.getTeam())
        .position(
            new Position(
                parent.getPosition().getX() + offsetXUnits,
                parent.getPosition().getY() + offsetYUnits))
        .health(new Health(500))
        .movement(new Movement(tiles(1.0), 4.0f, tiles(0.3), tiles(0.3), MovementType.GROUND))
        .combat(
            Combat.builder()
                .damage(50)
                .range(tiles(5.0))
                .targetType(TargetType.ALL)
                .targetOnlyTroops(true)
                .build())
        .deployTime(0f)
        .deployTimer(0f)
        .attached(attached)
        .invulnerable(true)
        .build();
  }

  @Test
  void attachedUnit_isNotTargetable() {
    Troop parent = createParent(Team.BLUE, 5f, 5f);
    Troop child = createAttachedChild(parent, "RamRider", 0f, 0f);

    gameState.spawnEntity(parent);
    gameState.spawnEntity(child);
    gameState.processPending();

    assertThat(parent.isTargetable()).isTrue();
    assertThat(child.isTargetable()).isFalse();
    assertThat(child.isAttached()).isTrue();
  }

  @Test
  void attachedUnit_positionSyncsWithParent() {
    Troop parent = createParent(Team.BLUE, 5f, 5f);
    Troop child = createAttachedChild(parent, "RamRider", 0f, 0.5f);

    gameState.spawnEntity(parent);
    gameState.spawnEntity(child);
    gameState.processPending();

    // Move the parent
    parent.getPosition().set(tiles(10), tiles(15));
    parent.getPosition().setRotation(0f); // facing right

    attachedUnitSystem.update(1f / 30f);

    // Child should follow parent (offset 0.5 in Y when rotation=0 means +0.5 in Y)
    assertThat(child.getPosition().getX()).isCloseTo(tiles(10), within(tiles(0.01)));
    assertThat(child.getPosition().getY()).isCloseTo(tiles(15.5), within(tiles(0.01)));
  }

  @Test
  void attachedUnit_diesWhenParentDies() {
    Troop parent = createParent(Team.BLUE, 5f, 5f);
    Troop child = createAttachedChild(parent, "RamRider", 0f, 0f);

    gameState.spawnEntity(parent);
    gameState.spawnEntity(child);
    gameState.processPending();

    // Kill the parent
    parent.getHealth().takeDamage(parent.getHealth().getMax());
    assertThat(parent.isAlive()).isFalse();

    // Run the attached unit system
    attachedUnitSystem.update(1f / 30f);

    // Child should also be dead
    assertThat(child.isAlive()).isFalse();
  }

  @Test
  void attachedUnit_inheritsStunFromParent() {
    Troop parent = createParent(Team.BLUE, 5f, 5f);
    Troop child = createAttachedChild(parent, "RamRider", 0f, 0f);

    gameState.spawnEntity(parent);
    gameState.spawnEntity(child);
    gameState.processPending();

    // Stun the parent (simulate StatusEffectSystem applying stun)
    parent.getMovement().setMovementDisabled(ModifierSource.STATUS_EFFECT, true);
    parent.getCombat().setCombatDisabled(ModifierSource.STATUS_EFFECT, true);

    attachedUnitSystem.update(1f / 30f);

    // Child should also be stunned
    assertThat(child.getMovement().isMovementDisabled()).isTrue();
    assertThat(child.getCombat().isCombatDisabled()).isTrue();
  }

  @Test
  void attachedUnit_inheritsSlowFromParent() {
    Troop parent = createParent(Team.BLUE, 5f, 5f);
    Troop child = createAttachedChild(parent, "RamRider", 0f, 0f);

    gameState.spawnEntity(parent);
    gameState.spawnEntity(child);
    gameState.processPending();

    // Apply slow to parent
    parent.getMovement().setSpeedMultiplier(ModifierSource.STATUS_EFFECT, 0.5f);
    parent.getCombat().setAttackSpeedMultiplier(ModifierSource.STATUS_EFFECT, 0.5f);

    attachedUnitSystem.update(1f / 30f);

    // Child should also be slowed
    assertThat(child.getMovement().getSpeedMultiplier()).isCloseTo(0.5f, within(0.01f));
    assertThat(child.getCombat().getAttackSpeedMultiplier()).isCloseTo(0.5f, within(0.01f));
  }

  @Test
  void attachedUnit_skipsPhysicsCollision() {
    Troop parent = createParent(Team.BLUE, 5f, 5f);
    Troop child = createAttachedChild(parent, "RamRider", 0f, 0f);

    // Attached unit should not be in the collidable set since isTargetable() returns false
    gameState.spawnEntity(parent);
    gameState.spawnEntity(child);
    gameState.processPending();

    assertThat(child.isTargetable()).isFalse();
  }

  @Test
  void deployRamRider_spawnsBothParentAndRider() {
    // Integration test: deploy a Ram Rider card through DeploymentSystem
    DefaultCombatAbilityBridge abilityBridge = new DefaultCombatAbilityBridge();
    AoeDamageService aoeDamageService = new AoeDamageService(gameState, abilityBridge);
    ProjectileSystem projectileSystem =
        new ProjectileSystem(gameState, aoeDamageService, abilityBridge);
    CombatSystem combatSystem =
        new CombatSystem(gameState, aoeDamageService, projectileSystem, abilityBridge);
    DeploymentSystem deploymentSystem =
        new DeploymentSystem(
            gameState, new AoeDamageService(gameState, new DefaultCombatAbilityBridge()));

    TroopStats riderStats =
        TroopStats.builder()
            .name("RamRider")
            .health(500)
            .damage(50)
            .speed(tiles(1.0))
            .range(tiles(5.0))
            .movementType(MovementType.GROUND)
            .targetType(TargetType.ALL)
            .targetOnlyTroops(true)
            .ignoreTargetsWithBuff("BolaSnare")
            .build();

    TroopStats ramStats =
        TroopStats.builder()
            .name("Ram")
            .health(1000)
            .damage(100)
            .speed(tiles(1.0))
            .range(tiles(1.2))
            .movementType(MovementType.GROUND)
            .targetType(TargetType.GROUND)
            .liveSpawn(new LiveSpawnConfig("RamRider", 1, 0f, 0f, 0f, 0, true))
            .build();

    Card ramRiderCard =
        Card.builder()
            .id("ramrider")
            .name("RamRider")
            .type(CardType.TROOP)
            .cost(5)
            .rarity(Rarity.LEGENDARY)
            .unitStats(ramStats)
            .unitCount(1)
            .spawnTemplate(riderStats)
            .build();

    List<Card> cards = new ArrayList<>(Collections.nCopies(8, ramRiderCard));
    Player player = new Player(Team.BLUE, new Deck(cards), false);
    player.getElixir().update(100f);

    PlayerActionDTO action =
        PlayerActionDTO.builder().handIndex(0).x(tiles(9)).y(tiles(10)).build();
    deploymentSystem.queueAction(player, action);
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);
    gameState.processPending();

    // Should have 2 entities: the Ram and the RamRider
    List<Troop> troops =
        gameState.getEntities().stream()
            .filter(e -> e instanceof Troop)
            .map(e -> (Troop) e)
            .toList();
    assertThat(troops).hasSize(2);

    Troop ram = troops.stream().filter(t -> "Ram".equals(t.getName())).findFirst().orElse(null);
    Troop rider =
        troops.stream().filter(t -> "RamRider".equals(t.getName())).findFirst().orElse(null);

    assertThat(ram).isNotNull();
    assertThat(rider).isNotNull();

    // Deploying troops are targetable; rider (attached) is not
    assertThat(ram.isTargetable()).isTrue();
    ram.setDeployTimer(0); // Finish deploying
    assertThat(ram.isTargetable()).isTrue();
    assertThat(rider.isTargetable()).isFalse();
    assertThat(rider.isAttached()).isTrue();
    assertThat(rider.isInvulnerable()).isTrue();

    // Rider should have no spawner component on the Ram (spawnAttach skips SpawnerComponent)
    assertThat(ram.getSpawner()).isNull();

    // Rider's combat should have targetOnlyTroops
    assertThat(rider.getCombat().isTargetOnlyTroops()).isTrue();
    assertThat(rider.getCombat().getIgnoreTargetsWithBuff()).isEqualTo("BolaSnare");
  }

  @Test
  void deployGoblinGiant_spawnsTwoAttachedSpearGoblins() {
    // GoblinGiant uses spawnAttach with 2 SpearGoblinGiant units
    DefaultCombatAbilityBridge abilityBridge2 = new DefaultCombatAbilityBridge();
    AoeDamageService aoeDamageService2 = new AoeDamageService(gameState, abilityBridge2);
    ProjectileSystem projectileSystem2 =
        new ProjectileSystem(gameState, aoeDamageService2, abilityBridge2);
    CombatSystem combatSystem =
        new CombatSystem(gameState, aoeDamageService2, projectileSystem2, abilityBridge2);
    DeploymentSystem deploymentSystem =
        new DeploymentSystem(
            gameState, new AoeDamageService(gameState, new DefaultCombatAbilityBridge()));

    TroopStats spearGoblinStats =
        TroopStats.builder()
            .name("SpearGoblinGiant")
            .health(100)
            .damage(30)
            .speed(tiles(1.0))
            .range(tiles(4.0))
            .movementType(MovementType.GROUND)
            .targetType(TargetType.ALL)
            .build();

    TroopStats goblinGiantStats =
        TroopStats.builder()
            .name("GoblinGiant")
            .health(2000)
            .damage(120)
            .speed(tiles(0.75))
            .range(tiles(1.2))
            .movementType(MovementType.GROUND)
            .targetType(TargetType.GROUND)
            .liveSpawn(new LiveSpawnConfig("SpearGoblinGiant", 2, 0f, 0f, 0f, tiles(1.0), true))
            .build();

    Card goblinGiantCard =
        Card.builder()
            .id("goblingiant")
            .name("GoblinGiant")
            .type(CardType.TROOP)
            .cost(6)
            .rarity(Rarity.EPIC)
            .unitStats(goblinGiantStats)
            .unitCount(1)
            .spawnTemplate(spearGoblinStats)
            .build();

    List<Card> cards = new ArrayList<>(Collections.nCopies(8, goblinGiantCard));
    Player player = new Player(Team.BLUE, new Deck(cards), false);
    player.getElixir().update(100f);

    PlayerActionDTO action =
        PlayerActionDTO.builder().handIndex(0).x(tiles(9)).y(tiles(10)).build();
    deploymentSystem.queueAction(player, action);
    deploymentSystem.update(DeploymentSystem.PLACEMENT_SYNC_DELAY);
    gameState.processPending();

    List<Troop> troops =
        gameState.getEntities().stream()
            .filter(e -> e instanceof Troop)
            .map(e -> (Troop) e)
            .toList();

    // 1 GoblinGiant + 2 SpearGoblinGiant = 3
    assertThat(troops).hasSize(3);

    long attachedCount = troops.stream().filter(Troop::isAttached).count();
    assertThat(attachedCount).isEqualTo(2);

    // The two attached units should be at different positions (formation offsets)
    List<Troop> attached = troops.stream().filter(Troop::isAttached).toList();
    assertThat(attached).hasSize(2);

    // With 2 units and spawnRadius=1.0, they should be offset from each other
    int x0 = attached.get(0).getPosition().getX();
    int x1 = attached.get(1).getPosition().getX();
    int y0 = attached.get(0).getPosition().getY();
    int y1 = attached.get(1).getPosition().getY();
    // They should not be at the same position (more than 0.01 tiles apart on some axis)
    boolean differentPositions = Math.abs(x0 - x1) > tiles(0.01) || Math.abs(y0 - y1) > tiles(0.01);
    assertThat(differentPositions).isTrue();
  }

  @Test
  void attachedUnit_positionSyncsWithRotation() {
    Troop parent = createParent(Team.BLUE, 5f, 5f);
    Troop child = createAttachedChild(parent, "RamRider", 1f, 0f);

    gameState.spawnEntity(parent);
    gameState.spawnEntity(child);
    gameState.processPending();

    // Rotate parent 90 degrees (pi/2)
    parent.getPosition().setRotation((float) (Math.PI / 2));

    attachedUnitSystem.update(1f / 30f);

    // Offset (1, 0) rotated 90 degrees -> (0, 1)
    assertThat(child.getPosition().getX()).isCloseTo(tiles(5), within(tiles(0.01)));
    assertThat(child.getPosition().getY()).isCloseTo(tiles(6), within(tiles(0.01)));
  }
}
