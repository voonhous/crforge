package org.crforge.core.pathfinding.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.target.TargetView;
import org.crforge.core.pathfinding.target.TargetingConfig;
import org.crforge.core.pathfinding.target.TargetingState;
import org.crforge.core.pathfinding.target.ValidatorQueries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The area damage's collection and sharing rules, one at a time. The Wizard run holds the whole
 * path with a single victim; these hold the rules that run does not reach.
 */
class AreaDamageTest {

  /** Kind of a projectile, the area's owner here. */
  private static final int PROJECTILE = 4;

  private TargetingState owner;

  /** What each victim was dealt, in the order it was dealt. */
  private final Map<String, Integer> dealt = new LinkedHashMap<>();

  private final AreaDamage.Queries queries =
      (victim, damage, hitId) -> {
        dealt.put(victim.name(), damage);
        return new DamageResult(true, damage, false);
      };

  @BeforeEach
  void setUp() {
    GridEntity view = new GridEntity();
    view.setName("proj");
    view.setType(PROJECTILE);
    view.setSide(0);
    owner = new TargetingState();
    owner.setOwner(view);
  }

  private static TargetView unit(String name, int side, int x, int y) {
    GridEntity e = new GridEntity();
    e.setName(name);
    e.setSide(side);
    e.setX(x);
    e.setY(y);
    e.setCollisionRadius(500);
    e.setTargetable(1);
    TargetView view =
        new TargetView(e, TargetingConfig.forUnit(1200, 5500, 500, 1200, 700, true, false));
    view.setHitPointsPresent(true);
    return view;
  }

  private static TargetView tower(String name, int side, int x, int y, boolean king) {
    GridEntity e = new GridEntity();
    e.setName(name);
    e.setSide(side);
    e.setX(x);
    e.setY(y);
    e.setCollisionRadius(1000);
    e.setBuilding(true);
    e.setCrownTower(true);
    e.setKingCandidate(king ? 1 : 0);
    e.setTargetable(1);
    TargetView view =
        new TargetView(e, TargetingConfig.tower("Tower", 7000, 7000, 1000, 1000, 0, !king));
    view.setHitPointsPresent(true);
    view.setCrownTowerTarget(true);
    return view;
  }

  private static AreaDamage.Area area(int radius, int limit, boolean ownSide, boolean split) {
    return new AreaDamage.Area(0, 0, radius, 100, 30, 1, limit, ownSide, true, true, split);
  }

  private List<String> damage(AreaDamage.Area area, TargetView... entities) {
    List<String> names = new ArrayList<>();
    for (TargetView victim :
        AreaDamage.damage(
            owner, List.of(entities), area, ValidatorQueries.standard1v1(), queries)) {
      names.add(victim.name());
    }
    return names;
  }

  @Test
  @DisplayName("the owner's own side is spared unless the area may hit it")
  void theOwnSideIsSparedUnlessAsked() {
    TargetView friend = unit("friend", 0, 100, 0);
    TargetView enemy = unit("enemy", 1, -100, 0);

    assertThat(damage(area(1500, 0, false, false), friend, enemy)).containsExactly("enemy");
    assertThat(damage(area(1500, 0, true, false), friend, enemy))
        .containsExactly("friend", "enemy");
  }

  @Test
  @DisplayName("a unit is in the area while its centre is closer than the radius plus its own")
  void aUnitCountsItsOwnRadius() {
    // Radius 1500 plus the unit's 500: a centre at 1999 is inside, one at 2000 is not.
    assertThat(damage(area(1500, 0, false, false), unit("near", 1, 1999, 0))).hasSize(1);
    assertThat(damage(area(1500, 0, false, false), unit("far", 1, 2000, 0))).isEmpty();
  }

  @Test
  @DisplayName("a building is in the area when its square reaches strictly inside the radius")
  void aBuildingCountsItsSquare() {
    // The tower's square spans 1000 either side of its centre: its edge at 2499 is 1499 away.
    assertThat(damage(area(1500, 0, false, false), tower("in", 1, 2499, 0, false))).hasSize(1);
    assertThat(damage(area(1500, 0, false, false), tower("out", 1, 2500, 0, false))).isEmpty();
  }

  @Test
  @DisplayName("a crown tower takes the crown-tower damage, anything else the plain damage")
  void aCrownTowerTakesTheTowerDamage() {
    damage(area(3000, 0, false, false), unit("troop", 1, 0, 0), tower("tower", 1, 0, 0, false));

    assertThat(dealt).containsEntry("troop", 100).containsEntry("tower", 30);
  }

  @Test
  @DisplayName("one area takes at most one entity that fills a side's tower slot")
  void oneTowerSlotEntityPerArea() {
    assertThat(
            damage(
                area(3000, 0, false, false),
                tower("king", 1, 0, 0, true),
                tower("second", 1, 0, 0, true),
                tower("princess", 1, 0, 0, false)))
        .containsExactly("king", "princess");
  }

  @Test
  @DisplayName("the limit stops the collection, and a split shares the damage rounded up")
  void theLimitAndTheSplit() {
    TargetView a = unit("a", 1, 0, 0);
    TargetView b = unit("b", 1, 0, 0);
    TargetView c = unit("c", 1, 0, 0);

    assertThat(damage(area(1500, 2, false, false), a, b, c)).containsExactly("a", "b");

    dealt.clear();
    damage(area(1500, 0, false, true), a, b, c);
    // 100 over three is 33 and a third, rounded up to 34.
    assertThat(dealt.values()).containsExactly(34, 34, 34);
  }

  @Test
  @DisplayName("an area that does not reach the air leaves an air unit alone")
  void theAirGate() {
    TargetView flyer = unit("flyer", 1, 0, 0);
    flyer.getEntity().setAir(true);

    assertThat(
            damage(
                new AreaDamage.Area(0, 0, 1500, 100, 100, 1, 0, false, false, true, false),
                flyer,
                unit("walker", 1, 0, 0)))
        .containsExactly("walker");
  }
}
