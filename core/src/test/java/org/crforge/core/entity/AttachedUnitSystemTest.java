package org.crforge.core.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.LiveSpawnConfig;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.combat.CombatSystem;
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

  private Troop createParent(Team team, float x, float y) {
    return Troop.builder()
        .name("Ram")
        .team(team)
        .position(new Position(x, y))
        .health(new Health(1000))
        .movement(new Movement(1.0f, 8.0f, 0.5f, 0.5f, MovementType.GROUND))
        .combat(Combat.builder().damage(100).range(1.2f).targetType(TargetType.GROUND).build())
        .deployTime(0f)
        .deployTimer(0f)
        .build();
  }

  private Troop createAttachedChild(Troop parent, String name, float offsetX, float offsetY) {
    AttachedComponent attached = new AttachedComponent(parent, offsetX, offsetY);
    return Troop.builder()
        .name(name)
        .team(parent.getTeam())
        .position(
            new Position(
                parent.getPosition().getX() + offsetX, parent.getPosition().getY() + offsetY))
        .health(new Health(500))
        .movement(new Movement(1.0f, 4.0f, 0.3f, 0.3f, MovementType.GROUND))
        .combat(
            Combat.builder()
                .damage(50)
                .range(5.0f)
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
    parent.getPosition().set(10f, 15f);
    parent.getPosition().setRotation(0f); // facing right

    attachedUnitSystem.update(1f / 30f);

    // Child should follow parent (offset 0.5 in Y when rotation=0 means +0.5 in Y)
    assertThat(child.getPosition().getX()).isCloseTo(10f, within(0.01f));
    assertThat(child.getPosition().getY()).isCloseTo(15.5f, within(0.01f));
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
    CombatSystem combatSystem = new CombatSystem(gameState, new AoeDamageService(gameState));
    DeploymentSystem deploymentSystem =
        new DeploymentSystem(gameState, new AoeDamageService(gameState));

    TroopStats riderStats =
        TroopStats.builder()
            .name("RamRider")
            .health(500)
            .damage(50)
            .speed(1.0f)
            .range(5.0f)
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
            .speed(1.0f)
            .range(1.2f)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.GROUND)
            .liveSpawn(new LiveSpawnConfig("RamRider", 1, 0f, 0f, 0f, 0f, true))
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

    PlayerActionDTO action = PlayerActionDTO.builder().handIndex(0).x(9f).y(10f).build();
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

    // Ram should be targetable, rider should not
    assertThat(ram.isTargetable()).isFalse(); // Still deploying
    ram.update(2.0f); // Finish deploying
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
    CombatSystem combatSystem = new CombatSystem(gameState, new AoeDamageService(gameState));
    DeploymentSystem deploymentSystem =
        new DeploymentSystem(gameState, new AoeDamageService(gameState));

    TroopStats spearGoblinStats =
        TroopStats.builder()
            .name("SpearGoblinGiant")
            .health(100)
            .damage(30)
            .speed(1.0f)
            .range(4.0f)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.ALL)
            .build();

    TroopStats goblinGiantStats =
        TroopStats.builder()
            .name("GoblinGiant")
            .health(2000)
            .damage(120)
            .speed(0.75f)
            .range(1.2f)
            .movementType(MovementType.GROUND)
            .targetType(TargetType.GROUND)
            .liveSpawn(new LiveSpawnConfig("SpearGoblinGiant", 2, 0f, 0f, 0f, 1.0f, true))
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

    PlayerActionDTO action = PlayerActionDTO.builder().handIndex(0).x(9f).y(10f).build();
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
    float x0 = attached.get(0).getPosition().getX();
    float x1 = attached.get(1).getPosition().getX();
    float y0 = attached.get(0).getPosition().getY();
    float y1 = attached.get(1).getPosition().getY();
    // They should not be at exactly the same position
    boolean differentPositions = Math.abs(x0 - x1) > 0.01f || Math.abs(y0 - y1) > 0.01f;
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
    assertThat(child.getPosition().getX()).isCloseTo(5f, within(0.01f));
    assertThat(child.getPosition().getY()).isCloseTo(6f, within(0.01f));
  }
}
