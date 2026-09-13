package org.crforge.core.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.util.GameUnits.tiles;

import org.crforge.core.player.Team;
import org.junit.jupiter.api.Test;

class ArenaTest {

  @Test
  void shouldNotDeployOnBluePrincessTower() {
    Arena arena = Arena.standard();

    // Blue Left Princess Tower is around x=3, y=6 (indices 2-4, 5-7)
    // Testing center of tower
    assertThat(arena.isValidPlacement(tiles(3.5), tiles(6.5), Team.BLUE))
        .as("Should not be able to deploy on top of Princess Tower")
        .isFalse();
  }

  @Test
  void shouldNotDeployOnBlueCrownTower() {
    Arena arena = Arena.standard();

    // Blue Crown Tower is around x=9, y=3 (indices 7-10, 1-4)
    // Testing center of tower area
    assertThat(arena.isValidPlacement(tiles(9.0), tiles(3.0), Team.BLUE))
        .as("Should not be able to deploy on top of Crown Tower")
        .isFalse();
  }

  @Test
  void shouldNotDeployOnRedTowersAsRed() {
    Arena arena = Arena.standard();

    // Red Crown Tower (top side)
    assertThat(arena.isValidPlacement(tiles(9.0), tiles(29.0), Team.RED))
        .as("Red player should not deploy on their own Crown Tower")
        .isFalse();
  }

  @Test
  void shouldDeployNextToTower() {
    Arena arena = Arena.standard();

    // Just outside Blue Princess Tower range (x=5 is valid, tower ends at 4)
    assertThat(arena.isValidPlacement(tiles(5.5), tiles(6.5), Team.BLUE))
        .as("Should be able to deploy adjacent to towers")
        .isTrue();
  }

  @Test
  void freePrincessTowerTiles_convertsToBlueZone() {
    Arena arena = Arena.standard();

    // Blue left princess tower at (3.5, 6.5) -> center tile (3, 6), 3x3: x[2-4], y[5-7]
    // Verify they start as TOWER
    for (int x = 2; x <= 4; x++) {
      for (int y = 5; y <= 7; y++) {
        assertThat(arena.getTile(x, y).type())
            .as("Tile (%d,%d) should be TOWER before freeing", x, y)
            .isEqualTo(TileType.TOWER);
      }
    }

    arena.freePrincessTowerTiles(tiles(3.5), tiles(6.5), Team.BLUE);

    // Verify they are now BLUE_ZONE
    for (int x = 2; x <= 4; x++) {
      for (int y = 5; y <= 7; y++) {
        assertThat(arena.getTile(x, y).type())
            .as("Tile (%d,%d) should be BLUE_ZONE after freeing", x, y)
            .isEqualTo(TileType.BLUE_ZONE);
      }
    }
  }

  @Test
  void freePrincessTowerTiles_convertsToRedZone() {
    Arena arena = Arena.standard();

    // Red right princess tower at (14.5, 25.5) -> center tile (14, 25), 3x3: x[13-15], y[24-26]
    arena.freePrincessTowerTiles(tiles(14.5), tiles(25.5), Team.RED);

    for (int x = 13; x <= 15; x++) {
      for (int y = 24; y <= 26; y++) {
        assertThat(arena.getTile(x, y).type())
            .as("Tile (%d,%d) should be RED_ZONE after freeing", x, y)
            .isEqualTo(TileType.RED_ZONE);
      }
    }
  }

  @Test
  void freePrincessTowerTiles_enablesPlacement() {
    Arena arena = Arena.standard();

    // Blue left princess tower center
    assertThat(arena.isValidPlacement(tiles(3.5), tiles(6.5), Team.BLUE))
        .as("Cannot place on live princess tower")
        .isFalse();

    arena.freePrincessTowerTiles(tiles(3.5), tiles(6.5), Team.BLUE);

    assertThat(arena.isValidPlacement(tiles(3.5), tiles(6.5), Team.BLUE))
        .as("Should be able to place on destroyed princess tower footprint")
        .isTrue();
  }

  @Test
  void freePrincessTowerTiles_doesNotAffectCrownTower() {
    Arena arena = Arena.standard();

    // Free blue left princess tower
    arena.freePrincessTowerTiles(tiles(3.5), tiles(6.5), Team.BLUE);

    // Crown tower tiles (x[7-10], y[1-4]) should remain TOWER
    for (int x = 7; x <= 10; x++) {
      for (int y = 1; y <= 4; y++) {
        assertThat(arena.getTile(x, y).type())
            .as("Crown tower tile (%d,%d) should remain TOWER", x, y)
            .isEqualTo(TileType.TOWER);
      }
    }
  }

  @Test
  void freePrincessTowerTiles_doesNotAffectOtherPrincessTower() {
    Arena arena = Arena.standard();

    // Free only blue left princess tower
    arena.freePrincessTowerTiles(tiles(3.5), tiles(6.5), Team.BLUE);

    // Blue right princess tower tiles (x[13-15], y[5-7]) should remain TOWER
    for (int x = 13; x <= 15; x++) {
      for (int y = 5; y <= 7; y++) {
        assertThat(arena.getTile(x, y).type())
            .as("Other princess tower tile (%d,%d) should remain TOWER", x, y)
            .isEqualTo(TileType.TOWER);
      }
    }
  }

  @Test
  void openPocketZone_blueCanDeployInRedTerritoryAfterLeftPTDestroyed() {
    Arena arena = Arena.standard();

    // Before opening pocket, Blue cannot deploy in Red territory
    assertThat(arena.isValidPlacement(tiles(4.5), tiles(18.5), Team.BLUE))
        .as("Blue should not deploy in Red territory before pocket opens")
        .isFalse();

    // Open left-lane pocket for Blue (simulates destroying Red's left princess tower)
    arena.openPocketZone(Team.BLUE, true);

    // Now Blue can deploy in the pocket area: x[0-8], y[17-20]
    assertThat(arena.isValidPlacement(tiles(4.5), tiles(18.5), Team.BLUE))
        .as("Blue should deploy in left pocket after it opens")
        .isTrue();
    assertThat(arena.isValidPlacement(tiles(0.5), tiles(17.5), Team.BLUE))
        .as("Blue should deploy at pocket corner (0,17)")
        .isTrue();
    assertThat(arena.isValidPlacement(tiles(8.5), tiles(20.5), Team.BLUE))
        .as("Blue should deploy at pocket corner (8,20)")
        .isTrue();
  }

  @Test
  void openPocketZone_blueCannotDeployInOppositeUndestroyedLane() {
    Arena arena = Arena.standard();

    // Open left-lane pocket only
    arena.openPocketZone(Team.BLUE, true);

    // Right lane pocket (x[9-17], y[17-20]) should still be blocked for Blue
    assertThat(arena.isValidPlacement(tiles(14.5), tiles(18.5), Team.BLUE))
        .as("Blue should not deploy in right lane when only left pocket is open")
        .isFalse();
  }

  @Test
  void openPocketZone_redCanDeployInBlueTerritoryAfterRightPTDestroyed() {
    Arena arena = Arena.standard();

    // Before opening pocket, Red cannot deploy in Blue territory
    assertThat(arena.isValidPlacement(tiles(14.5), tiles(12.5), Team.RED))
        .as("Red should not deploy in Blue territory before pocket opens")
        .isFalse();

    // Open right-lane pocket for Red (simulates destroying Blue's right princess tower)
    arena.openPocketZone(Team.RED, false);

    // Now Red can deploy in the pocket area: x[9-17], y[11-14]
    assertThat(arena.isValidPlacement(tiles(14.5), tiles(12.5), Team.RED))
        .as("Red should deploy in right pocket after it opens")
        .isTrue();
    assertThat(arena.isValidPlacement(tiles(9.5), tiles(11.5), Team.RED))
        .as("Red should deploy at pocket corner (9,11)")
        .isTrue();
    assertThat(arena.isValidPlacement(tiles(17.5), tiles(14.5), Team.RED))
        .as("Red should deploy at pocket corner (17,14)")
        .isTrue();
  }

  @Test
  void openPocketZone_buildingPlacementWorksInPocket() {
    Arena arena = Arena.standard();
    arena.openPocketZone(Team.BLUE, true);

    // A building with radius 1.0 centered at (4.5, 18.5) covers tiles x[3-5], y[17-19]
    // All within the left pocket x[0-8], y[17-20]
    assertThat(arena.isValidBuildingPlacement(tiles(4.5), tiles(18.5), tiles(1.0), Team.BLUE))
        .as("Building should be placeable inside pocket zone")
        .isTrue();

    // A building at the pocket edge that extends outside should fail
    // Centered at (8.5, 18.5) with radius 1.0 covers tiles x[7-9], y[17-19]
    // x=9 is outside the left pocket (x[0-8])
    assertThat(arena.isValidBuildingPlacement(tiles(8.5), tiles(18.5), tiles(1.0), Team.BLUE))
        .as("Building straddling pocket boundary should not be placeable")
        .isFalse();
  }

  @Test
  void openPocketZone_pocketDoesNotAffectEnemyDeployInOwnZone() {
    Arena arena = Arena.standard();

    // Open left pocket for Blue (in Red territory y[17-20])
    arena.openPocketZone(Team.BLUE, true);

    // Red should still be able to deploy in their own territory (which includes the pocket area)
    assertThat(arena.isValidPlacement(tiles(4.5), tiles(18.5), Team.RED))
        .as("Red should still deploy in their own RED_ZONE tiles within the pocket area")
        .isTrue();
  }

  @Test
  void tileLookup_usesFloorDivisionForGameUnits() {
    Arena arena = Arena.standard();

    // Tile boundaries: 999 units is tile 0, 1000 units is tile 1
    assertThat(arena.getTileAt(999, 0)).isEqualTo(arena.getTile(0, 0));
    assertThat(arena.getTileAt(1000, 0)).isEqualTo(arena.getTile(1, 0));

    // A negative coordinate is outside the grid (floor division), not truncated into tile 0
    assertThat(arena.getTileAt(-1, tiles(5))).isNull();
    assertThat(arena.isInBounds(-1, tiles(5))).isFalse();

    // Upper edges are exclusive: 18,000 by 32,000 game units
    assertThat(arena.isInBounds(Arena.WIDTH_UNITS - 1, Arena.HEIGHT_UNITS - 1)).isTrue();
    assertThat(arena.isInBounds(Arena.WIDTH_UNITS, 0)).isFalse();
    assertThat(arena.isInBounds(0, Arena.HEIGHT_UNITS)).isFalse();
  }

  @Test
  void buildingFootprintOnTileBoundary_doesNotCoverNeighborTile() {
    Arena arena = Arena.standard();

    // Blue zone tile x=5..6 at y=9..10. A 1-tile radius building centered on the tile corner
    // (6000, 10000) spans exactly [5000, 7000] x [9000, 11000], covering tiles x[5-6], y[9-10].
    assertThat(arena.isValidBuildingPlacement(tiles(6), tiles(10), tiles(1), Team.BLUE)).isTrue();

    // River starts at tile y=15: a footprint whose top edge lands exactly on y=15000 does not
    // cover the river tile, but one game unit further does.
    assertThat(arena.isValidBuildingPlacement(tiles(6), tiles(14), tiles(1), Team.BLUE)).isTrue();
    assertThat(arena.isValidBuildingPlacement(tiles(6), tiles(14) + 1, tiles(1), Team.BLUE))
        .isFalse();
  }

  @Test
  void towerCoordinates_areGameUnits() {
    Arena arena = Arena.standard();

    assertThat(arena.getBlueCrownTowerX()).isEqualTo(tiles(9));
    assertThat(arena.getBlueCrownTowerY()).isEqualTo(tiles(3));
    assertThat(arena.getRedCrownTowerY()).isEqualTo(tiles(29));
    assertThat(arena.getBlueLeftPrincessTowerX()).isEqualTo(tiles(3.5));
    assertThat(arena.getBlueRightPrincessTowerX()).isEqualTo(tiles(14.5));
    assertThat(arena.getBlueLeftPrincessTowerY()).isEqualTo(tiles(6.5));
    assertThat(arena.getRedLeftPrincessTowerY()).isEqualTo(tiles(25.5));
  }
}
