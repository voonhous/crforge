package org.crforge.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Collections;
import java.util.List;
import org.crforge.bridge.dto.EntityDTO;
import org.crforge.bridge.dto.InitConfig;
import org.crforge.bridge.dto.ObservationDTO;
import org.crforge.bridge.dto.StepAction;
import org.crforge.bridge.dto.TowerDTO;
import org.crforge.bridge.observation.BinaryObservationEncoder;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.junit.jupiter.api.Test;

/**
 * The Python bridge protocol stays in tiles while the engine runs in integer game units. These
 * tests pin the conversion at both boundaries: actions in, observations out.
 */
class TileProtocolConversionTest {

  private static final List<String> DECK =
      List.of(
          "knight", "archer", "fireball", "arrows", "giant", "musketeer", "minions", "valkyrie");

  @Test
  void stepAction_convertsTileCoordinatesToGameUnits() {
    PlayerActionDTO action = new StepAction(2, 9.5f, 10.25f).toPlayerAction();

    assertThat(action.getHandIndex()).isEqualTo(2);
    assertThat(action.getX()).isEqualTo(9500);
    assertThat(action.getY()).isEqualTo(10250);
  }

  @Test
  void stepAction_roundsSubUnitTileInputToNearestUnit() {
    // 3.0004 tiles = 3000.4 units; agents may emit arbitrary floats
    PlayerActionDTO action = new StepAction(0, 3.0004f, 7.9996f).toPlayerAction();

    assertThat(action.getX()).isEqualTo(3000);
    assertThat(action.getY()).isEqualTo(8000);
  }

  @Test
  void observation_reportsTowerAndEntityPositionsInTiles() {
    GameSession session = new GameSession();
    session.init(new InitConfig(DECK, DECK, 11, 1));

    ObservationDTO obs = session.observe();

    TowerDTO blueCrown =
        obs.bluePlayer().towers().stream()
            .filter(t -> t.type().equals("crown"))
            .findFirst()
            .orElseThrow();
    assertThat(blueCrown.x()).isEqualTo(9.0f);
    assertThat(blueCrown.y()).isEqualTo(3.0f);

    for (EntityDTO entity : obs.entities()) {
      assertThat(entity.x()).isBetween(0f, 18f);
      assertThat(entity.y()).isBetween(0f, 32f);
    }
    assertThat(obs.entities())
        .anySatisfy(
            e -> {
              assertThat(e.x()).isEqualTo(3.5f);
              assertThat(e.y()).isEqualTo(6.5f);
            });
  }

  @Test
  void deployedTroop_appearsAtRequestedTilePositionInObservation() {
    // An all-Knight deck makes every hand slot a single-unit troop
    List<String> knights = Collections.nCopies(8, "knight");
    GameSession session = new GameSession();
    session.init(new InitConfig(knights, DECK, 11, 25));

    // 25 ticks covers the 1 s (20 tick) placement sync delay
    session.step(new StepAction(0, 9.5f, 8.5f), null);

    ObservationDTO obs = session.observe();
    List<EntityDTO> blueTroops =
        obs.entities().stream()
            .filter(e -> e.team().equals(Team.BLUE.name()) && e.entityType().equals("TROOP"))
            .toList();
    assertThat(blueTroops).isNotEmpty();
    // Spawned at the tile-space request; only deploy-time physics can nudge it slightly
    assertThat(blueTroops.get(0).x()).isCloseTo(9.5f, within(0.6f));
    assertThat(blueTroops.get(0).y()).isCloseTo(8.5f, within(0.6f));
  }

  @Test
  void binaryObservation_normalizesTowerPositionsFromTiles() {
    GameSession session = new GameSession();
    session.init(new InitConfig(DECK, DECK, 11, 1));
    float[] obs =
        new BinaryObservationEncoder()
            .fillAndGetObsBuffer(
                session.getEngine(), session.getBluePlayer(), session.getRedPlayer());

    // Tower block starts at offset 22: [hp_frac, x_norm, y_norm, alive] per tower, blue first
    List<Tower> blueTowers = session.getEngine().getGameState().getTowers().get(Team.BLUE);
    for (int i = 0; i < blueTowers.size(); i++) {
      Tower tower = blueTowers.get(i);
      int base = 22 + i * 4;
      assertThat(obs[base + 1]).isCloseTo(tower.getPosition().getX() / 18000f, within(1e-6f));
      assertThat(obs[base + 2]).isCloseTo(tower.getPosition().getY() / 32000f, within(1e-6f));
    }
    assertThat(obs[22 + 1]).isBetween(0f, 1f);
  }
}
