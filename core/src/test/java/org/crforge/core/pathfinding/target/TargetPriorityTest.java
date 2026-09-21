package org.crforge.core.pathfinding.target;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.pathfinding.GridEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which candidates a building-only attacker ranks first. */
class TargetPriorityTest {

  /** A queries stub: no candidate carries a deprioritizing buff. */
  private static final SelectionQueries NO_BUFFS =
      new SelectionQueries() {
        @Override
        public List<TargetView> candidates(int x, int y, int radius) {
          return List.of();
        }

        @Override
        public List<TargetView> allCandidates() {
          return List.of();
        }

        @Override
        public boolean validate(TargetView candidate, int mode) {
          return true;
        }

        @Override
        public TargetView defaultTarget() {
          return null;
        }
      };

  private static TargetingState buildingOnlyAttacker() {
    GridEntity owner = new GridEntity();
    TargetingState state = new TargetingState();
    state.setOwner(owner);
    state.setConfig(
        TargetingConfig.forUnit(1200, 7500, 750, 1500, 1000, true, false).toBuilder()
            .targetOnlyBuildings(true)
            .build());
    return state;
  }

  private static TargetView troop(boolean buildingTarget) {
    GridEntity entity = new GridEntity();
    // Every ordinary entity answers the flag the priority rule asks before it reads the column.
    entity.setTargetable(1);
    return new TargetView(
        entity,
        TargetingConfig.forUnit(1200, 5500, 500, 1200, 700, true, false).toBuilder()
            .buildingTarget(buildingTarget)
            .build());
  }

  @Test
  @DisplayName("a building-only attacker prefers a building")
  void prefersABuilding() {
    GridEntity entity = new GridEntity();
    entity.setBuilding(true);
    TargetView building =
        new TargetView(entity, TargetingConfig.tower("tower", 7500, 7500, 1000, 800, 0, true));

    assertThat(TargetPriority.priority(buildingOnlyAttacker(), building, NO_BUFFS))
        .isEqualTo(TargetPriority.PREFERRED);
  }

  @Test
  @DisplayName("the column that lets a troop be taken by a building-only attacker also ranks it")
  void prefersATroopMarkedAsABuildingTarget() {
    // One column does both jobs: the validator lets such a troop through, and the priority rule
    // ranks it level with a building, so the nearer of the two wins on distance.
    assertThat(TargetPriority.priority(buildingOnlyAttacker(), troop(true), NO_BUFFS))
        .isEqualTo(TargetPriority.PREFERRED);
  }

  @Test
  @DisplayName("an ordinary troop is not preferred by a building-only attacker")
  void doesNotPreferAnOrdinaryTroop() {
    assertThat(TargetPriority.priority(buildingOnlyAttacker(), troop(false), NO_BUFFS))
        .isEqualTo(TargetPriority.ORDINARY);
  }
}
