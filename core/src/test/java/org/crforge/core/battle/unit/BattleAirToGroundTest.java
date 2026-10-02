package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Vines' air-to-ground run where the reference runs do not reach: an air unit that lives through
 * its hold, whose climb ends in the path reset that is not modelled; the ground tag a held ground
 * unit carries, and none without the row's flag; and a hovering unit refused.
 */
class BattleAirToGroundTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** A point on the top side, away from every tower. */
  private static final int X = 9000;

  private static final int Y = 20000;

  /** A battle with the towers holding fire, and every start and phase change of a run. */
  private static final class Scene {
    final Standard1v1Battle match;
    final List<String> runs = new ArrayList<>();

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void airToGroundStarted(
                    int tick,
                    WorldEntity unit,
                    String action,
                    int phase,
                    int runPhase,
                    int counter,
                    int height) {
                  runs.add(
                      "%d start phase %d counter %d height %d"
                          .formatted(tick, runPhase, counter, height));
                }

                @Override
                public void airToGroundStepped(
                    int tick,
                    WorldEntity unit,
                    int phaseBefore,
                    int phaseAfter,
                    int counterBefore,
                    int counterAfter,
                    boolean done,
                    List<Integer> pushes) {
                  runs.add(
                      "%d phase %d %d counter %d %d done %s pushes %s"
                          .formatted(
                              tick,
                              phaseBefore,
                              phaseAfter,
                              counterBefore,
                              counterAfter,
                              done,
                              pushes));
                }
              });
    }

    void steps(int count) {
      for (int i = 0; i < count; i++) {
        match.getBattle().step();
      }
    }

    boolean forcedOntoTheGround(WorldEntity unit) {
      return (unit.getView().getFlags() & match.getWorld().forceIsGround()) != 0;
    }
  }

  @Test
  @DisplayName(
      "a Baby Dragon is pulled down in two steps, is a ground unit at height 0 from the next"
          + " pre-hooks, and climbs back once its hold ends, where its path reset is refused")
  void anAirUnitIsPulledDown() {
    Scene scene = new Scene(GameData.tables());
    CharacterEntity dragon =
        scene.match.deploy(0, GameData.unit("BabyDragon"), LEVEL, 1, X, Y, "dragon");
    int height = dragon.getData().flyingHeight();
    scene.match.placeAreaEffect(0, "Vines_AeO", LEVEL, 0, X, Y, "vines");
    scene.steps(21);
    assertThat(scene.runs)
        .containsExactly(
            "18 start phase 1 counter 50 height " + height,
            "20 phase 1 2 counter 0 1900 done false pushes [" + -height + "]");
    assertThat(dragon.getTargetView().z()).as("the pushes are not folded yet").isEqualTo(height);

    scene.steps(1);
    assertThat(dragon.getTargetView().z()).as("its live height").isZero();
    assertThat(dragon.getView().isAir()).as("FORCE_IS_GROUND is raised but not yet in").isTrue();
    scene.steps(1);
    assertThat(scene.forcedOntoTheGround(dragon)).isTrue();
    assertThat(dragon.getView().isAir()).isFalse();

    // The hold lasts to tick 58; tick 59 starts the climb, whose one step on 60 ends the run.
    scene.steps(37);
    assertThat(scene.runs)
        .last()
        .isEqualTo("59 phase 2 3 counter 0 0 done false pushes [" + -height + "]");
    assertThatThrownBy(() -> scene.steps(1))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("resetting its path");
  }

  @Test
  @DisplayName(
      "a held Knight carries FORCE_IS_GROUND from the pre-hook after its first step to the one"
          + " after its last, and none when the row does not ask for it")
  void aGroundUnitIsTagged(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(GameData.tables());
    CharacterEntity knight =
        scene.match.deploy(0, GameData.unit("Knight"), LEVEL, 1, X, Y, "knight");
    scene.match.placeAreaEffect(0, "Vines_AeO", LEVEL, 0, X, Y, "vines");
    scene.steps(20);
    assertThat(scene.forcedOntoTheGround(knight)).isFalse();
    scene.steps(1);
    assertThat(scene.forcedOntoTheGround(knight)).isTrue();
    scene.steps(39);
    assertThat(scene.runs)
        .containsExactly(
            "18 start phase 0 counter 2000 height -1",
            "59 phase 0 0 counter 0 0 done true pushes []");
    assertThat(scene.forcedOntoTheGround(knight)).isTrue();
    scene.steps(1);
    assertThat(scene.forcedOntoTheGround(knight)).isFalse();
    assertThat(knight.getView().isAir()).isFalse();

    Files.createDirectories(folder);
    GameTables untagged =
        GameData.altered(
            folder,
            "actions",
            rows ->
                ((ObjectNode) rows.get("Vines_Air_To_Ground").get("fields"))
                    .put("AllowIsGroundTagOnIdle", false));
    Scene plain = new Scene(untagged);
    CharacterEntity other =
        plain.match.deploy(0, GameData.unit("Knight"), LEVEL, 1, X, Y, "knight");
    plain.match.placeAreaEffect(0, "Vines_AeO", LEVEL, 0, X, Y, "vines");
    for (int i = 0; i < 40; i++) {
      plain.steps(1);
      assertThat(plain.forcedOntoTheGround(other)).isFalse();
    }
  }

  @Test
  @DisplayName("an air-to-ground run on a hovering unit is refused")
  void aHoveringUnitIsRefused() {
    Scene scene = new Scene(GameData.tables());
    scene.match.deploy(0, GameData.unit("BattleHealer"), LEVEL, 1, X, Y, "healer");
    scene.match.placeAreaEffect(0, "Vines_AeO", LEVEL, 0, X, Y, "vines");
    scene.steps(18);
    assertThatThrownBy(() -> scene.steps(1))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("a hovering unit");
  }
}
