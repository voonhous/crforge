package org.crforge.core.pathfinding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.util.GameUnits.tiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import org.crforge.core.card.Card;
import org.crforge.core.engine.GameEngine;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.match.PathfindingMode;
import org.crforge.core.match.Standard1v1Match;
import org.crforge.core.player.Deck;
import org.crforge.core.player.LevelConfig;
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.crforge.core.testing.SimHarness;
import org.crforge.core.testing.SimSystems;
import org.crforge.core.testing.TroopTemplate;
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The pathfinding mode switch: what each mode wires and what it leaves alone. */
class GridPathfindingModeTest {

  private static final int LEVEL = 11;

  /** The Knight's speed column, in game units per tick. */
  private static final int KNIGHT_RAW_SPEED = 60;

  @Test
  @DisplayName("a match says nothing and gets the waypoint rules")
  void defaultModeIsWaypoints() {
    assertThat(new Standard1v1Match().getPathfindingMode()).isEqualTo(PathfindingMode.WAYPOINTS);
    assertThat(new Standard1v1Match(LEVEL).getPathfindingMode())
        .isEqualTo(PathfindingMode.WAYPOINTS);
  }

  @Test
  @DisplayName("a waypoint match has no grid system and its troops carry no grid state")
  void waypointMatchHasNoGridSystem() {
    GameEngine engine = engineFor(new Standard1v1Match(LEVEL));

    assertThat(engine.getGridPathfindingSystem()).isNull();

    Troop knight = spawnKnight(engine);
    engine.tick(30);

    assertThat(knight.getGridUnitState()).isNull();
    assertThat(knight.getPosition().getY()).isGreaterThan(tiles(10));
  }

  @Test
  @DisplayName("a grid match attaches grid state to a ground troop and moves it with it")
  void gridMatchManagesGroundTroops() {
    GameEngine engine = engineFor(new Standard1v1Match(LEVEL, PathfindingMode.GRID));

    assertThat(engine.getGridPathfindingSystem()).isNotNull();

    Troop knight = spawnKnight(engine);
    engine.tick(30);

    assertThat(knight.getGridUnitState()).isNotNull();
    assertThat(engine.getGridPathfindingSystem().manages(knight)).isTrue();
    assertThat(knight.getGridUnitState().movement().getRoute().size()).isGreaterThan(0);
    assertThat(knight.getPosition().getY()).isGreaterThan(tiles(10));

    // The previous-position copy is refreshed at the head of every tick, so after one more tick it
    // holds where the troop stood at the end of the tick before it.
    int wasX = knight.getPosition().getX();
    int wasY = knight.getPosition().getY();
    engine.tick();
    GridEntity view = knight.getGridUnitState().entity();
    assertThat(view.getPrevX()).isEqualTo(wasX);
    assertThat(view.getPrevY()).isEqualTo(wasY);
    assertThat(view.getY()).isGreaterThan(wasY);
  }

  @Test
  @DisplayName("a spawned troop carries its speed column, which is what the grid budget reads")
  void aSpawnedTroopCarriesItsSpeedColumn() {
    GameEngine engine = engineFor(new Standard1v1Match(LEVEL, PathfindingMode.GRID));
    Troop knight = spawnKnight(engine);

    assertThat(knight.getMovement().getRawSpeed()).isEqualTo(KNIGHT_RAW_SPEED);
    assertThat(knight.getGridUnitState().speedConfig().speed()).isEqualTo(KNIGHT_RAW_SPEED);
  }

  @Test
  @DisplayName("an air troop keeps the waypoint rules inside a grid match")
  void gridMatchLeavesAirTroopsAlone() {
    GameEngine engine = engineFor(new Standard1v1Match(LEVEL, PathfindingMode.GRID));
    Card minions = Objects.requireNonNull(CardRegistry.get("minions"), "minions not found");
    engine
        .getSpawnerSystem()
        .spawnUnit(tiles(9), tiles(10), Team.BLUE, minions.getUnitStats(), LEVEL, 0f);
    engine.tick(30);

    Troop minion = firstTroop(engine);
    assertThat(minion).isNotNull();
    assertThat(engine.getGridPathfindingSystem().manages(minion)).isFalse();
    assertThat(minion.getGridUnitState()).isNull();
  }

  @Test
  @DisplayName("the harness wires the grid system the same way the engine does")
  void harnessWiresTheGridSystem() {
    SimHarness plain =
        SimHarness.create()
            .withSystems(SimSystems.PHYSICS, SimSystems.TARGETING)
            .spawn(TroopTemplate.melee("Knight", Team.BLUE).at(5, 5))
            .deployed()
            .build();
    assertThat(plain.gridPathfindingSystem()).isNull();

    SimHarness grid =
        SimHarness.create()
            .withSystems(SimSystems.PHYSICS, SimSystems.TARGETING)
            .withGridPathfinding()
            .spawn(TroopTemplate.melee("Knight", Team.BLUE).at(5, 5))
            .deployed()
            .build();
    assertThat(grid.gridPathfindingSystem()).isNotNull();

    grid.tick(5);

    assertThat(grid.troop("Knight").getGridUnitState()).isNotNull();
  }

  @Test
  @DisplayName("a building-only attacker walks past an enemy troop without ever taking it")
  void aBuildingOnlyAttackerNeverTakesATroop() {
    GameEngine engine = gridEngineHolding("giant");
    Troop giantTroop = playFromHand(engine, "giant", tiles(4), tiles(10));

    assertThat(giantTroop.getGridUnitState().targeting().getConfig().targetOnlyBuildings())
        .as("the Giant's building-only column reached the grid")
        .isTrue();

    Card musketeer = Objects.requireNonNull(CardRegistry.get("musketeer"), "musketeer not found");
    engine
        .getSpawnerSystem()
        .spawnUnit(tiles(4), tiles(13), Team.RED, musketeer.getUnitStats(), LEVEL, 0f);

    for (int tick = 0; tick < 120; tick++) {
      engine.tick();
      assertThat(giantTroop.getCombat().getCurrentTarget())
          .as("tick %d: the Giant took a troop as its reference", tick)
          .matches(target -> !(target instanceof Troop));
    }
  }

  @Test
  @DisplayName("a multi-target unit's target count reaches its grid targeting columns")
  void aMultiTargetUnitCarriesItsTargetCount() {
    GameEngine engine = gridEngineHolding("electrowizard");
    Troop unit = playFromHand(engine, "electrowizard", tiles(4), tiles(10));

    assertThat(unit.getCombat().getMultipleTargets()).isEqualTo(2);
    assertThat(unit.getGridUnitState().targeting().getConfig().multipleTargets()).isEqualTo(2);
  }

  /**
   * A grid match whose blue player holds the given card in its opening hand. The hand is drawn from
   * a shuffled deck, so the seed is searched rather than assumed.
   */
  private static GameEngine gridEngineHolding(String cardId) {
    AbstractEntity.resetIdCounter();
    for (int seed = 0; seed < 1000; seed++) {
      Player blue = playerWithDeck(Team.BLUE, cardId, seed);
      if (handSlot(blue, cardId) < 0) {
        continue;
      }
      Standard1v1Match match = new Standard1v1Match(LEVEL, PathfindingMode.GRID);
      match.addPlayer(blue);
      match.addPlayer(playerWithDeck(Team.RED, cardId, seed));
      GameEngine engine = new GameEngine();
      engine.setMatch(match);
      engine.initMatch();
      return engine;
    }
    throw new IllegalStateException("no shuffle put " + cardId + " in the opening hand");
  }

  /** A player whose deck holds the given card plus seven others, shuffled with the given seed. */
  private static Player playerWithDeck(Team team, String cardId, int seed) {
    List<Card> cards = new ArrayList<>();
    cards.add(Objects.requireNonNull(CardRegistry.get(cardId), cardId + " not found"));
    for (String id : List.of("knight", "musketeer", "archer", "goblins", "valkyrie", "bomber")) {
      cards.add(Objects.requireNonNull(CardRegistry.get(id), id + " not found"));
    }
    cards.add(Objects.requireNonNull(CardRegistry.get("minions"), "minions not found"));
    return new Player(team, new Deck(cards), false, new LevelConfig(LEVEL), new Random(seed));
  }

  /** The slot the card sits in, or -1 when the hand does not hold it. */
  private static int handSlot(Player player, String cardId) {
    for (int slot = 0; slot < 4; slot++) {
      Card card = player.getHand().getCard(slot);
      if (card != null && cardId.equals(card.getId())) {
        return slot;
      }
    }
    return -1;
  }

  /** Plays the card from the blue player's hand and runs on until its troop is grid-driven. */
  private static Troop playFromHand(GameEngine engine, String cardId, int x, int y) {
    Player blue = engine.getMatch().getPlayers(Team.BLUE).get(0);
    blue.getElixir().add(10);
    engine.queueAction(blue, PlayerActionDTO.play(handSlot(blue, cardId), x, y));
    for (int tick = 0; tick < 40; tick++) {
      engine.tick();
      for (Entity entity : engine.getGameState().getAliveEntities()) {
        if (entity instanceof Troop troop && troop.getGridUnitState() != null) {
          return troop;
        }
      }
    }
    throw new IllegalStateException(cardId + " never reached the arena");
  }

  private static GameEngine engineFor(Standard1v1Match match) {
    AbstractEntity.resetIdCounter();
    List<Card> deckCards = new ArrayList<>();
    for (String id :
        List.of(
            "knight", "giant", "musketeer", "archer", "goblins", "valkyrie", "bomber", "minions")) {
      deckCards.add(Objects.requireNonNull(CardRegistry.get(id), id + " not found"));
    }
    match.addPlayer(new Player(Team.BLUE, new Deck(deckCards), false, new LevelConfig(LEVEL)));
    match.addPlayer(
        new Player(Team.RED, new Deck(new ArrayList<>(deckCards)), false, new LevelConfig(LEVEL)));
    GameEngine engine = new GameEngine();
    engine.setMatch(match);
    engine.initMatch();
    return engine;
  }

  private static Troop spawnKnight(GameEngine engine) {
    Card knight = Objects.requireNonNull(CardRegistry.get("knight"), "knight not found");
    engine
        .getSpawnerSystem()
        .spawnUnit(
            tiles(4),
            tiles(10),
            Team.BLUE,
            knight.getUnitStats(),
            LEVEL,
            knight.getUnitStats().getDeployTime());
    engine.tick();
    return firstTroop(engine);
  }

  private static Troop firstTroop(GameEngine engine) {
    for (Entity entity : engine.getGameState().getAliveEntities()) {
      if (entity instanceof Troop troop) {
        return troop;
      }
    }
    return null;
  }
}
