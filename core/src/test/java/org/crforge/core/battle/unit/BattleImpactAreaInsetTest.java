/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.deploy.CardPlacement;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The area effect a projectile's impact makes is created as every game object is: its point kept
 * the creation's inset inside each edge of the arena, wherever the projectile itself ended.
 */
class BattleImpactAreaInsetTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  @Test
  @DisplayName(
      "the evolved Firecracker's shrapnel that fly past the arena's side make their area effects"
          + " the creation's inset inside it, the others where they land")
  void shrapnelAreasStayInsideTheArena() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    List<int[]> made = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileAreaEffect(
                  int t, ProjectileEntity projectile, AreaEffectEntity areaEffect) {
                made.add(
                    new int[] {
                      projectile.getAimX(),
                      projectile.getAimY(),
                      areaEffect.getX(),
                      areaEffect.getY()
                    });
              }
            });
    // The Firecracker shoots along the width toward a target near the left side, so the shell's
    // shrapnel fly on past it, beyond the arena's edge.
    match.deploy(0, GameData.unit("Firecracker_EV1"), LEVEL, 0, 6500, 14000, "F");
    CharacterEntity target =
        match.deploy(0, GameData.unit("Barbarian"), LEVEL, 1, 1500, 14000, "B");
    target.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    target.setActive(CharacterEntity.TARGETING_SLOT, false);
    int width = match.getWorld().getGrid().getWidth() * TileMap.CELL_UNITS;
    int height = match.getWorld().getGrid().getHeight() * TileMap.CELL_UNITS;
    int inset = CardPlacement.CREATION_INSET;
    int ticks = 0;
    while (made.stream().noneMatch(m -> m[0] < inset) || made.size() < 2) {
      match.getBattle().step();
      ticks++;
      assertThat(ticks).as("a shrapnel lands beyond the left side").isLessThan(400);
    }
    for (int[] m : made) {
      assertThat(m[2]).isEqualTo(Math.min(Math.max(m[0], inset), width - inset));
      assertThat(m[3]).isEqualTo(Math.min(Math.max(m[1], inset), height - inset));
    }
    assertThat(made).anyMatch(m -> m[0] < 0 && m[2] == inset);
  }
}
