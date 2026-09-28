package org.crforge.core.pathfinding.target;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.index.SpatialIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which extra target a hit that reaches several takes, as the lookup finds it. */
class MultiTargetLookupTest {

  private TargetingState wizard;
  private TargetView reference;
  private final List<TargetView> around = new ArrayList<>();

  /** A candidate list handed straight to the lookup, walked in the order it is listed. */
  private final class Queries implements MultiTargetLookup.Queries {
    private int released;

    @Override
    public List<TargetView> lookupCandidates(int x, int y, int radius) {
      return around;
    }

    @Override
    public List<TargetView> candidates(int x, int y, int radius) {
      throw new AssertionError("the lookup makes its own query");
    }

    @Override
    public List<TargetView> allCandidates() {
      throw new AssertionError("the lookup makes its own query");
    }

    @Override
    public void releaseCandidates(List<TargetView> candidates) {
      released++;
    }

    @Override
    public boolean validate(TargetView candidate, int mode) {
      return ReferenceValidator.validate(wizard, candidate, mode, ValidatorQueries.standard1v1());
    }

    @Override
    public TargetView defaultTarget() {
      throw new AssertionError("the lookup has no default");
    }
  }

  private Queries queries;

  private static TargetView troop(String name, int id, int x, int y) {
    GridEntity e = new GridEntity();
    e.setName(name);
    e.setId(id);
    e.setSide(1);
    e.setX(x);
    e.setY(y);
    e.setCollisionRadius(500);
    e.setTargetable(1);
    return new TargetView(e, TargetingConfig.forUnit(1200, 5500, 500, 1200, 700, true, false));
  }

  /** The candidate at a distance straight up the arena from the wizard. */
  private static TargetView troopAhead(String name, int id, int distance) {
    return troop(name, id, 3500, 10000 + distance);
  }

  @BeforeEach
  void setUp() {
    GridEntity unit = new GridEntity();
    unit.setName("wizard");
    unit.setId(7);
    unit.setSide(0);
    unit.setX(3500);
    unit.setY(10000);
    unit.setCollisionRadius(500);
    unit.setState(GridEntityState.ATTACKING);
    unit.setTargetable(1);

    // The Electro Wizard: Range 5000 and SightRange 5500, so the pick must lie within 5500 of
    // the wizard's edge - 6000 to a candidate's centre - while the reach runs to 6500.
    wizard = new TargetingState();
    wizard.setOwner(unit);
    wizard.setConfig(TargetingConfig.forUnit(5000, 5500, 500, 1800, 1200, true, true));
    reference = troop("reference", 20, 2000, 12000);
    wizard.setReference(reference);
    queries = new Queries();
    around.clear();
  }

  @Test
  @DisplayName("the nearest candidate other than the reference is the pick, and the query returns")
  void theNearestOtherCandidateIsThePick() {
    TargetView far = troopAhead("far", 21, 4500);
    TargetView near = troop("near", 22, 4500, 10000);
    around.addAll(List.of(reference, far, near));

    assertThat(MultiTargetLookup.lookup(wizard, 0, queries)).isSameAs(near);
    assertThat(queries.released).isEqualTo(1);
  }

  @Test
  @DisplayName("of two candidates at the same distance the earlier in the query's order stays")
  void theEarlierOfEqualsStays() {
    TargetView left = troop("left", 21, 2500, 10000);
    TargetView right = troop("right", 22, 4500, 10000);
    around.addAll(List.of(left, right));

    assertThat(MultiTargetLookup.lookup(wizard, 0, queries)).isSameAs(left);
  }

  @Test
  @DisplayName("a pick within the reach but beyond the attack range is not taken")
  void aPickBeyondTheAttackRangeIsNotTaken() {
    around.add(troopAhead("beyond", 21, 6300));

    assertThat(MultiTargetLookup.lookup(wizard, 0, queries)).isNull();
  }

  @Test
  @DisplayName("a candidate in the attack range but beyond the sight reach is skipped")
  void aCandidateBeyondTheReachIsSkipped() {
    // A sight shorter than the range: the reach is 500 + 3000 + 500 = 4000 to the centre.
    wizard.setConfig(wizard.getConfig().toBuilder().sightRange(3000).build());
    around.add(troopAhead("outside", 21, 4200));

    assertThat(MultiTargetLookup.lookup(wizard, 0, queries)).isNull();
  }

  @Test
  @DisplayName("a hidden candidate is skipped")
  void aHiddenCandidateIsSkipped() {
    TargetView hidden = troopAhead("hidden", 21, 2000);
    hidden.setHiddenCountdownMs(100);
    around.add(hidden);

    assertThat(MultiTargetLookup.lookup(wizard, 0, queries)).isNull();
  }

  @Test
  @DisplayName("a candidate clipped behind the wizard is skipped, though in the attack range")
  void aCandidateClippedBehindIsSkipped() {
    // The reach is 6500 and the clip 1000, so more than 5500 behind is clipped; 5800 is still
    // within the 6000 of the attack range.
    around.add(troopAhead("behind", 21, -5800));

    assertThat(MultiTargetLookup.lookup(wizard, 0, queries)).isNull();
  }

  @Test
  @DisplayName("a candidate inside the minimum range is skipped, so a farther one is the pick")
  void aCandidateInsideTheMinimumRangeIsSkipped() {
    // The loop's floor is 500 + 2000 - 500 = 2000 to the centre; the pick's own range test then
    // wants 500 + 2500 = 3000, which the near candidate would fail.
    wizard.setConfig(wizard.getConfig().toBuilder().minimumRange(2000).build());
    TargetView inside = troopAhead("inside", 21, 1800);
    TargetView outside = troopAhead("outside", 22, 4000);
    around.addAll(List.of(inside, outside));

    assertThat(MultiTargetLookup.lookup(wizard, 0, queries)).isSameAs(outside);
  }

  @Test
  @DisplayName("the chain's own query leaves a crown tower in its place, so it keeps a tie")
  void theQueryLeavesCrownTowersInPlace() {
    // A princess tower and a troop at the same distance either side of the wizard: the tower's
    // bucket comes first, and the selector's query would have moved it last.
    GridEntity tower = new GridEntity();
    tower.setName("PrincessTower_1_1");
    tower.setId(5);
    tower.setSide(1);
    tower.setX(1500);
    tower.setY(10000);
    tower.setCollisionRadius(1000);
    tower.setBuilding(true);
    tower.setCrownTower(true);
    tower.setTargetable(1);
    TargetView towerView =
        new TargetView(
            tower, TargetingConfig.tower("PrincessTower", 7500, 7500, 1000, 800, 0, true));
    TargetView troop = troop("troop", 21, 5500, 10000);
    SpatialIndex index = new SpatialIndex(36, 64);
    index.rebuild(List.of(tower, troop.getEntity(), wizard.getOwner()));
    SelectionChain chain = new SelectionChain(index, wizard, 64);
    chain.register(towerView);
    chain.register(troop);
    wizard.setReference(null);

    assertThat(chain.multiTarget(0, false)).isSameAs(towerView);
  }

  @Test
  @DisplayName("a second extra target skips the first's pick, and only that one")
  void aSecondExtraTargetSkipsTheFirstPick() {
    TargetView first = troopAhead("first", 21, 1000);
    TargetView second = troopAhead("second", 22, 2000);
    TargetView third = troopAhead("third", 23, 3000);
    around.addAll(List.of(first, second, third));

    assertThat(MultiTargetLookup.lookup(wizard, 0, queries)).isSameAs(first);
    assertThat(MultiTargetLookup.lookup(wizard, 1, queries)).isSameAs(second);
    assertThat(MultiTargetLookup.lookup(wizard, 2, queries))
        .as("index 2 skips index 1's pick alone, so the first is the pick again")
        .isSameAs(first);
  }
}
