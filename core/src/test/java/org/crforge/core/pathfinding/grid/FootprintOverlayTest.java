package org.crforge.core.pathfinding.grid;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.pathfinding.GridEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Stamping building footprints into the routing cost overlay. */
class FootprintOverlayTest {

  private CellGrid grid;

  @BeforeEach
  void setUp() {
    grid = new CellGrid(TileMap.standard1v1(), true, 100);
  }

  private static GridEntity entity(int id, int side, int x, int y, int radius, boolean occludes) {
    GridEntity entity = new GridEntity();
    entity.setId(id);
    entity.setSide(side);
    entity.setX(x);
    entity.setY(y);
    entity.setCollisionRadius(radius);
    entity.setOccludes(occludes);
    return entity;
  }

  @Test
  void stampsTheSixCrownTowers() {
    List<Integer> stamped = FootprintOverlay.buildOverlay(grid, StandardTowers.entities());

    assertThat(stamped)
        .containsExactly(
            FootprintOverlay.packFootprint(15, 20, 3, 8),
            FootprintOverlay.packFootprint(5, 8, 11, 14),
            FootprintOverlay.packFootprint(27, 30, 11, 14),
            FootprintOverlay.packFootprint(15, 20, 55, 60),
            FootprintOverlay.packFootprint(5, 8, 49, 52),
            FootprintOverlay.packFootprint(27, 30, 49, 52));
    assertThat(grid.getFootprints()).isEqualTo(stamped);

    int stampedCells = 0;
    for (int value : grid.getCurrent()) {
      if (value != 0) {
        stampedCells++;
        assertThat(value).isEqualTo(100);
      }
    }
    assertThat(stampedCells).isEqualTo(136);
    assertThat(grid.getHash()).containsExactly(74, 95);
    assertThat(grid.getChanged()).containsExactly(1, 1);
    assertThat(grid.getActive()).isEqualTo(1);
  }

  @Test
  void unpacksAFootprintIntoItsCellBounds() {
    int packed = FootprintOverlay.packFootprint(5, 8, 11, 14);

    assertThat(FootprintOverlay.footprintFirstCol(packed)).isEqualTo(5);
    assertThat(FootprintOverlay.footprintLastCol(packed)).isEqualTo(8);
    assertThat(FootprintOverlay.footprintFirstRow(packed)).isEqualTo(11);
    assertThat(FootprintOverlay.footprintLastRow(packed)).isEqualTo(14);
  }

  @Test
  void aSecondBuildWithTheSameOccludersClearsTheChangeFlags() {
    FootprintOverlay.buildOverlay(grid, StandardTowers.entities());
    grid.swap();
    FootprintOverlay.buildOverlay(grid, StandardTowers.entities());

    assertThat(grid.getHash()).containsExactly(74, 95);
    assertThat(grid.getChanged()).containsExactly(0, 0);
  }

  @Test
  void losingAnOccluderRaisesOnlyThatSidesChangeFlag() {
    List<GridEntity> towers = StandardTowers.entities();
    FootprintOverlay.buildOverlay(grid, towers);
    grid.swap();
    towers.removeIf(tower -> tower.getId() == 2);
    FootprintOverlay.buildOverlay(grid, towers);

    assertThat(grid.getHash()).containsExactly(32, 95);
    assertThat(grid.getChanged()).containsExactly(1, 0);
  }

  @Test
  void skipsEntitiesOfAnotherVirtualTypeOrThatDoNotOcclude() {
    GridEntity wrongType = entity(1, 0, 2500, 2500, 500, true);
    wrongType.setType(4);
    GridEntity transparent = entity(2, 0, 2500, 2500, 500, false);
    GridEntity otherSide = entity(3, 7, 2500, 2500, 500, true);

    List<Integer> stamped =
        FootprintOverlay.buildOverlay(grid, List.of(wrongType, transparent, otherSide));

    assertThat(stamped).containsExactly(FootprintOverlay.packFootprint(4, 5, 4, 5));
    assertThat(grid.getHash()).containsExactly(0, 0);
    assertThat(grid.getChanged()).containsExactly(0, 0);
    assertThat(countStamped()).isEqualTo(4);
  }

  @Test
  void marksTheOverlayActiveEvenWithNothingToStamp() {
    List<Integer> stamped = FootprintOverlay.buildOverlay(grid, List.of());

    assertThat(stamped).isEmpty();
    assertThat(grid.getActive()).isEqualTo(1);
    assertThat(grid.getHash()).containsExactly(0, 0);
    assertThat(grid.getChanged()).containsExactly(0, 0);
  }

  @Test
  void aDisabledGridStampsNothingAndStaysInactive() {
    CellGrid disabled = new CellGrid(TileMap.standard1v1(), false, 100);
    disabled.setHash(new int[] {3, 4});

    List<Integer> stamped =
        FootprintOverlay.buildOverlay(disabled, List.of(entity(1, 0, 2500, 2500, 500, true)));

    assertThat(stamped).isEmpty();
    assertThat(disabled.getFootprints()).isEmpty();
    assertThat(disabled.getHash()).containsExactly(0, 0);
    assertThat(disabled.getChanged()).containsExactly(1, 1);
    assertThat(disabled.getActive()).isZero();
    assertThat(disabled.getCurrent()).containsOnly(0);
  }

  @Test
  void anchorsTheBoxOnTheCellEdgeAtOrAfterThePosition() {
    CellGrid small = new CellGrid(new TileMap(18, 32, new int[18 * 32]), true, 100);

    assertThat(FootprintOverlay.rasterize(small, 2500, 3500, 500, 500, 100))
        .isEqualTo(FootprintOverlay.packFootprint(4, 5, 6, 7));
    small.setCurrent(new int[18 * 32]);
    assertThat(FootprintOverlay.rasterize(small, 2600, 3500, 500, 500, 100))
        .isEqualTo(FootprintOverlay.packFootprint(5, 6, 6, 7));
    small.setCurrent(new int[18 * 32]);
    assertThat(FootprintOverlay.rasterize(small, 2500, 3500, 750, 750, 100))
        .isEqualTo(FootprintOverlay.packFootprint(3, 6, 5, 8));
  }

  @Test
  void stampsNothingWhenTheBoxLeavesTheGrid() {
    CellGrid small = new CellGrid(new TileMap(18, 32, new int[18 * 32]), true, 100);

    assertThat(FootprintOverlay.rasterize(small, 100, 3500, 750, 750, 100)).isNull();
    assertThat(FootprintOverlay.rasterize(small, 8900, 3500, 500, 500, 100)).isNull();
    assertThat(small.getFootprints()).isEmpty();
    assertThat(small.getCurrent()).containsOnly(0);
  }

  @Test
  void aBoxThatStartsExactlyAtTheEdgeStillStamps() {
    CellGrid small = new CellGrid(new TileMap(18, 32, new int[18 * 32]), true, 100);

    assertThat(FootprintOverlay.rasterize(small, 200, 3500, 500, 500, 100))
        .isEqualTo(FootprintOverlay.packFootprint(0, 1, 6, 7));
    int stamped = 0;
    for (int value : small.getCurrent()) {
      if (value != 0) {
        stamped++;
      }
    }
    assertThat(stamped).isEqualTo(4);
  }

  @Test
  void keepsTheDearerValueWhereTwoFootprintsOverlap() {
    CellGrid small = new CellGrid(new TileMap(18, 32, new int[18 * 32]), true, 100);
    small.getCurrent()[6 * 18 + 4] = 250;

    FootprintOverlay.rasterize(small, 2500, 3500, 500, 500, 100);

    assertThat(small.getCurrent()[6 * 18 + 4]).isEqualTo(250);
    assertThat(small.getCurrent()[6 * 18 + 5]).isEqualTo(100);
  }

  private int countStamped() {
    int stamped = 0;
    for (int value : grid.getCurrent()) {
      if (value != 0) {
        stamped++;
      }
    }
    return stamped;
  }
}
