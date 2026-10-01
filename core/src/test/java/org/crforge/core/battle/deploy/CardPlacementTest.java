package org.crforge.core.battle.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.TowerEntity;
import org.crforge.core.battle.unit.UnitData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every card play of the placement references worked out before anything is created: the refusal
 * code, or the placed point, the column its units are clamped into, the lane of the point, and each
 * unit's formation offset, position, lane and start.
 */
class CardPlacementTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "barbarians_left",
        "barbarians_edge",
        "skeleton_army_edge",
        "knight_side1",
        "deploy_refused",
        "three_musketeers_pekka",
        "three_musketeers_air_building"
      })
  void everyPlayLandsWhereTheReferencePlacesIt(String name) throws IOException {
    JsonNode reference;
    try (InputStream stream =
        CardPlacementTest.class.getResourceAsStream("/pathfinding/golden/" + name + ".json")) {
      reference = MAPPER.readTree(stream);
    }
    Standard1v1Battle match =
        new Standard1v1Battle(GameData.tables(), reference.get("tower_level").asInt());
    List<MaskEntity> towers = new ArrayList<>();
    for (BattleEntity entity : match.getBattle().getHolder().entities()) {
      if (entity instanceof TowerEntity tower) {
        towers.add(
            PlacementSearch.maskEntity(
                tower.getData(),
                tower.side(),
                tower.getView().getX(),
                tower.getView().getY(),
                true));
      }
    }

    for (JsonNode command : reference.get("commands")) {
      String where = name + " " + command.get("name").asText();
      DeployCard card = GameData.card(command.get("card").asText());
      CardPlacement.Result result =
          CardPlacement.place(
              match.getWorld().getTileMap(),
              card,
              command.get("point").get(0).asInt(),
              command.get("point").get(1).asInt(),
              command.get("side").asInt(),
              towers,
              true,
              true);

      assertThat(result.code()).as("%s code", where).isEqualTo(command.get("code").asInt());
      if (!command.get("outcome").asText().equals("placed")) {
        assertThat(result.units()).as("%s creates nothing", where).isEmpty();
        continue;
      }
      assertThat(new int[] {result.x(), result.y()})
          .as("%s placed", where)
          .containsExactly(ints(command.get("placed")));
      assertThat(result.interval())
          .as("%s interval", where)
          .containsExactly(ints(command.get("interval")));
      assertThat(result.originLane())
          .as("%s origin lane", where)
          .isEqualTo(command.get("origin_lane").asInt());
      assertThat(result.units()).as("%s units", where).hasSize(command.get("units").size());
      for (int i = 0; i < result.units().size(); i++) {
        CardPlacement.Unit unit = result.units().get(i);
        JsonNode expected = command.get("units").get(i);
        String at = where + " unit " + i;
        assertThat(unit.unit().name()).as("%s row", at).isEqualTo(expected.get("row").asText());
        assertThat(new int[] {unit.dx(), unit.dy()})
            .as("%s formation", at)
            .containsExactly(ints(expected.get("formation")));
        assertThat(new int[] {unit.x(), unit.y()})
            .as("%s position", at)
            .containsExactly(expected.get("x").asInt(), expected.get("y").asInt());
        assertThat(unit.lane()).as("%s lane", at).isEqualTo(expected.get("lane").asInt());
        assertThat(unit.start().state())
            .as("%s state", at)
            .isEqualTo(expected.get("state").asInt());
        assertThat(Math.max(unit.start().waitMs(), 0))
            .as("%s wait", at)
            .isEqualTo(expected.get("delay").asInt());
      }
    }
  }

  @Test
  @DisplayName(
      "a listed character's offset: both negated for the bottom side, the one across negated for"
          + " the top side from the middle on when the card mirrors it")
  void aListedOffsetTurnsBySideAndHalf() {
    DeployCard card = GameData.card("ThreeMusketeers");
    // Character_2 lists (-1000, 1000); the map is 36 cells wide, its middle at 9000.
    DeployCard.Listed second = card.listed().get(1);
    assertThat(CardPlacement.listOffset(second, true, 0, 3500, 36)).containsExactly(1000, -1000);
    assertThat(CardPlacement.listOffset(second, true, 0, 14500, 36)).containsExactly(1000, -1000);
    assertThat(CardPlacement.listOffset(second, true, 1, 8999, 36)).containsExactly(-1000, 1000);
    assertThat(CardPlacement.listOffset(second, true, 1, 9000, 36)).containsExactly(1000, 1000);
    assertThat(CardPlacement.listOffset(second, false, 1, 14500, 36)).containsExactly(-1000, 1000);
  }

  @Test
  @DisplayName(
      "the top side's Three Musketeers on the left half: the front one level with the point, the"
          + " other two behind it, waiting 100 and 200")
  void theTopSidesListOnTheLeftHalf() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), 11);
    CardPlacement.Result result =
        CardPlacement.place(
            match.getWorld().getTileMap(),
            GameData.card("ThreeMusketeers"),
            3500,
            20500,
            1,
            List.of(),
            true,
            true);
    assertThat(result.units())
        .extracting(
            u ->
                u.unit().name()
                    + " "
                    + u.dx()
                    + " "
                    + u.dy()
                    + " "
                    + u.start().state()
                    + " "
                    + u.start().waitMs())
        .containsExactly(
            "ThreeMusketeer_Rework_Character_1 0 -1000 4 -1",
            "ThreeMusketeer_Rework_Character_2 -1000 1000 11 100",
            "ThreeMusketeer_Rework_Character_3 1000 1000 11 200");
  }

  @Test
  @DisplayName(
      "the loop reads the card's first unit's radius, angle shift and deploy time for every index:"
          + " a second group's own leave its places and starts as they are")
  void theFirstUnitsColumnsServeEveryIndex() {
    DeployCard rascals = GameData.card("Rascals");
    // The girls' own radius, angle shift and deploy time all differ from the boy's; with no
    // radius of its own the card's formation takes the boy's 750.
    UnitData girl =
        rascals.secondary().toBuilder()
            .collisionRadius(400)
            .spawnAngleShift(40)
            .deployTimeMs(0)
            .build();
    DeployCard card = withGroups(rascals, girl, 2);
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), 11);
    CardPlacement.Result result =
        CardPlacement.place(
            match.getWorld().getTileMap(), card, 3500, 10000, 0, List.of(), true, true);
    assertThat(result.units()).hasSize(3);
    for (CardPlacement.Unit unit : result.units()) {
      int[] expected =
          Formation.offset(unit.index(), 1, 750, 0, 1, result.originLane(), 0, 2, true, true);
      assertThat(new int[] {unit.dx(), unit.dy()})
          .as("unit %d", unit.index())
          .containsExactly(expected);
    }
    // The girls wait the second stagger in turn, as units with the boy's deploy time.
    assertThat(result.units())
        .extracting(u -> u.start().state() + " " + u.start().waitMs())
        .containsExactly("4 -1", "11 100", "11 200");

    // A first group of more than one turns the ring by the first unit's angle shift, never the
    // second group's own.
    DeployCard gang = GameData.card("GoblinGang");
    UnitData spear = gang.secondary().toBuilder().spawnAngleShift(40).build();
    DeployCard turned = withGroups(gang, spear, gang.secondaryCount());
    CardPlacement.Result ring =
        CardPlacement.place(
            match.getWorld().getTileMap(), turned, 3500, 10000, 0, List.of(), true, true);
    int gangRadius = gang.unit().collisionRadius();
    for (CardPlacement.Unit unit : ring.units()) {
      int[] expected =
          Formation.offset(unit.index(), 3, gangRadius, 0, 1, ring.originLane(), 0, 3, true, true);
      assertThat(new int[] {unit.dx(), unit.dy()})
          .as("goblin %d", unit.index())
          .containsExactly(expected);
    }
  }

  @Test
  @DisplayName(
      "the formation is handed the second count as the row sets it, with no second unit to place")
  void theRawSecondCount() {
    DeployCard knight = GameData.card("Knight");
    DeployCard card = withGroups(knight, null, 2);
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), 11);
    CardPlacement.Result result =
        CardPlacement.place(
            match.getWorld().getTileMap(), card, 3500, 10000, 0, List.of(), true, true);
    assertThat(result.units()).as("the second group places none").hasSize(1);
    CardPlacement.Unit unit = result.units().get(0);
    int radius = knight.unit().collisionRadius();
    assertThat(new int[] {unit.dx(), unit.dy()})
        .containsExactly(
            Formation.offset(0, 1, radius, 0, 1, result.originLane(), 0, 2, true, true));
    assertThat(new int[] {unit.dx(), unit.dy()})
        .as("a ring place, not the point")
        .isNotEqualTo(new int[] {0, 0});
  }

  /** The card with no radius of its own and the given second group. */
  private static DeployCard withGroups(DeployCard c, UnitData secondary, int secondaryCount) {
    return new DeployCard(
        c.name(),
        c.unit(),
        c.count(),
        secondary,
        secondaryCount,
        0,
        c.summonWidth(),
        c.summonDeployDelayMs(),
        c.summonDeployDelaySecondMs(),
        c.canDeployOnEnemySide(),
        c.canPlaceOnBuildings(),
        c.canPlaceOnWater(),
        c.fullLaneDeploy(),
        c.touchdownLimitedDeploy(),
        c.deployWTileMargin(),
        c.deployStartY(),
        c.deployEndY(),
        c.projectile(),
        c.areaEffect(),
        c.searchUnit(),
        c.spellAsDeploy(),
        c.radius(),
        c.multipleProjectiles(),
        c.projectileWaves(),
        c.projectileWaveIntervalMs(),
        c.projectileIntervalMs(),
        c.listed(),
        c.listOffsetsXMirrored());
  }

  private static int[] ints(JsonNode array) {
    int[] out = new int[array.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = array.get(i).asInt();
    }
    return out;
  }
}
