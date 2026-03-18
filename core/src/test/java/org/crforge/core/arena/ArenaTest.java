package org.crforge.core.arena;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.player.Team;
import org.junit.jupiter.api.Test;

class ArenaTest {

  @Test
  void shouldNotDeployOnBluePrincessTower() {
    Arena arena = Arena.standard();

    // Blue Left Princess Tower is around x=3, y=6 (indices 2-4, 5-7)
    // Testing center of tower
    assertThat(arena.isValidPlacement(3.5f, 6.5f, Team.BLUE))
        .as("Should not be able to deploy on top of Princess Tower")
        .isFalse();
  }

  @Test
  void shouldNotDeployOnBlueCrownTower() {
    Arena arena = Arena.standard();

    // Blue Crown Tower is around x=9, y=3 (indices 7-10, 1-4)
    // Testing center of tower area
    assertThat(arena.isValidPlacement(9.0f, 3.0f, Team.BLUE))
        .as("Should not be able to deploy on top of Crown Tower")
        .isFalse();
  }

  @Test
  void shouldNotDeployOnRedTowersAsRed() {
    Arena arena = Arena.standard();

    // Red Crown Tower (top side)
    assertThat(arena.isValidPlacement(9.0f, 29.0f, Team.RED))
        .as("Red player should not deploy on their own Crown Tower")
        .isFalse();
  }

  @Test
  void shouldDeployNextToTower() {
    Arena arena = Arena.standard();

    // Just outside Blue Princess Tower range (x=5 is valid, tower ends at 4)
    assertThat(arena.isValidPlacement(5.5f, 6.5f, Team.BLUE))
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

    arena.freePrincessTowerTiles(3.5f, 6.5f, Team.BLUE);

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
    arena.freePrincessTowerTiles(14.5f, 25.5f, Team.RED);

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
    assertThat(arena.isValidPlacement(3.5f, 6.5f, Team.BLUE))
        .as("Cannot place on live princess tower")
        .isFalse();

    arena.freePrincessTowerTiles(3.5f, 6.5f, Team.BLUE);

    assertThat(arena.isValidPlacement(3.5f, 6.5f, Team.BLUE))
        .as("Should be able to place on destroyed princess tower footprint")
        .isTrue();
  }

  @Test
  void freePrincessTowerTiles_doesNotAffectCrownTower() {
    Arena arena = Arena.standard();

    // Free blue left princess tower
    arena.freePrincessTowerTiles(3.5f, 6.5f, Team.BLUE);

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
    arena.freePrincessTowerTiles(3.5f, 6.5f, Team.BLUE);

    // Blue right princess tower tiles (x[13-15], y[5-7]) should remain TOWER
    for (int x = 13; x <= 15; x++) {
      for (int y = 5; y <= 7; y++) {
        assertThat(arena.getTile(x, y).type())
            .as("Other princess tower tile (%d,%d) should remain TOWER", x, y)
            .isEqualTo(TileType.TOWER);
      }
    }
  }
}
