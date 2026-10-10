package org.crforge.core.pathfinding.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.pathfinding.GridEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Bucket sizing, membership and the candidate query of the per-tick spatial index. */
class SpatialIndexTest {

  private static final int ARENA_CELLS_WIDE = 36;
  private static final int ARENA_CELLS_HIGH = 64;

  private GridEntity kingTop;
  private GridEntity princessTopLeft;
  private GridEntity princessTopRight;
  private GridEntity kingBottom;
  private GridEntity princessBottomLeft;
  private GridEntity princessBottomRight;
  private GridEntity unit;
  private SpatialIndex index;

  /**
   * A tower for the ordering tests. Only the two kings are flagged as crown towers here, so that
   * the list the queries answer mixes flagged and unflagged towers; in a battle every tower is one.
   */
  private static GridEntity tower(String name, int id, int side, int x, int y, boolean crown) {
    GridEntity e = new GridEntity();
    e.setName(name);
    e.setId(id);
    e.setSide(side);
    e.setX(x);
    e.setY(y);
    e.setCollisionRadius(crown ? 1400 : 1000);
    e.setCrownTower(crown);
    e.setBuilding(true);
    return e;
  }

  @BeforeEach
  void setUp() {
    kingTop = tower("KingTower_0_0", 1, 0, 9000, 3000, true);
    princessTopLeft = tower("PrincessTower_0_1", 2, 0, 3500, 6500, false);
    princessTopRight = tower("PrincessTower_0_2", 3, 0, 14500, 6500, false);
    kingBottom = tower("KingTower_1_0", 4, 1, 9000, 29000, true);
    princessBottomLeft = tower("PrincessTower_1_1", 5, 1, 3500, 25500, false);
    princessBottomRight = tower("PrincessTower_1_2", 6, 1, 14500, 25500, false);

    unit = new GridEntity();
    unit.setName("owner");
    unit.setId(7);
    unit.setSide(0);
    unit.setX(3500);
    unit.setY(10000);
    unit.setCollisionRadius(500);
    unit.setMovementActive(true);

    index = new SpatialIndex(ARENA_CELLS_WIDE, ARENA_CELLS_HIGH);
    index.rebuild(
        List.of(
            kingTop,
            princessTopLeft,
            princessTopRight,
            kingBottom,
            princessBottomLeft,
            princessBottomRight,
            unit));
  }

  @Test
  @DisplayName("a negative radius still finds an entity whose own radius more than covers it")
  void aNegativeRadiusIsNotAnEmptyAnswer() {
    // The unit's own collision radius is 500, so a radius of -100 still leaves 400 units of reach
    // and the circle test accepts the unit standing at the query's centre.
    List<GridEntity> found =
        index.query(new SpatialQuery(3500, 10_000, -100, 0, false, false, 0, -1));

    assertThat(found).containsExactly(unit);
    index.release(found);

    // The same radius 450 units away is beyond that reach, so the circle test refuses it. Both
    // queries scan the same bucket, so it is the circle test deciding and not the bucket guard.
    List<GridEntity> none =
        index.query(new SpatialQuery(3950, 10_000, -100, 0, false, false, 0, -1));

    assertThat(none).isEmpty();
  }

  @Test
  @DisplayName(
      "the centre query takes a centre strictly within the radius, adding no collision radius and"
          + " testing a building like anything else")
  void centreQueryMeasuresCentreToCentre() {
    // The right bottom princess tower stands at (14500, 25500): 4001 from the point below, past a
    // radius of 4000 however wide the tower is, and exactly 4000 from the second point.
    List<GridEntity> beyond = index.centreQuery(14500, 21499, 4000, e -> true);
    assertThat(beyond).doesNotContain(princessBottomRight);
    index.release(beyond);
    List<GridEntity> onTheEdge = index.centreQuery(14500, 21500, 4000, e -> true);
    assertThat(onTheEdge).as("strictly within").doesNotContain(princessBottomRight);
    index.release(onTheEdge);
    List<GridEntity> inside = index.centreQuery(14500, 21501, 4000, e -> true);
    assertThat(inside).containsExactly(princessBottomRight);
    index.release(inside);
    List<GridEntity> filtered = index.centreQuery(14500, 21501, 4000, e -> false);
    assertThat(filtered).as("the filter refuses it").isEmpty();
    index.release(filtered);
  }

  @Test
  @DisplayName("the standard arena is covered by 18 by 32 buckets of 1024 units")
  void dimensionsOfTheStandardArena() {
    SpatialIndex.Dimensions dimensions =
        SpatialIndex.dimensions(ARENA_CELLS_WIDE, ARENA_CELLS_HIGH);

    assertThat(dimensions.wide()).isEqualTo(18);
    assertThat(dimensions.high()).isEqualTo(32);
  }

  @Test
  @DisplayName("the candidate query walks x outer and y inner and orders crown towers last")
  void candidateQueryOrder() {
    int radius = 2000 + 5500 + 500;

    List<GridEntity> found = index.query(SpatialQuery.targetCandidates(3500, 10000, radius));

    assertThat(found).containsExactly(princessTopLeft, unit, kingTop);
  }

  @Test
  @DisplayName(
      "a candidate is accepted when its own collision radius reaches into the query circle")
  void collisionRadiusExtendsTheReach() {
    // The left top princess tower stands 3500 units away with a collision radius of 1000, so a
    // query radius above 2500 reaches its edge while its centre stays outside.
    assertThat(index.query(SpatialQuery.targetCandidates(3500, 10000, 2600)))
        .contains(princessTopLeft);
    assertThat(index.query(SpatialQuery.targetCandidates(3500, 10000, 2400)))
        .doesNotContain(princessTopLeft);
  }

  @Test
  @DisplayName("an entity without a collision radius is never indexed")
  void radiusBelowOneIsNotIndexed() {
    GridEntity ghost = new GridEntity();
    ghost.setName("ghost");
    ghost.setId(8);
    ghost.setX(3500);
    ghost.setY(10000);
    ghost.setCollisionRadius(0);
    index.rebuild(List.of(ghost, unit));

    assertThat(index.query(SpatialQuery.targetCandidates(3500, 10000, 8000))).containsExactly(unit);
  }

  @Test
  @DisplayName("the team exclusion drops every entity whose team matches the argument")
  void teamExclusionDropsMatchingEntities() {
    SpatialQuery excludeTopSide =
        new SpatialQuery(3500, 10000, 8000, 0, true, false, 0, SpatialIndex.team(unit));

    assertThat(index.query(excludeTopSide)).isEmpty();
  }

  @Test
  @DisplayName("the type mask keeps only the entity types whose bit is set")
  void typeMaskFiltersByType() {
    unit.setType(3);
    SpatialQuery onlyTypeThree = new SpatialQuery(3500, 10000, 8000, 0, true, false, 1 << 3, -1);

    assertThat(index.query(onlyTypeThree)).containsExactly(unit);
  }

  @Test
  @DisplayName("an inverted bucket range answers with no candidates")
  void negativeRadiusAnswersEmpty() {
    assertThat(index.query(SpatialQuery.targetCandidates(3500, 10000, -2000))).isEmpty();
  }

  @Test
  @DisplayName("the whole-list query returns every indexed entity once with crown towers last")
  void listQueryOrdersCrownTowersLast() {
    List<GridEntity> all = index.listQuery(true);

    assertThat(all).hasSize(7);
    assertThat(all.subList(5, 7)).containsExactlyInAnyOrder(kingTop, kingBottom);
    assertThat(all.subList(0, 5))
        .containsExactlyInAnyOrder(
            princessTopLeft, princessTopRight, princessBottomLeft, princessBottomRight, unit);
  }

  @Test
  @DisplayName("clearing the index empties every bucket")
  void clearEmptiesTheIndex() {
    index.clear();

    assertThat(index.query(SpatialQuery.targetCandidates(3500, 10000, 8000))).isEmpty();
  }

  @Test
  @DisplayName("a query without a free result list answers null and a release returns one")
  void resultListPoolIsBounded() {
    for (int i = 0; i < SpatialIndex.RESULT_LIST_POOL_SIZE; i++) {
      assertThat(index.query(SpatialQuery.targetCandidates(3500, 10000, 8000))).isNotNull();
    }
    assertThat(index.query(SpatialQuery.targetCandidates(3500, 10000, 8000))).isNull();

    index.release(List.of());

    assertThat(index.query(SpatialQuery.targetCandidates(3500, 10000, 8000))).isNotNull();
  }

  @Test
  @DisplayName(
      "the segment query visits the buckets over the segment's widened box x outer and y inner,"
          + " tests each entity once, accepted or not, and answers null with no free list")
  void segmentQueryTestsEachEntityOnce() {
    List<String> tested = new ArrayList<>();
    // The segment from the top left princess tower to the unit, widened by 2000: the tower's
    // square spans several buckets, and the unit is rejected.
    List<GridEntity> found =
        index.segmentQuery(
            3500,
            6500,
            3500,
            10000,
            2000,
            entity -> {
              tested.add(entity.getName());
              return entity.isBuilding();
            });

    assertThat(found).containsExactly(princessTopLeft);
    assertThat(tested).containsExactly("PrincessTower_0_1", "owner");

    for (int i = 1; i < SpatialIndex.RESULT_LIST_POOL_SIZE; i++) {
      assertThat(index.segmentQuery(0, 0, 0, 0, 0, entity -> true)).isNotNull();
    }
    assertThat(index.segmentQuery(0, 0, 0, 0, 0, entity -> true)).isNull();
  }

  @Test
  @DisplayName(
      "the box query tests a building by its square and anything else by its circle, after the"
          + " filter, each once, from a list of its own")
  void theBoxQuery() {
    // The box is 1200 wide and 2000 high about (3500, 8000): its lower edge at 7000 meets the
    // tower's square, which reaches 7500, and its upper edge at 9000 stops 1000 short of the
    // unit, whose circle reaches 500.
    assertThat(index.boxQuery(3500, 8000, 600, 1000, entity -> true))
        .containsExactly(princessTopLeft);
    assertThat(index.boxQuery(3500, 8000, 600, 1000, entity -> !entity.isBuilding())).isEmpty();
    // A box over the corner of the tower's square, 1273 from its centre: the square overlaps it,
    // the circle of 1000 would not.
    assertThat(index.boxQuery(4700, 7700, 300, 300, entity -> true))
        .containsExactly(princessTopLeft);

    // 400 from the upper edge, the unit's circle meets the box; it stands in several buckets and
    // is answered once.
    unit.setY(9400);
    index.rebuild(List.of(princessTopLeft, unit));
    assertThat(index.boxQuery(3500, 8000, 600, 1000, entity -> true))
        .containsExactly(princessTopLeft, unit);
    assertThat(index.boxQuery(3500, 8000, 600, 1000, entity -> entity == unit))
        .containsExactly(unit);

    // The pool of result lists is not drawn on.
    for (int i = 0; i < SpatialIndex.RESULT_LIST_POOL_SIZE + 1; i++) {
      assertThat(index.boxQuery(3500, 8000, 600, 1000, entity -> true)).isNotNull();
    }
    assertThat(index.query(SpatialQuery.targetCandidates(3500, 8000, 100))).isNotNull();
  }

  @Test
  @DisplayName(
      "a rebuild answers only the entities of the new tick, also where an old one stood, and a"
          + " clear leaves nothing")
  void aRebuildForgetsTheTickBefore() {
    // The tick after setUp's: the unit has gone and another stands far from where it was.
    GridEntity other = new GridEntity();
    other.setName("other");
    other.setId(8);
    other.setSide(1);
    other.setX(15_000);
    other.setY(15_000);
    other.setCollisionRadius(500);
    other.setMovementActive(true);
    index.rebuild(List.of(kingTop, other));

    List<GridEntity> whereItStood =
        index.query(new SpatialQuery(3500, 10_000, 1000, 0, false, false, 0, -1));
    assertThat(whereItStood).as("nothing is left where the unit stood").isEmpty();
    index.release(whereItStood);
    List<GridEntity> everything = index.listQuery(false);
    assertThat(everything).containsExactly(kingTop, other);
    index.release(everything);

    index.clear();
    assertThat(index.isPopulated()).isFalse();
    List<GridEntity> none = index.listQuery(false);
    assertThat(none).isEmpty();
    index.release(none);
  }
}
