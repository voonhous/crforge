package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.crforge.core.battle.Battle;
import org.crforge.core.card.Card;
import org.crforge.core.card.UnitDataMapper;
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
import org.crforge.data.card.CardRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs the same multi-unit scenes through the battle and through the older engine's grid mode and
 * requires the same positions on every tick.
 *
 * <p>Both run the same movement and targeting rules; what differs is what drives them. The golden
 * trajectories only ever hold one unit, where visiting entity by entity and visiting pass by pass
 * cannot be told apart. These scenes put several units in each other's way, so the whole-list pass
 * order, the shared spatial index and the neighbour answers all have to agree for the positions to
 * match.
 *
 * <p>The comparison stops before the older engine's combat can remove a unit: from then on the two
 * diverge for a reason that has nothing to do with movement, because hits do not land in the battle
 * yet. No scene deploys two units within reach of each other's push: the battle pushes a deploying
 * unit and the older engine does not, so such a scene diverges from the first tick.
 */
class BattleMultiUnitParityTest {

  private static final int LEVEL = 11;

  private record Placement(Team team, int x, int y) {}

  @Test
  @DisplayName("three Knights deployed apart in one lane crowd together and push identically")
  void threeKnightsInOneLane() {
    // Deployed beyond the push pass's reach of each other: the older engine does not push a
    // deploying unit, which the battle does, so the scenes compared start once they walk.
    compare(
        200,
        new Placement(Team.BLUE, 3500, 10000),
        new Placement(Team.BLUE, 3500, 11200),
        new Placement(Team.BLUE, 4700, 10000));
  }

  @Test
  @DisplayName("two Knights walking at each other meet and stop identically")
  void twoKnightsMeetHeadOn() {
    compare(100, new Placement(Team.BLUE, 3500, 12000), new Placement(Team.RED, 3500, 20000));
  }

  private void compare(int ticks, Placement... placements) {
    Card knight = Objects.requireNonNull(CardRegistry.get("knight"), "knight not found");

    // The older engine, in grid mode.
    AbstractEntity.resetIdCounter();
    List<Card> deck = new ArrayList<>();
    for (String id :
        List.of(
            "knight", "giant", "musketeer", "archer", "goblins", "valkyrie", "bomber", "minions")) {
      deck.add(Objects.requireNonNull(CardRegistry.get(id), id + " not found"));
    }
    Standard1v1Match match = new Standard1v1Match(LEVEL, PathfindingMode.GRID);
    match.addPlayer(new Player(Team.BLUE, new Deck(deck), false, new LevelConfig(LEVEL)));
    match.addPlayer(
        new Player(Team.RED, new Deck(new ArrayList<>(deck)), false, new LevelConfig(LEVEL)));
    GameEngine engine = new GameEngine();
    engine.setMatch(match);
    engine.initMatch();
    for (Placement placement : placements) {
      engine
          .getSpawnerSystem()
          .spawnUnit(
              placement.x(),
              placement.y(),
              placement.team(),
              knight.getUnitStats(),
              LEVEL,
              knight.getUnitStats().getDeployTime());
    }

    // The battle.
    Standard1v1Battle standard = new Standard1v1Battle(Standard1v1Battle.DEFAULT_LEVEL, false);
    Battle battle = standard.getBattle();
    List<CharacterEntity> units = new ArrayList<>();
    for (Placement placement : placements) {
      units.add(
          standard.deploy(
              0,
              UnitDataMapper.toUnitData(knight),
              LEVEL,
              placement.team() == Team.BLUE ? WorldEntity.SIDE_BOTTOM : WorldEntity.SIDE_TOP,
              placement.x(),
              placement.y()));
    }

    for (int tick = 0; tick < ticks; tick++) {
      engine.tick();
      battle.step();
      List<Troop> troops = knights(engine);
      assertThat(troops).as("tick %d: every Knight is still alive", tick).hasSize(units.size());
      for (int i = 0; i < units.size(); i++) {
        CharacterEntity unit = units.get(i);
        Troop troop = troops.get(i);
        assertThat(List.of(unit.getView().getX(), unit.getView().getY()))
            .as("tick %d, Knight %d position", tick, i)
            .containsExactly(troop.getPosition().getX(), troop.getPosition().getY());
        assertThat(unit.getView().getState())
            .as("tick %d, Knight %d state", tick, i)
            .isEqualTo(troop.getGridUnitState().entity().getState());
      }
    }
  }

  /** The live Knights of the older engine in creation order. */
  private static List<Troop> knights(GameEngine engine) {
    List<Troop> knights = new ArrayList<>();
    for (Entity entity : engine.getGameState().getAliveEntities()) {
      if (entity instanceof Troop troop && "Knight".equals(troop.getName())) {
        knights.add(troop);
      }
    }
    knights.sort((a, b) -> Long.compare(a.getId(), b.getId()));
    return knights;
  }
}
