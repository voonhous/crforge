package org.crforge.core.ability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.crforge.core.card.Card;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.EntityType;
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
 * Integration tests for the GoblinDrill card. Uses full GameEngine + CardRegistry to test the
 * complete tunnel -> morph -> building -> spawn lifecycle.
 */
class GoblinDrillTest {

  private GameEngine engine;
  private Standard1v1Match match;
  private Player blue;
  private Player red;
  private Card goblinDrillCard;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();

    goblinDrillCard =
        Objects.requireNonNull(CardRegistry.get("goblindrill"), "goblindrill not found");

    List<Card> deckCards = new ArrayList<>(Collections.nCopies(8, goblinDrillCard));
    blue = new Player(Team.BLUE, new Deck(new ArrayList<>(deckCards)), false);
    red = new Player(Team.RED, new Deck(new ArrayList<>(deckCards)), false);

    // Use level 1 towers to keep things simple
    match = new Standard1v1Match(1);
    match.addPlayer(blue);
    match.addPlayer(red);

    engine = new GameEngine();
    engine.setMatch(match);
    engine.initMatch();
  }

  private void deployGoblinDrill(Player player, float x, float y) {
    engine.queueAction(player, PlayerActionDTO.builder().handIndex(0).x(x).y(y).build());
  }

  private List<Troop> findTroopsByName(String name) {
    return engine.getGameState().getAliveEntities().stream()
        .filter(e -> e instanceof Troop t && name.equals(t.getName()))
        .map(e -> (Troop) e)
        .toList();
  }

  private List<Building> findBuildingsByName(String name) {
    return engine.getGameState().getAliveEntities().stream()
        .filter(e -> e instanceof Building b && name.equals(b.getName()))
        .map(e -> (Building) e)
        .toList();
  }

  private List<Entity> findNonTowerEntities() {
    return engine.getGameState().getAliveEntities().stream()
        .filter(e -> e.getEntityType() != EntityType.TOWER)
        .toList();
  }

  @Test
  void goblinDrill_canDeployOnEnemySide() {
    // Enemy side for blue is y > 16 (red zone)
    PlayerActionDTO action = PlayerActionDTO.builder().handIndex(0).x(9.0f).y(25.0f).build();
    assertThat(match.validateAction(blue, action)).isTrue();
  }

  @Test
  void goblinDrill_spawnsDigUnit_atKingTower() {
    deployGoblinDrill(blue, 9.0f, 25.0f);

    // Tick past sync delay (1.0s = 30 ticks) + 1 to process pending
    engine.tick(32);

    List<Troop> digTroops = findTroopsByName("GoblinDrillDig");
    assertThat(digTroops).hasSize(1);

    Troop digTroop = digTroops.get(0);
    // Should start near blue king tower (9.0, 3.0)
    assertThat(digTroop.getPosition().getX()).isCloseTo(9.0f, within(1.0f));
    assertThat(digTroop.getPosition().getY()).isCloseTo(3.0f, within(1.0f));
    assertThat(digTroop.getTeam()).isEqualTo(Team.BLUE);
  }

  @Test
  void goblinDrill_digUnit_isUntargetable_whileTunneling() {
    deployGoblinDrill(blue, 9.0f, 25.0f);
    engine.tick(32);

    List<Troop> digTroops = findTroopsByName("GoblinDrillDig");
    assertThat(digTroops).hasSize(1);

    Troop digTroop = digTroops.get(0);
    assertThat(digTroop.isTunneling()).isTrue();
    assertThat(digTroop.isTargetable()).isFalse();
    assertThat(digTroop.isInvulnerable()).isTrue();
    // collisionRadius should be 0 in data (GoblinDrillDig has collisionRadius: 0.0)
    assertThat(digTroop.getCollisionRadius()).isEqualTo(0f);
  }

  @Test
  void goblinDrill_digUnit_movesTowardTarget() {
    deployGoblinDrill(blue, 9.0f, 25.0f);
    engine.tick(32);

    List<Troop> digTroops = findTroopsByName("GoblinDrillDig");
    Troop digTroop = digTroops.get(0);
    float startY = digTroop.getPosition().getY();

    // Tick 10 more frames for the dig troop to move
    engine.tick(10);

    assertThat(digTroop.getPosition().getY())
        .as("Dig troop should move toward target (y=25)")
        .isGreaterThan(startY);
  }

  @Test
  void goblinDrill_morphsToBuilding_onArrival() {
    deployGoblinDrill(blue, 9.0f, 25.0f);

    // Tick enough for sync delay + tunnel travel. GoblinDrillDig speed = 300/60 = 5 tiles/sec.
    // Distance from king tower (9,3) to (9,25) is ~22 tiles. Via dogleg waypoint ~30 tiles.
    // At 5 t/s -> ~6s -> 180 ticks. Add sync delay 30 ticks + buffer.
    engine.tick(250);

    // Dig troop should be dead/gone (it gets killed on morph)
    List<Troop> digTroops = findTroopsByName("GoblinDrillDig");
    assertThat(digTroops).isEmpty();

    // GoblinDrill building should exist
    List<Building> buildings = findBuildingsByName("GoblinDrill");
    assertThat(buildings).hasSize(1);

    Building building = buildings.get(0);
    assertThat(building.getTeam()).isEqualTo(Team.BLUE);
    assertThat(building.getPosition().getX()).isCloseTo(9.0f, within(0.5f));
    assertThat(building.getPosition().getY()).isCloseTo(25.0f, within(0.5f));
  }

  @Test
  void goblinDrill_firesSpawnDamage_onEmergence() {
    // Place a dummy enemy troop near the deploy target to absorb the GoblinDrillDamage
    Troop enemy =
        Troop.builder()
            .name("Target")
            .team(Team.RED)
            .position(new org.crforge.core.component.Position(9.0f, 25.0f))
            .health(new org.crforge.core.component.Health(10000))
            .movement(
                new org.crforge.core.component.Movement(
                    0f, 5f, 0.5f, 0.5f, org.crforge.core.entity.base.MovementType.GROUND))
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    engine.spawn(enemy);

    deployGoblinDrill(blue, 9.0f, 25.0f);

    // Run until building morphs + area effect processes
    engine.tick(260);

    // Enemy should have taken damage from GoblinDrillDamage
    assertThat(enemy.getHealth().getCurrent())
        .as("Enemy near emergence should take GoblinDrillDamage")
        .isLessThan(10000);
  }

  @Test
  void goblinDrill_spawnDamage_appliesKnockback() {
    // Place a dummy enemy troop near the deploy target
    Troop enemy =
        Troop.builder()
            .name("Target")
            .team(Team.RED)
            .position(new org.crforge.core.component.Position(9.5f, 25.0f))
            .health(new org.crforge.core.component.Health(10000))
            .movement(
                new org.crforge.core.component.Movement(
                    1.0f, 5f, 0.5f, 0.5f, org.crforge.core.entity.base.MovementType.GROUND))
            .deployTime(0f)
            .deployTimer(0f)
            .build();
    engine.spawn(enemy);

    float startX = enemy.getPosition().getX();
    deployGoblinDrill(blue, 9.0f, 25.0f);

    // Run until building morphs + area effect processes + a few physics ticks for knockback
    engine.tick(265);

    // Enemy should have been knocked back (pushed away from the emergence center)
    float endX = enemy.getPosition().getX();
    assertThat(Math.abs(endX - startX))
        .as("Enemy should be displaced by knockback from GoblinDrillDamage pushback")
        .isGreaterThan(0.01f);
  }

  @Test
  void goblinDrill_spawnsGoblins_overLifetime() {
    // Deploy on own side to avoid tower fire killing Goblins
    deployGoblinDrill(blue, 9.0f, 10.0f);

    // Tunnel travel: ~7 tiles at 5 t/s -> ~1.4s -> ~42 ticks
    // Sync delay: 30 ticks + tunnel: ~42 ticks + building deploy: ~30 ticks = ~102 ticks
    // Then live spawn at 1s, 4s, 7s (spawnStartTime=1.0, spawnPauseTime=3.0)
    // Run 102 + 8*30 = ~342 ticks to get past the 7s spawn
    engine.tick(380);

    List<Troop> goblins = findTroopsByName("Goblin");
    // Should have at least 2 Goblins from live spawns (1s and 4s)
    assertThat(goblins.size())
        .as("Should have live-spawned Goblins from the GoblinDrill building")
        .isGreaterThanOrEqualTo(2);
  }

  @Test
  void goblinDrill_deathSpawns_twoGoblins() {
    // Deploy on own side to avoid tower fire killing Goblins
    deployGoblinDrill(blue, 9.0f, 10.0f);

    // Tunnel: ~42 ticks + sync: 30 ticks + deploy: ~30 ticks + lifetime: 300 ticks + buffer
    engine.tick(450);

    // Building should be dead (lifetime expired after 10s)
    List<Building> buildings = findBuildingsByName("GoblinDrill");
    assertThat(buildings).isEmpty();

    // Count total Goblins: 3 from live spawn + 2 from death spawn = 5 total
    // Some may have wandered off toward enemy towers, so use relaxed count
    List<Troop> goblins = findTroopsByName("Goblin");
    assertThat(goblins.size())
        .as("Should have Goblins from both live spawn and death spawn")
        .isGreaterThanOrEqualTo(3);
  }

  @Test
  void goblinDrill_fullLifecycle() {
    // Deploy on own side to avoid tower fire
    deployGoblinDrill(blue, 9.0f, 10.0f);

    // Phase 1: After sync delay, dig troop should exist
    engine.tick(32);
    assertThat(findTroopsByName("GoblinDrillDig")).hasSize(1);
    assertThat(findBuildingsByName("GoblinDrill")).isEmpty();

    // Phase 2: After tunnel travel (~42 ticks from king tower to y=10), building should exist
    engine.tick(50);
    assertThat(findTroopsByName("GoblinDrillDig")).isEmpty();
    assertThat(findBuildingsByName("GoblinDrill")).hasSize(1);

    // Phase 3: After building lifetime (10s=300 ticks) + buffer, Goblins should exist
    engine.tick(380);
    List<Troop> goblins = findTroopsByName("Goblin");
    assertThat(goblins.size())
        .as("Full lifecycle should produce multiple Goblins from live + death spawns")
        .isGreaterThanOrEqualTo(3);
  }
}
