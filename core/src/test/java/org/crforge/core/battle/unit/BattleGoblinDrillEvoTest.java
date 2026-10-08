package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The evolved Goblin Drill's relocation: it hides at each hit-point threshold and comes up five
 * ring steps further around the enemy tower it stands by.
 *
 * <p>The scene writes every column its points and steps are read from: the towers' places and size,
 * the relocation's thresholds (66 and 33 percent) and hide time (1000 ms), the hide group's spawns
 * and delays, and the drill's size, life and hit points, so they are its own and not a version's.
 */
class BattleGoblinDrillEvoTest {

  @TempDir static Path folder;

  /** The configured tables with the scene's columns written, and their records. */
  private static GameTables tables;

  private static BattleRecords records;

  @BeforeAll
  static void writeTheScene() throws IOException {
    GameData.altered(
        folder,
        "actions",
        rows -> {
          ObjectNode relocate = fields(rows, "GoblinDrill_EV1_relocate").put("HideTime", 1000);
          relocate.putArray("HideHpThresholds").add(66).add(33);
          fields(rows, "GoblinDrill_EV1_Hide_Group")
              .putArray("SubActionsDelay")
              .add(0)
              .add(50)
              .add(50);
          fields(rows, "GoblinDrill_EV1_Hide_Group2").putArray("SubActionsDelay").add(0).add(50);
          fields(rows, "GoblinDrill_EV1_Disable_Physics").put("ActionDuration", 1000);
          for (String spawn :
              List.of("GoblinDrill_EV1_Spawn_Goblin1", "GoblinDrill_EV1_Spawn_Goblin3")) {
            fields(rows, spawn).put("RelativeX", -1).put("RelativeY", 0).put("DeployTime", 500);
          }
          fields(rows, "GoblinDrill_EV1_Spawn_Goblin2")
              .put("RelativeX", 1)
              .put("RelativeY", 0)
              .put("DeployTime", 500);
        });
    GameData.alterLoaded(
        folder,
        "buildings",
        rows -> {
          GameData.columns(rows, "GoblinDrill_EV1")
              .put("CollisionRadius", 500)
              .put("LifeTime", 10000)
              .put("Hitpoints", 513)
              .put("DeployTime", 1000);
          GameData.columns(rows, "PrincessTower").put("CollisionRadius", 1000);
          GameData.columns(rows, "KingTower").put("CollisionRadius", 1400);
        });
    GameData.alterLoaded(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "GoblinDrill_EV1_Dig")
                .put("SpawnPathfindSpeed", 300)
                .put("DeployTime", 1000));
    GameData.alterLoaded(
        folder,
        "spawn_groups",
        rows -> {
          ArrayNode towers = GameData.columns(rows, "King_PrincessTowers").putArray("Objects");
          towers.addObject().put("Data", "KingTower").put("x", 18).put("y", 6);
          towers.addObject().put("Data", "PrincessTower").put("x", 7).put("y", 13);
          towers.addObject().put("Data", "PrincessTower").put("x", 29).put("y", 13);
        });
    tables = GameTables.load(folder);
    records = new BattleRecords(tables);
  }

  /** The fields of an action row in the actions table, to alter. */
  private static ObjectNode fields(ObjectNode rows, String action) {
    return (ObjectNode) rows.get(action).get("fields");
  }

  /** The evolved drill standing on the ring of the top side's right princess tower. */
  private static final int RING_X = 14000;

  private static final int RING_Y = 23000;

  private static Standard1v1Battle passiveTowers() {
    return new Standard1v1Battle(tables, Standard1v1Battle.DEFAULT_LEVEL, false);
  }

  /** The evolved drill building, once the dig has surfaced; null before. */
  static CharacterEntity drill(Standard1v1Battle match) {
    for (BattleEntity entity : match.getBattle().getHolder().entities()) {
      if (entity instanceof CharacterEntity unit
          && unit.getData().building()
          && unit.getData().onStartingAction() != null) {
        return unit;
      }
    }
    return null;
  }

  /** How many area effects of a row the holder lists. */
  static int areas(Standard1v1Battle match, String row) {
    int count = 0;
    for (BattleEntity entity : match.getBattle().getHolder().entities()) {
      if (entity instanceof AreaEffectEntity area && area.getData().name().equals(row)) {
        count++;
      }
    }
    return count;
  }

  /** The points of the live Goblins of a side. */
  private static List<String> goblins(Standard1v1Battle match) {
    List<String> out = new ArrayList<>();
    for (BattleEntity entity : match.getBattle().getHolder().entities()) {
      if (entity instanceof CharacterEntity unit && unit.getData().name().equals("Goblin")) {
        out.add(unit.getView().getX() + "," + unit.getView().getY());
      }
    }
    return out;
  }

  /** Plays the evolved drill onto the ring point and steps until it has surfaced and deployed. */
  private static CharacterEntity surfacedAndDeployed(Standard1v1Battle match) {
    match.play(
        0,
        records.card("GoblinDrill_EV1"),
        Standard1v1Battle.DEFAULT_LEVEL,
        0,
        RING_X,
        RING_Y,
        "Drill");
    CharacterEntity drill = null;
    for (int step = 0; step < 200 && drill == null; step++) {
      match.getBattle().step();
      drill = drill(match);
    }
    assertThat(drill).as("the dig surfaced").isNotNull();
    while (drill.getView().getState() == GridEntityState.DEPLOYING) {
      match.getBattle().step();
    }
    return drill;
  }

  @Test
  @DisplayName(
      "at its first threshold it hides with its hide goblins, moves five ring steps two steps"
          + " before the hide time ends, and deploys again there")
  void itHidesAndComesUpFiveRingStepsOn() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity drill = surfacedAndDeployed(match);
    int maximum = drill.getHitPoints().getMaximum();
    // 66 percent of the maximum or less: the first threshold.
    drill.getHitPoints().setHitPoints(maximum * 60 / 100);

    match.getBattle().step();
    assertThat(drill.getView().getState()).isEqualTo(GridEntityState.SPAWN_PATHFIND);
    assertThat(drill.hidden()).isTrue();
    assertThat(goblins(match)).isEmpty();

    match.getBattle().step();
    // The hide group's two spawns, one half tile either side of the drill.
    assertThat(goblins(match)).containsExactlyInAnyOrder("13500,23000", "14500,23000");

    for (int step = 0; step < 16; step++) {
      match.getBattle().step();
    }
    assertThat(drill.getView().getX()).as("not moved yet").isEqualTo(RING_X);

    match.getBattle().step();
    // Five steps from the ring's seventh point: three along the near side, two up the far one.
    assertThat(drill.getView().getX()).isEqualTo(17000);
    assertThat(drill.getView().getY()).isEqualTo(25000);
    assertThat(drill.getView().getState()).isEqualTo(GridEntityState.SPAWN_PATHFIND);

    match.getBattle().step();
    match.getBattle().step();
    assertThat(drill.getView().getState()).isEqualTo(GridEntityState.DEPLOYING);
    assertThat(drill.hidden()).isFalse();
  }

  @Test
  @DisplayName(
      "the hide goblins, spawned with a deploy time of their own, face where the drill faces")
  void theHideGoblinsFaceAsTheDrillDoes() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity drill = surfacedAndDeployed(match);
    int dirX = drill.getView().getDirX();
    int dirY = drill.getView().getDirY();
    assertThat(dirX).as("the dig's facing, not the side's default").isNotZero();
    drill.getHitPoints().setHitPoints(drill.getHitPoints().getMaximum() * 60 / 100);

    match.getBattle().step();
    match.getBattle().step();

    List<CharacterEntity> goblins = new ArrayList<>();
    for (BattleEntity entity : match.getBattle().getHolder().entities()) {
      if (entity instanceof CharacterEntity unit && unit.getData().name().equals("Goblin")) {
        goblins.add(unit);
      }
    }
    assertThat(goblins).hasSize(2);
    for (CharacterEntity goblin : goblins) {
      assertThat(goblin.getView().getDirX()).isEqualTo(dirX);
      assertThat(goblin.getView().getDirY()).isEqualTo(dirY);
    }
  }

  @Test
  @DisplayName("above its threshold it stays standing where it came up")
  void itStaysAboveItsThreshold() {
    Standard1v1Battle match = passiveTowers();
    CharacterEntity drill = surfacedAndDeployed(match);
    int maximum = drill.getHitPoints().getMaximum();
    // The lifetime decay takes about 15 percent over the 30 steps, leaving it above 66.
    drill.getHitPoints().setHitPoints(maximum * 90 / 100);

    for (int step = 0; step < 30; step++) {
      match.getBattle().step();
    }

    assertThat(drill.getView().getState()).isNotEqualTo(GridEntityState.SPAWN_PATHFIND);
    assertThat(drill.getView().getX()).isEqualTo(RING_X);
    assertThat(drill.getView().getY()).isEqualTo(RING_Y);
  }

  @Test
  @DisplayName("a drill that surfaces on no ring point of an enemy tower is refused")
  void offTheRingItIsRefused() {
    Standard1v1Battle match = passiveTowers();
    match.play(
        0, records.card("GoblinDrill_EV1"), Standard1v1Battle.DEFAULT_LEVEL, 0, 9000, 20000, "D");

    assertThatThrownBy(
            () -> {
              for (int step = 0; step < 200; step++) {
                match.getBattle().step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("standing on no ring point of an enemy tower");
  }
}
