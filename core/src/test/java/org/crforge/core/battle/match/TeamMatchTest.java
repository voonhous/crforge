/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.BattleRandom;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.replay.ReplayScenario;
import org.crforge.core.battle.unit.AreaEffectData;
import org.crforge.core.battle.unit.BattleWorld;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.TowerEntity;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Ladder's team match of four players, two a team, on the tables of the configured data
 * version: its towers, its players and its end. Every expected value is read from the tables.
 */
class TeamMatchTest {

  private static final List<String> DECK =
      List.of(
          "Knight",
          "Archer",
          "Giant",
          "MiniPekka",
          "Musketeer",
          "Valkyrie",
          "Barbarians",
          "Minions");

  /** The level every tower of these battles stands at. */
  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The team battle spawn group of the princess towers' selection. */
  private static String teamGroup() {
    return Shipped.text(
        Shipped.row("support_cards", ReplayScenario.TEAM_TOWER_SELECTION),
        ReplayScenario.TEAM_SPAWN_GROUP);
  }

  /** The map a recorded team battle is played on. */
  private static TileMap teamMap() {
    return GameData.tables().tileMap(ReplayScenario.TEAM_TILE_MAP);
  }

  /** A team battle whose four players' towers stand at the given king levels. */
  private static Standard1v1Battle teamBattle(int... kingLevels) {
    List<Standard1v1Battle.Towers> towers = new ArrayList<>();
    for (int side = 0; side < BattleWorld.TEAM_BATTLE; side++) {
      int king = kingLevels.length == 0 ? LEVEL : kingLevels[side];
      towers.add(new Standard1v1Battle.Towers(teamGroup(), king, LEVEL));
    }
    return new Standard1v1Battle(GameData.tables(), towers, true, teamMap());
  }

  /** A team battle played as the team match, each player of the same deck. */
  private static Standard1v1Battle teamMatch() {
    Standard1v1Battle battle = teamBattle();
    battle.startTeamMatch(
        Collections.nCopies(4, DECK),
        new int[] {0, 0, 0, 0},
        Collections.nCopies(4, new int[DECK.size()]),
        Collections.nCopies(4, List.of()));
    return battle;
  }

  private static List<WorldEntity> entities(Standard1v1Battle battle) {
    List<WorldEntity> out = new ArrayList<>();
    for (BattleEntity entity : battle.getBattle().getHolder().entities()) {
      out.add((WorldEntity) entity);
    }
    return out;
  }

  @Test
  @DisplayName(
      "each player's towers stand from its team spawn group, mirrored across the width for sides"
          + " 0 and 1 and along the length for team 1, then each team's middle piece")
  void theTowersStandFromEachPlayersSpawnGroup() {
    Standard1v1Battle battle = teamBattle();
    TileMap map = teamMap();
    GameRow group = Shipped.row("spawn_groups", teamGroup());
    List<WorldEntity> entities = entities(battle);
    List<JsonNode> objects = new ArrayList<>();
    group.value("Objects").forEach(element -> objects.add(group.tableElement("Objects", element)));

    assertThat(entities).hasSize(4 * objects.size() + 2);
    int index = 0;
    for (int side = 0; side < 4; side++) {
      for (JsonNode object : objects) {
        WorldEntity tower = entities.get(index++);
        int col = object.path("x").asInt();
        int row = object.path("y").asInt();
        assertThat(tower).isInstanceOf(TowerEntity.class);
        assertThat(tower.getData().name()).isEqualTo(object.path("Data").asText());
        assertThat(tower.side()).isEqualTo(side);
        assertThat(tower.x()).isEqualTo((side <= 1 ? map.width() - col : col) * 500);
        assertThat(tower.y()).isEqualTo(((side & 1) != 0 ? map.height() - row : row) * 500);
      }
    }
    // The middle pieces: team 0's then team 1's, in the middle of the width on their kings' row,
    // without hit points and standing from the start.
    for (int side = 0; side < 2; side++) {
      WorldEntity middle = entities.get(index++);
      WorldEntity king = entities.get(side * objects.size());
      assertThat(middle).isInstanceOf(CharacterEntity.class);
      assertThat(middle.getData().name()).isEqualTo(Standard1v1Battle.KING_TOWER_MIDDLE);
      assertThat(middle.side()).isEqualTo(side);
      assertThat(middle.x()).isEqualTo(map.widthUnits() / 2);
      assertThat(middle.y()).isEqualTo(king.y());
      assertThat(middle.getHitPoints()).isNull();
      assertThat(middle.getView().getState()).isEqualTo(GridEntityState.STANDING);
    }
  }

  @Test
  @DisplayName("the middle pieces stay without hit points, where any other such object leaves")
  void theMiddlePiecesStay() {
    Standard1v1Battle battle = teamMatch();
    for (int step = 0; step < 40; step++) {
      battle.getBattle().step();
    }
    assertThat(entities(battle))
        .filteredOn(e -> e.getData().name().equals(Standard1v1Battle.KING_TOWER_MIDDLE))
        .hasSize(2)
        .allMatch(e -> e.getView().getState() == GridEntityState.STANDING);
  }

  @Test
  @DisplayName(
      "a king's hit points are raised by the team king percent, a princess tower's by the team"
          + " princess tower percent, rounded up")
  void theTowersHitPointsAreRaised() {
    Standard1v1Battle oneASide = new Standard1v1Battle(GameData.tables(), LEVEL);
    int king = oneASide.getWorld().kingTower(0).getHitPoints().getMaximum();
    int princess =
        entities(oneASide).stream()
            .filter(e -> e.getData().summonerTower())
            .findFirst()
            .orElseThrow()
            .getHitPoints()
            .getMaximum();
    int kingPercent =
        100 + GameData.records().globalNumber("TEAM_VS_TEAM_SUMMONER_HP_INCREASE_PERCENTAGE");
    int princessPercent =
        100 + GameData.records().globalNumber("TEAM_VS_TEAM_PRINCESS_TOWER_HP_INCREASE_PERCENTAGE");

    for (WorldEntity tower : entities(teamBattle())) {
      if (tower.getData().king()) {
        assertThat(tower.getHitPoints().getMaximum()).isEqualTo((kingPercent * king + 99) / 100);
      } else if (tower.getData().summonerTower()) {
        assertThat(tower.getHitPoints().getMaximum())
            .isEqualTo((princessPercent * princess + 99) / 100);
      }
    }
  }

  @Test
  @DisplayName("the kings stand at the levels the battle is given, each its own row's")
  void theKingsStandAtTheirLevels() {
    Standard1v1Battle battle = teamBattle(LEVEL - 1, LEVEL, LEVEL + 1, LEVEL);
    Standard1v1Battle lower = teamBattle(LEVEL - 1, LEVEL - 1, LEVEL - 1, LEVEL - 1);
    assertThat(battle.getWorld().kingTower(0).getHitPoints().getMaximum())
        .isEqualTo(lower.getWorld().kingTower(0).getHitPoints().getMaximum());
    assertThat(battle.getWorld().kingTower(2).getHitPoints().getMaximum())
        .isGreaterThan(battle.getWorld().kingTower(1).getHitPoints().getMaximum());
  }

  @Test
  @DisplayName(
      "the match deals four players, side 0 to side 3, one shuffle draw each, under the team row's"
          + " timeline, each starting at its starting elixir")
  void theMatchDealsFourPlayers() {
    Standard1v1Battle battle = teamBattle();
    // The draws the shuffles take, one after the other, from the battle's source as it stands.
    BattleRandom random = new BattleRandom(battle.getWorld().getRandom().getState());
    battle.startTeamMatch(
        Collections.nCopies(4, DECK),
        new int[] {0, 0, 0, 0},
        Collections.nCopies(4, new int[DECK.size()]),
        Collections.nCopies(4, List.of()));
    LadderMatch match = battle.getMatch();

    assertThat(match.playerCount()).isEqualTo(4);
    for (int side = 0; side < 4; side++) {
      assertThat(match.shuffleDraw(side)).isEqualTo(random.next(0x0fffffff));
      assertThat(match.side(side).getElixir())
          .isEqualTo(match.getTimeline().row().startingElixir() * MatchSide.SCALE);
    }
    assertThat(match.getTimeline().row().name())
        .isEqualTo(
            Shipped.text(Shipped.row("game_modes", LadderMatch.TEAM_GAME_MODE), "BattleTimeline"));
  }

  @Test
  @DisplayName(
      "the start cooldown is the team row's own, or the team battle's global where it leaves it"
          + " empty")
  void theStartCooldownIsTheTeamBattles() {
    GameRow row = Shipped.row("game_modes", LadderMatch.TEAM_GAME_MODE);
    int own = Shipped.number(row, "BattleStartCooldown", 0);
    int expected =
        own != 0 ? own : GameData.records().globalNumber("LOGIC_BATTLE_START_COOLDOWN_2V2");

    assertThat(teamMatch().getMatch().getStartCooldownTicks()).isEqualTo(expected / 50);
  }

  @Test
  @DisplayName("each player regenerates its own elixir on its own king's visit")
  void eachPlayerRegeneratesItsOwnElixir() {
    Standard1v1Battle battle = teamMatch();
    LadderMatch match = battle.getMatch();
    int start = match.side(0).getElixir();
    battle.getBattle().step();
    int gained = match.side(0).getElixir() - start;

    assertThat(gained).isPositive();
    for (int side = 1; side < 4; side++) {
      assertThat(match.side(side).getElixir() - start).isEqualTo(gained);
    }
  }

  @Test
  @DisplayName(
      "a princess tower of either partner gives the other team a crown, from the cleanup that"
          + " removes it")
  void crownsArePerTeam() {
    Standard1v1Battle battle = teamMatch();
    LadderMatch match = battle.getMatch();
    battle.getBattle().step();
    WorldEntity princess =
        entities(battle).stream()
            .filter(e -> e.getData().summonerTower() && e.side() == 2)
            .findFirst()
            .orElseThrow();
    battle.getWorld().getHits().kill(princess, null);
    battle.getBattle().step();
    battle.getBattle().step();

    assertThat(match.crowns(1)).isEqualTo(1);
    assertThat(match.crowns(0)).isZero();
  }

  @Test
  @DisplayName(
      "a hit on a king of side 2 or 3 lowers its partner's king's hit points, the team's pool,"
          + " while its own stay at their maximum and its dedupe list is its own")
  void aHitOnAHalfLowersThePool() {
    Standard1v1Battle battle = teamMatch();
    battle.getBattle().step();
    TowerEntity holder = battle.getWorld().kingTower(1);
    TowerEntity half = battle.getWorld().kingTower(3);
    int pool = holder.getHitPoints().getHitPoints();

    half.takeDamage(100, 7, 0, 1);
    half.takeDamage(100, 7, 0, 1);
    assertThat(holder.getHitPoints().getHitPoints())
        .as("the second hit of the id is refused by the half's own list")
        .isEqualTo(pool - 100);
    assertThat(half.getHitPoints().getHitPoints()).isEqualTo(half.getHitPoints().getMaximum());
    assertThat(half.getHitPoints().dedupeIds()).containsExactly(7);

    holder.takeDamage(100, 7, 0, 1);
    assertThat(holder.getHitPoints().getHitPoints())
        .as("the holder lists the id on its own list")
        .isEqualTo(pool - 200);
    assertThat(holder.getTargetView().getHitPoints()).isEqualTo(pool - 200);
  }

  @Test
  @DisplayName(
      "the pool's fall through either half takes both kings, the half's own hit points to 0, and"
          + " gives the other team the battle; a second hit in that step is refused")
  void thePoolFallsWithBothKings() {
    for (int struck : new int[] {0, 2}) {
      Standard1v1Battle battle = teamMatch();
      battle.getBattle().step();
      BattleWorld world = battle.getWorld();
      TowerEntity holder = world.kingTower(0);
      TowerEntity half = world.kingTower(2);
      TowerEntity target = world.kingTower(struck);

      world
          .getHits()
          .dealDamage(target.getTargetView(), holder.getHitPoints().getHitPoints(), 0, 1);

      assertThat(holder.getHitPoints().getHitPoints()).isZero();
      assertThat(half.getHitPoints().getHitPoints()).isZero();
      assertThatThrownBy(() -> half.takeDamage(1, 0, 0, 1))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("has fallen in the step");
      battle.getBattle().step();
      assertThat(battle.getMatch().isEnded()).isTrue();
      assertThat(battle.getMatch().getWinner()).as("struck side " + struck).isEqualTo(1);
    }
  }

  /** How many steps an area effect of these tests runs for: one hit at least, its buff still on. */
  private static final int STEPS = 30;

  /** A team's middle piece. */
  private static WorldEntity middle(Standard1v1Battle battle, int team) {
    return battle.getWorld().getHolder().entities().stream()
        .filter(
            e ->
                e instanceof WorldEntity w
                    && w.getData().name().equals(Standard1v1Battle.KING_TOWER_MIDDLE)
                    && w.side() == team)
        .map(WorldEntity.class::cast)
        .findFirst()
        .orElseThrow();
  }

  /** What an area effect of side 1 over side 0's king takes off it in a battle of two players. */
  private static int oneASideLoss(String row) {
    Standard1v1Battle battle = new Standard1v1Battle(GameData.tables(), LEVEL);
    TowerEntity king = battle.getWorld().kingTower(0);
    battle
        .getWorld()
        .createAreaEffect(row, king.x(), king.y(), 1, king.getPackedLevel(), null, "test", null);
    for (int step = 0; step < STEPS; step++) {
      battle.getBattle().step();
    }
    int loss = king.getHitPoints().getMaximum() - king.getHitPoints().getHitPoints();
    assertThat(loss).as(row + " hits the king").isPositive();
    return loss;
  }

  /**
   * Casts an area effect of side 1 on side 0's king, whose circle reaches the side 2 king too, and
   * steps the battle {@value #STEPS} times.
   */
  private static Standard1v1Battle castOnTheHolder(String row) {
    Standard1v1Battle battle = teamMatch();
    battle.getBattle().step();
    TowerEntity holder = battle.getWorld().kingTower(0);
    TowerEntity half = battle.getWorld().kingTower(2);
    AreaEffectData data = GameData.records().areaEffect(row);
    assertThat(Math.abs(holder.x() - half.x()))
        .as("the circle reaches both halves")
        .isLessThan(data.radius() + half.getData().collisionRadius());
    battle
        .getWorld()
        .createAreaEffect(
            row, holder.x(), holder.y(), 1, holder.getPackedLevel(), null, "test", null);
    for (int step = 0; step < STEPS; step++) {
      battle.getBattle().step();
    }
    return battle;
  }

  @Test
  @DisplayName(
      "an area effect hits one half of a team's king, lists the other as reached, and carries a"
          + " buff without damage over time to it and to the team's middle piece")
  void anAreaEffectCarriesItsBuffToThePartner() {
    String row = "Freeze";
    String buff = GameData.records().areaEffect(row).buff();
    assertThat(GameData.records().buff(buff).damagePerSecond()).isZero();
    Standard1v1Battle battle = castOnTheHolder(row);
    TowerEntity holder = battle.getWorld().kingTower(0);
    TowerEntity half = battle.getWorld().kingTower(2);

    assertThat(holder.getHitPoints().getMaximum() - holder.getHitPoints().getHitPoints())
        .as("the pool takes the hit once")
        .isEqualTo(oneASideLoss(row));
    assertThat(half.getHitPoints().getHitPoints()).isEqualTo(half.getHitPoints().getMaximum());
    assertThat(holder.getBuffs().carries(buff)).isTrue();
    assertThat(half.getBuffs().carries(buff)).isTrue();
    assertThat(middle(battle, 0).getBuffs().carries(buff)).isTrue();
  }

  @Test
  @DisplayName(
      "a buff with damage over time stays on the half of a team's king the area effect hits, the"
          + " team's middle piece carrying it too")
  void aDamagingBuffStaysOnTheHalfItHits() {
    String row = "Poison";
    String buff = GameData.records().areaEffect(row).buff();
    assertThat(GameData.records().buff(buff).damagePerSecond()).isPositive();
    Standard1v1Battle battle = castOnTheHolder(row);
    TowerEntity holder = battle.getWorld().kingTower(0);
    TowerEntity half = battle.getWorld().kingTower(2);

    assertThat(holder.getHitPoints().getMaximum() - holder.getHitPoints().getHitPoints())
        .isEqualTo(oneASideLoss(row));
    assertThat(holder.getBuffs().carries(buff)).isTrue();
    assertThat(half.getBuffs().carries(buff)).isFalse();
    assertThat(middle(battle, 0).getBuffs().carries(buff)).isTrue();
  }

  @Test
  @DisplayName(
      "an idle team battle's tiebreaker drains both teams' princess towers and the kings of side 0"
          + " and side 1 only, and ends a draw when all four sides' lowest are equal")
  void anIdleTeamBattleEndsADraw() {
    Standard1v1Battle battle = teamMatch();
    LadderMatch match = battle.getMatch();
    while (!match.isOver()) {
      battle.getBattle().step();
    }
    BattleWorld world = battle.getWorld();

    assertThat(match.isEnded()).isTrue();
    assertThat(match.getWinner()).isEqualTo(-1);
    for (int side = 0; side < 4; side++) {
      TowerEntity king = world.kingTower(side);
      boolean drained = side < 2;
      assertThat(king.getHitPoints().getHitPoints() < king.getHitPoints().getMaximum())
          .as("king of side " + side + " drained")
          .isEqualTo(drained);
    }
    // The tiebreaker's clearing took the middle pieces away.
    assertThat(entities(battle))
        .noneMatch(e -> e.getData().name().equals(Standard1v1Battle.KING_TOWER_MIDDLE));
  }
}
