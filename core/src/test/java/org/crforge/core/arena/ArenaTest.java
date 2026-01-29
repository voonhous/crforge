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
}
