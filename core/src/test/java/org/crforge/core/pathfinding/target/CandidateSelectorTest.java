package org.crforge.core.pathfinding.target;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which of the entities around a unit it takes as its target. */
class CandidateSelectorTest {

  private TargetingState knight;
  private TargetingOutcome outcome;
  private TargetView defaultTower;
  private final List<TargetView> around = new ArrayList<>();

  /** A candidate list handed straight to the selector, with a fixed default target. */
  private final class Queries implements SelectionQueries {
    private int released;

    @Override
    public List<TargetView> candidates(int x, int y, int radius) {
      return around;
    }

    @Override
    public List<TargetView> allCandidates() {
      return around;
    }

    @Override
    public void releaseCandidates(List<TargetView> candidates) {
      released++;
    }

    @Override
    public boolean validate(TargetView candidate, int mode) {
      return ReferenceValidator.validate(knight, candidate, mode, ValidatorQueries.standard1v1());
    }

    @Override
    public TargetView defaultTarget() {
      return defaultTower;
    }
  }

  private Queries queries;

  private static TargetView troop(String name, int id, int side, int x, int y, int radius) {
    GridEntity e = new GridEntity();
    e.setName(name);
    e.setId(id);
    e.setSide(side);
    e.setX(x);
    e.setY(y);
    e.setCollisionRadius(radius);
    e.setTargetable(1);
    return new TargetView(e, TargetingConfig.forUnit(500, 5000, radius, 1000, 500, true, false));
  }

  private static TargetView tower(String name, int id, int x, int y, boolean king) {
    GridEntity e = new GridEntity();
    e.setName(name);
    e.setId(id);
    e.setSide(1);
    e.setX(x);
    e.setY(y);
    e.setCollisionRadius(king ? 1400 : 1000);
    e.setBuilding(true);
    e.setKing(king);
    e.setKingCandidate(1);
    e.setTargetable(1);
    return new TargetView(
        e,
        king
            ? TargetingConfig.tower("KingTower", 7000, 7000, 1400, 1000, 500, false)
            : TargetingConfig.tower("PrincessTower", 7500, 7500, 1000, 800, 0, true));
  }

  @BeforeEach
  void setUp() {
    GridEntity unit = new GridEntity();
    unit.setName("owner");
    unit.setId(7);
    unit.setSide(0);
    unit.setX(3500);
    unit.setY(10000);
    unit.setCollisionRadius(500);
    unit.setState(GridEntityState.MOVING);
    unit.setTargetable(1);

    knight = new TargetingState();
    knight.setOwner(unit);
    knight.setConfig(TargetingConfig.forUnit(1200, 5500, 500, 1200, 700, true, false));
    knight.setMovementComponentActive(true);

    outcome = new TargetingOutcome();
    queries = new Queries();
    defaultTower = tower("PrincessTower_1_1", 5, 3500, 25500, false);
    around.clear();
  }

  @Test
  @DisplayName("with nothing in reach the unit takes the default tower and asks for a route")
  void emptyReachFallsBackToTheDefault() {
    CandidateSelector.select(knight, false, false, queries, outcome);

    assertThat(knight.getReference()).isSameAs(defaultTower);
    assertThat(outcome.isRoutePreparationRequested()).isTrue();
    assertThat(queries.released).isEqualTo(1);
  }

  @Test
  @DisplayName("an enemy troop within sight beats the default tower")
  void aTroopInSightWins() {
    TargetView enemy = troop("Archer", 9, 1, 3500, 13000, 500);
    around.add(enemy);

    CandidateSelector.select(knight, false, false, queries, outcome);

    assertThat(knight.getReference()).isSameAs(enemy);
  }

  @Test
  @DisplayName("the nearer of two candidates wins")
  void theNearerCandidateWins() {
    TargetView far = troop("Archer", 9, 1, 3500, 14000, 500);
    TargetView near = troop("Goblin", 10, 1, 3500, 12000, 500);
    around.add(far);
    around.add(near);

    CandidateSelector.select(knight, false, false, queries, outcome);

    assertThat(knight.getReference()).isSameAs(near);
  }

  @Test
  @DisplayName("a king tower is noticed 2000 units beyond the ordinary reach")
  void crownTowersAreNoticedFurtherAway() {
    knight.getOwner().setX(9000);
    knight.getOwner().setY(20000);
    TargetView king = tower("KingTower_1_0", 4, 9000, 29000, true);
    around.add(king);
    defaultTower = null;

    // 9000 units away, which is beyond the 7400 the tower's radius and the sight range give but
    // inside the 9400 the crown-tower bonus adds.
    CandidateSelector.select(knight, false, false, queries, outcome);

    assertThat(knight.getReference()).isSameAs(king);
  }

  @Test
  @DisplayName("a friendly entity and the unit itself are never taken")
  void friendlyCandidatesAreSkipped() {
    around.add(troop("Friend", 9, 0, 3500, 11000, 500));
    around.add(new TargetView(knight.getOwner(), knight.getConfig()));

    CandidateSelector.select(knight, false, false, queries, outcome);

    assertThat(knight.getReference()).isSameAs(defaultTower);
  }

  @Test
  @DisplayName("a locked unit, a dashing unit and a jumping unit choose nothing")
  void theThreeEarlyExits() {
    TargetView enemy = troop("Archer", 9, 1, 3500, 13000, 500);
    around.add(enemy);

    knight.getOwner().setFlags(EntityFlags.LOCK_TARGET);
    CandidateSelector.select(knight, false, false, queries, outcome);
    assertThat(knight.getReference()).isNull();

    knight.getOwner().setFlags(0);
    knight.getOwner().setState(GridEntityState.DASHING);
    CandidateSelector.select(knight, false, false, queries, outcome);
    assertThat(knight.getReference()).isNull();

    knight.getOwner().setState(GridEntityState.JUMPING);
    CandidateSelector.select(knight, false, false, queries, outcome);
    assertThat(knight.getReference()).isNull();

    knight.getOwner().setState(GridEntityState.MOVING);
    CandidateSelector.select(knight, false, false, queries, outcome);
    assertThat(knight.getReference()).isSameAs(enemy);
  }

  @Test
  @DisplayName("a buff that locks the target stops the selection once a target is held")
  void aLockingBuffKeepsTheReference() {
    TargetView held = troop("Archer", 9, 1, 3500, 13000, 500);
    knight.setReference(held);
    knight.setTargetLockingBuffs(1);
    around.add(troop("Goblin", 10, 1, 3500, 10500, 500));

    CandidateSelector.select(knight, false, false, queries, outcome);

    assertThat(knight.getReference()).isSameAs(held);
  }

  @Test
  @DisplayName("a building-only attacker prefers a building over a nearer troop")
  void buildingAttackersPreferBuildings() {
    knight.setConfig(knight.getConfig().toBuilder().targetOnlyBuildings(false).build());
    TargetView building = tower("Cannon", 11, 3500, 14000, false);
    TargetView nearer = troop("Goblin", 10, 1, 3500, 11000, 500);
    around.add(nearer);
    around.add(building);

    // Without the column the nearer troop wins on distance.
    CandidateSelector.select(knight, false, false, queries, outcome);
    assertThat(knight.getReference()).isSameAs(nearer);

    knight.setReference(null);
    knight.setConfig(knight.getConfig().toBuilder().targetOnlyBuildings(true).build());
    CandidateSelector.select(knight, false, false, queries, outcome);
    assertThat(knight.getReference()).isSameAs(building);
  }

  @Test
  @DisplayName("a hidden candidate is skipped while its countdown runs")
  void hiddenCandidatesAreSkipped() {
    TargetView enemy = troop("Archer", 9, 1, 3500, 13000, 500);
    enemy.setHiddenCountdownMs(500);
    around.add(enemy);

    CandidateSelector.select(knight, false, false, queries, outcome);

    assertThat(knight.getReference()).isSameAs(defaultTower);
  }

  @Test
  @DisplayName("the unit keeps the target it has when its route leads away from it")
  void theRetentionGateKeepsTheReference() {
    TargetView held = troop("Archer", 9, 1, 3500, 30000, 500);
    knight.setReference(held);
    knight.setRouteLeadsAway(true);

    CandidateSelector.select(knight, false, false, queries, outcome);

    assertThat(knight.getReference()).isSameAs(held);
  }

  @Test
  @DisplayName("the sight range and the collision radius set the query radius")
  void theQueryRadiusIsTheSightReach() {
    List<Integer> radii = new ArrayList<>();
    SelectionQueries recording =
        new SelectionQueries() {
          @Override
          public List<TargetView> candidates(int x, int y, int radius) {
            radii.add(radius);
            return List.of();
          }

          @Override
          public List<TargetView> allCandidates() {
            return List.of();
          }

          @Override
          public boolean validate(TargetView candidate, int mode) {
            return false;
          }

          @Override
          public TargetView defaultTarget() {
            return null;
          }
        };

    CandidateSelector.select(knight, false, false, recording, outcome);

    assertThat(radii).containsExactly(2000 + 5500 + 500);
  }
}
