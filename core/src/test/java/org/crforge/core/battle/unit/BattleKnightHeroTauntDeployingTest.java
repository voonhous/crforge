/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.Route;
import org.crforge.core.pathfinding.grid.TileMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hero Knight's taunt on a troop still deploying from its card play. The play's entry to the
 * deploying state ends with the combat gate, as every change of state does, so the troop deploys
 * with its targeting component switched off: the taunt forces its reference onto the Knight, but
 * the route the reference asks for finds no reference while the component is off, and the troop
 * holds no route through its deploy. The gate that ends the deploy switches the component on after
 * the moving state's entry, so the troop's first walking step searches its route from where it was
 * placed and walks toward that route's first node.
 */
class BattleKnightHeroTauntDeployingTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for the troop to be placed and to deploy. */
  private static final int TICKS = 200;

  /** The ability's spawn of the taunting area effect. */
  private static final String SPAWN = "Knight_hero_CreateTauntAEO";

  @Test
  @DisplayName(
      "a troop taunted while it deploys holds no route until it walks, then walks its route's first"
          + " node from where it was placed")
  void troopTauntedWhileDeployingWalksAFreshRoute() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, true);
    CharacterEntity knight =
        match.deploy(0, GameData.unit("KnightHero"), LEVEL, 0, 3500, 13000, "knight");
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    match.play(5, match.getWorld().getRecords().card("Knight"), LEVEL, 1, 3500, 17500, "T");
    int tick = 0;
    CharacterEntity troop = null;
    while (troop == null) {
      match.getBattle().step();
      assertThat(++tick).as("the troop is placed").isLessThan(TICKS);
      for (BattleEntity entity : match.getBattle().getHolder().entities()) {
        if (entity instanceof CharacterEntity unit && unit.side() == 1) {
          troop = unit;
        }
      }
    }
    assertThat(troop.getView().getState()).isEqualTo(GridEntityState.DEPLOYING);
    assertThat(troop.isActive(CharacterEntity.TARGETING_SLOT))
        .as("the play's entry to the deploying state ends with the gate, which switches it off")
        .isFalse();

    // The Knight's taunt reaches the deploying troop and forces its reference onto the Knight.
    BattleAction spawn = GameData.actions().build(SPAWN, match.getWorld().binding(knight));
    knight.actionHolder().start(spawn, knight.actionHolder());
    match.getBattle().step();
    assertThat(troop.getTargeting().getReference()).isSameAs(knight.getTargetView());

    // No route while it deploys: the reference is not read while the targeting component is off.
    int placedX = troop.getView().getX();
    int placedY = troop.getView().getY();
    while (troop.getView().getState() == GridEntityState.DEPLOYING) {
      assertThat(troop.getUnit().movement().getRoute().isEmpty())
          .as("the deploying troop holds no route")
          .isTrue();
      match.getBattle().step();
      assertThat(++tick).as("the troop deploys").isLessThan(TICKS);
    }
    assertThat(troop.getView().getState()).isEqualTo(GridEntityState.MOVING);
    assertThat(troop.getUnit().movement().getRoute().isEmpty())
        .as("the moving state's entry, before the gate switches the component on, makes no route")
        .isTrue();
    assertThat(troop.isActive(CharacterEntity.TARGETING_SLOT)).isTrue();
    assertThat(troop.getView().getX()).isEqualTo(placedX);
    assertThat(troop.getView().getY()).isEqualTo(placedY);

    // The first walking step searches the route from the placed point and heads for its first node.
    match.getBattle().step();
    Route route = troop.getUnit().movement().getRoute();
    assertThat(route.isEmpty()).isFalse();
    int dx = troop.getView().getX() - placedX;
    int dy = troop.getView().getY() - placedY;
    assertThat(dx != 0 || dy != 0).as("the troop walks").isTrue();
    int firstCol = placedX / TileMap.CELL_UNITS;
    int firstRow = placedY / TileMap.CELL_UNITS;
    int nodeCol = route.last() % match.getWorld().getGrid().getWidth();
    int nodeRow = route.last() / match.getWorld().getGrid().getWidth();
    // The node it walked toward is the first after its own cell: still ahead of it, or popped on
    // arrival, in which case the next one lies one cell further.
    assertThat(Math.max(Math.abs(nodeCol - firstCol), Math.abs(nodeRow - firstRow)))
        .as("the route starts next to the placed point")
        .isBetween(1, 2);
  }
}
