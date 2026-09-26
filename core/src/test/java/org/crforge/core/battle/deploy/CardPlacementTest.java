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
        "deploy_refused"
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

  private static int[] ints(JsonNode array) {
    int[] out = new int[array.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = array.get(i).asInt();
    }
    return out;
  }
}
