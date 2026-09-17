package org.crforge.core.pathfinding.target;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Storing a reference: what it does to the attack timing and what it announces. */
class ReferenceSetterTest {

  private TargetingState unit;
  private TargetView tower;
  private TargetingOutcome outcome;
  private RecordingQueries queries;

  /** Answers the setter pulls, recording the on-starting-attack action it runs. */
  private static final class RecordingQueries implements SelectionQueries {

    private int actionRuns;

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

    @Override
    public void onStartingAttack() {
      actionRuns++;
    }
  }

  @BeforeEach
  void setUp() {
    GridEntity owner = new GridEntity();
    owner.setSide(0);
    owner.setX(3500);
    owner.setY(24_000);
    owner.setCollisionRadius(500);
    owner.setState(GridEntityState.MOVING);

    unit = new TargetingState();
    unit.setOwner(owner);
    unit.setConfig(
        TargetingConfig.forUnit(1200, 5500, 500, 1200, 700, true, false).toBuilder()
            .hasOnStartingAttackAction(true)
            .build());

    GridEntity towerEntity = new GridEntity();
    towerEntity.setSide(1);
    towerEntity.setX(3500);
    towerEntity.setY(25_500);
    towerEntity.setCollisionRadius(1000);
    towerEntity.setBuilding(true);
    tower =
        new TargetView(
            towerEntity, TargetingConfig.tower("PrincessTower", 7500, 7500, 1000, 800, 0, true));

    outcome = new TargetingOutcome();
    queries = new RecordingQueries();
  }

  private void store(TargetView reference) {
    ReferenceSetter.setReference(unit, reference, false, true, false, queries, outcome);
  }

  @Test
  @DisplayName("a unit part way through an attack runs its action again for a target in range")
  void theActionRunsAgainForATargetAlreadyInRange() {
    unit.setAttackTimerMs(400);

    store(tower);

    assertThat(queries.actionRuns).isEqualTo(1);
    assertThat(unit.getAttackTimerMs()).as("the timing is left alone").isEqualTo(400);
  }

  @Test
  @DisplayName("a unit that has not started an attack yet runs no action")
  void noActionBeforeTheAttackHasStarted() {
    unit.setAttackTimerMs(0);

    store(tower);

    assertThat(queries.actionRuns).isZero();
  }

  @Test
  @DisplayName("a unit whose character carries no action runs none")
  void noActionWithoutAnActionColumn() {
    unit.setConfig(unit.getConfig().toBuilder().hasOnStartingAttackAction(false).build());
    unit.setAttackTimerMs(400);

    store(tower);

    assertThat(queries.actionRuns).isZero();
  }

  @Test
  @DisplayName("a unit that winds up again after retargeting clears its attack instead")
  void theWindUpColumnClearsTheAttackInstead() {
    unit.setConfig(unit.getConfig().toBuilder().loadAfterRetarget(true).build());
    unit.setAttackTimerMs(400);

    store(tower);

    assertThat(queries.actionRuns).isZero();
    assertThat(unit.getAttackTimerMs()).isZero();
  }
}
