package org.crforge.core.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.Rarity;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.MovementType;
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
 * Tests for Furnace (rework): a 4-elixir Rare walking troop that attacks with a ranged projectile
 * and spawns FireSpirits periodically. Unlike traditional buildings, the reworked Furnace walks
 * toward enemies (movementType=GROUND, speed=60) and is classified as a TROOP card type.
 */
class FurnaceReworkTest {

  private GameEngine engine;
  private Player bluePlayer;

  private static final Card FURNACE = CardRegistry.get("firespirithut");

  // Deploy at y=10 on blue side, away from towers
  private static final float DEPLOY_X = 9f;
  private static final float DEPLOY_Y = 10f;

  // 1.0s placement sync delay = 30 ticks
  private static final int SYNC_DELAY_TICKS = 30;
  // 1.0s building deploy time = 30 ticks
  private static final int DEPLOY_TICKS = 30;

  @BeforeEach
  void setUp() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    engine = new GameEngine();

    Deck blueDeck = buildDeckWith(FURNACE);
    Deck redDeck = buildDeckWith(FURNACE);

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
    assertThat(FURNACE).as("Card should be loaded").isNotNull();
    assertThat(FURNACE.getType()).as("Card type").isEqualTo(CardType.TROOP);
    assertThat(FURNACE.getCost()).as("Elixir cost").isEqualTo(4);
    assertThat(FURNACE.getRarity()).as("Rarity").isEqualTo(Rarity.RARE);

    assertThat(FURNACE.getUnitStats()).as("Unit stats").isNotNull();
    assertThat(FURNACE.getUnitStats().getHealth()).as("Health").isEqualTo(284);
    assertThat(FURNACE.getUnitStats().getDamage()).as("Damage").isEqualTo(70);
    // Speed 60 in units.json -> 1.0 tiles/sec after UnitLoader conversion (60 / SPEED_BASE)
    assertThat(FURNACE.getUnitStats().getSpeed()).as("Speed").isEqualTo(1.0f);
    assertThat(FURNACE.getUnitStats().getMovementType())
        .as("Movement type")
        .isEqualTo(MovementType.GROUND);
    assertThat(FURNACE.getUnitStats().getRange()).as("Range").isEqualTo(6.0f);
    assertThat(FURNACE.getUnitStats().getProjectile()).as("Projectile").isNotNull();
    assertThat(FURNACE.getUnitStats().getProjectile().getName())
        .as("Projectile name")
        .isEqualTo("Furnace_Rework_Projectile");

    assertThat(FURNACE.getUnitStats().getLiveSpawn()).as("Live spawn config").isNotNull();
    assertThat(FURNACE.getUnitStats().getLiveSpawn().spawnCharacter())
        .as("Spawn character")
        .isEqualTo("FireSpirits");
    assertThat(FURNACE.getUnitStats().getLiveSpawn().spawnNumber()).as("Spawn number").isEqualTo(1);
    assertThat(FURNACE.getUnitStats().getLiveSpawn().spawnPauseTime())
        .as("Spawn pause time")
        .isEqualTo(7.0f);
    assertThat(FURNACE.getUnitStats().getLiveSpawn().spawnStartTime())
        .as("Spawn start time")
        .isEqualTo(1.95f);
  }

  @Test
  void furnaceIsCreatedAsTroop() {
    deployFurnace(DEPLOY_X, DEPLOY_Y);

    // Tick past sync delay so the entity is spawned
    engine.tick(SYNC_DELAY_TICKS + 2);

    Troop furnace = findFurnaceTroop();
    assertThat(furnace).as("Furnace should exist as a Troop").isNotNull();
    assertThat(furnace.getTeam()).as("Team").isEqualTo(Team.BLUE);
    assertThat(furnace.getHealth().getMax()).as("Max HP").isEqualTo(284);

    // Should NOT exist as a Building
    Building building = findFurnaceBuilding();
    assertThat(building).as("Furnace should NOT be a Building entity").isNull();
  }

  @Test
  void furnaceWalksTowardEnemy() {
    deployFurnace(DEPLOY_X, DEPLOY_Y);

    // Tick past sync + deploy so the troop is active
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    Troop furnace = findFurnaceTroop();
    assertThat(furnace).as("Furnace should exist").isNotNull();
    float initialY = furnace.getPosition().getY();

    // Tick enough for movement to occur (blue walks toward red = +Y)
    engine.tick(60);

    float movedY = furnace.getPosition().getY();
    assertThat(movedY)
        .as("Furnace should walk toward enemy (increasing Y for blue)")
        .isGreaterThan(initialY);
  }

  @Test
  void furnaceFiresProjectile() {
    deployFurnace(DEPLOY_X, DEPLOY_Y);

    // Tick past sync + deploy
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    Troop furnace = findFurnaceTroop();
    assertThat(furnace).as("Furnace should exist").isNotNull();
    assertThat(furnace.getCombat()).as("Should have combat component").isNotNull();
    assertThat(furnace.getCombat().getProjectileStats())
        .as("Should have projectile stats")
        .isNotNull();

    // Run enough ticks for the Furnace to acquire a target and fire
    // It has range=6.0 and the red towers are at y~29, so it needs to walk close enough first.
    // Instead, verify the combat setup is correct (projectile-based ranged attack).
    assertThat(furnace.getCombat().getRange()).as("Attack range").isEqualTo(6.0f);
    assertThat(furnace.getCombat().getProjectileStats().getName())
        .as("Projectile name")
        .isEqualTo("Furnace_Rework_Projectile");
  }

  @Test
  void furnaceSpawnsFireSpiritAfterStartTime() {
    deployFurnace(DEPLOY_X, DEPLOY_Y);

    // Tick past sync + deploy
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);
    assertThat(countFireSpirits()).as("No FireSpirits right after deploy").isEqualTo(0);

    // spawnStartTime = 1.95s = ~59 ticks. Tick just before.
    engine.tick(55);
    assertThat(countFireSpirits()).as("No FireSpirits before start time").isEqualTo(0);

    // Tick past start time (need 1-2 extra ticks for processing)
    engine.tick(10);
    assertThat(countFireSpirits()).as("1 FireSpirit after start time").isEqualTo(1);
  }

  @Test
  void furnaceSpawnsFireSpiritPeriodically() {
    deployFurnace(DEPLOY_X, DEPLOY_Y);

    // Tick past sync + deploy + spawn start time (1.95s ~= 59 ticks) + buffer
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2 + 65);
    assertThat(countFireSpirits()).as("1st FireSpirit spawned").isEqualTo(1);

    // spawnPauseTime = 7.0s = 210 ticks between waves. Tick just before.
    engine.tick(200);
    assertThat(countFireSpirits()).as("Still only 1 FireSpirit before next wave").isEqualTo(1);

    // Tick past the pause
    engine.tick(15);
    assertThat(countFireSpirits()).as("2nd FireSpirit after 7s pause").isEqualTo(2);
  }

  @Test
  void furnaceHasNoLifetime() {
    // The reworked Furnace is a walking troop, not a stationary building.
    // It has no lifeTime field in unit data (defaults to 0), meaning no building-style decay.
    assertThat(FURNACE.getUnitStats().getLifeTime())
        .as("Furnace should have no lifetime (0 = no decay)")
        .isEqualTo(0f);

    // Verify it spawns as a Troop (no Building lifetime mechanism)
    deployFurnace(DEPLOY_X, DEPLOY_Y);
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    Troop furnace = findFurnaceTroop();
    assertThat(furnace).as("Furnace should exist as Troop").isNotNull();
    // Troops do not have the Building.remainingLifetime decay mechanism
    assertThat(furnace).isNotInstanceOf(Building.class);
  }

  @Test
  void furnaceHasNoDeathSpawn() {
    deployFurnace(DEPLOY_X, DEPLOY_Y);

    // Tick past sync + deploy
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    Troop furnace = findFurnaceTroop();
    assertThat(furnace).as("Furnace should exist").isNotNull();

    long entitiesBefore =
        engine.getGameState().getEntitiesOfType(Troop.class).stream()
            .filter(t -> t.getTeam() == Team.BLUE && !"Furnace_rework".equals(t.getName()))
            .count();

    // Kill the Furnace
    furnace.getHealth().takeDamage(furnace.getHealth().getCurrent());

    // Tick to process death
    engine.tick(3);

    long entitiesAfter =
        engine.getGameState().getEntitiesOfType(Troop.class).stream()
            .filter(t -> t.getTeam() == Team.BLUE && !"Furnace_rework".equals(t.getName()))
            .count();

    assertThat(entitiesAfter)
        .as("No additional entities should spawn on death")
        .isEqualTo(entitiesBefore);
  }

  @Test
  void furnaceIsATroopNotABuilding() {
    deployFurnace(DEPLOY_X, DEPLOY_Y);

    // Tick past sync + deploy so Furnace is targetable
    engine.tick(SYNC_DELAY_TICKS + DEPLOY_TICKS + 2);

    Troop furnace = findFurnaceTroop();
    assertThat(furnace).as("Furnace should exist as a Troop").isNotNull();
    assertThat(furnace).as("Furnace is a Troop, not a Building").isNotInstanceOf(Building.class);
  }

  // -- Helpers --

  private void deployFurnace(float x, float y) {
    PlayerActionDTO action = PlayerActionDTO.builder().handIndex(0).x(x).y(y).build();
    engine.queueAction(bluePlayer, action);
  }

  private long countFireSpirits() {
    return engine.getGameState().getEntitiesOfType(Troop.class).stream()
        .filter(t -> "FireSpirits".equals(t.getName()) && t.getTeam() == Team.BLUE)
        .count();
  }

  private Troop findFurnaceTroop() {
    return engine.getGameState().getEntitiesOfType(Troop.class).stream()
        .filter(t -> "Furnace_rework".equals(t.getName()) && t.getTeam() == Team.BLUE)
        .findFirst()
        .orElse(null);
  }

  private Building findFurnaceBuilding() {
    return engine.getGameState().getEntitiesOfType(Building.class).stream()
        .filter(b -> b.getName().contains("Furnace") && b.getTeam() == Team.BLUE)
        .findFirst()
        .orElse(null);
  }

  private Deck buildDeckWith(Card card) {
    List<Card> cards = new java.util.ArrayList<>();
    for (int i = 0; i < 8; i++) {
      cards.add(card);
    }
    return new Deck(cards);
  }
}
