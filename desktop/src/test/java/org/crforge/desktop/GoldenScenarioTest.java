package org.crforge.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.desktop.GoldenScenario.Case;
import org.crforge.desktop.GoldenScenario.Entry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The golden scenarios load a reference trajectory bundled with the visualizer and compare the live
 * unit against it tick for tick. These tests cover the loading and the first-deviation detection;
 * the drawing of the ghost polyline is not unit tested.
 */
class GoldenScenarioTest {

  @Test
  @DisplayName("the three cases are cycled through in order and wrap around")
  void casesCycle() {
    GoldenScenario scenario = new GoldenScenario();

    assertThat(scenario.nextCaseName()).isEqualTo("knight_left");
    assertThat(scenario.nextCaseName()).isEqualTo("knight_right");
    assertThat(scenario.nextCaseName()).isEqualTo("knight_centre");
    assertThat(scenario.nextCaseName()).isEqualTo("knight_left");
  }

  @Test
  @DisplayName("a bundled case loads its deployment and every record")
  void loadsABundledCase() {
    Case loaded = GoldenScenario.load("knight_left");

    assertThat(loaded.name()).isEqualTo("knight_left");
    assertThat(loaded.card()).isEqualTo("Knight");
    assertThat(loaded.deployX()).isEqualTo(3500);
    assertThat(loaded.deployY()).isEqualTo(10000);
    assertThat(loaded.side()).isZero();
    assertThat(loaded.lane()).isEqualTo(1);
    assertThat(loaded.records()).hasSize(236);

    Entry first = loaded.records().get(0);
    assertThat(first.tick()).isZero();
    assertThat(first.x()).isEqualTo(3500);
    assertThat(first.y()).isEqualTo(10000);
    assertThat(first.state()).isEqualTo(4);
    assertThat(first.reference()).isNull();
    assertThat(first.routeNodes()).isZero();

    Entry last = loaded.records().get(loaded.records().size() - 1);
    assertThat(last.tick()).isEqualTo(235);
    assertThat(last.reference()).isEqualTo("PrincessTower_1_1");
  }

  @Test
  @DisplayName("all three bundled cases load")
  void allCasesLoad() {
    for (String name : GoldenScenario.CASE_NAMES) {
      assertThat(GoldenScenario.load(name).records()).as("%s has records", name).isNotEmpty();
    }
  }

  @Test
  @DisplayName("the engine's first tick after the spawn is reference tick 0")
  void tickAlignment() {
    GoldenScenario scenario = new GoldenScenario();
    scenario.begin(synthetic(), 7);

    assertThat(scenario.referenceTick(7)).isEqualTo(-1);
    assertThat(scenario.referenceTick(8)).isZero();
    assertThat(scenario.referenceTick(10)).isEqualTo(2);
  }

  @Test
  @DisplayName("a unit that matches the trajectory never deviates")
  void noDeviation() {
    GoldenScenario scenario = new GoldenScenario();
    scenario.begin(synthetic(), 0);

    scenario.sample(1, 1000, 1000);
    scenario.sample(2, 1060, 1000);
    scenario.sample(3, 1120, 1000);

    assertThat(scenario.firstDeviationTick()).isNull();
    assertThat(scenario.deviationPoint()).isNull();
    assertThat(scenario.statusLines()).contains("deviation: none");
  }

  @Test
  @DisplayName("the first tick that differs by even one unit is remembered with its distance")
  void firstDeviationIsRemembered() {
    GoldenScenario scenario = new GoldenScenario();
    scenario.begin(synthetic(), 0);

    scenario.sample(1, 1000, 1000);
    scenario.sample(2, 1063, 1004);
    scenario.sample(3, 9999, 9999);

    assertThat(scenario.firstDeviationTick()).isEqualTo(1);
    // (1060, 1000) against (1063, 1004): three units across and four along, so five apart.
    assertThat(scenario.firstDeviationDistance()).isEqualTo(5);
    assertThat(scenario.deviationPoint()).containsExactly(1060, 1000);
    assertThat(scenario.statusLines()).contains("first deviation at tick 1, distance 5 units");
  }

  @Test
  @DisplayName("the golden point of a tick outside the trajectory is absent")
  void goldenPointOutsideTheTrajectory() {
    GoldenScenario scenario = new GoldenScenario();
    scenario.begin(synthetic(), 0);

    assertThat(scenario.goldenAt(0)).containsExactly(1000, 1000);
    assertThat(scenario.goldenAt(2)).containsExactly(1120, 1000);
    assertThat(scenario.goldenAt(3)).isNull();
    assertThat(scenario.goldenAt(-1)).isNull();
    assertThat(scenario.goldenPath()).hasSize(3);
  }

  @Test
  @DisplayName("clearing forgets the case and the deviation")
  void clearForgetsEverything() {
    GoldenScenario scenario = new GoldenScenario();
    scenario.begin(synthetic(), 0);
    scenario.sample(1, 9999, 9999);
    assertThat(scenario.firstDeviationTick()).isZero();

    scenario.clear();

    assertThat(scenario.getActiveCase()).isNull();
    assertThat(scenario.firstDeviationTick()).isNull();
    assertThat(scenario.goldenPath()).isEmpty();
    assertThat(scenario.statusLines()).isEmpty();
  }

  /** A three-tick trajectory that walks straight along the arena's width at sixty units a tick. */
  private static Case synthetic() {
    List<Entry> records =
        List.of(
            new Entry(0, 1000, 1000, 4, null, 0),
            new Entry(1, 1060, 1000, 1, "PrincessTower_1_1", 12),
            new Entry(2, 1120, 1000, 1, "PrincessTower_1_1", 11));
    return new Case("knight_left", "Knight", 1000, 1000, 0, 1, records);
  }
}
