package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.Version16Tables;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A cast that fires and ends in the casting state's own entry, the Ice Golemite hero form's ability
 * (IceGolemiteHero_Ability of data version 16.402.18, neither a cast time nor a trigger delay),
 * asked for while the unit walks. The entry empties the route and runs the ability's effect; the
 * unit is then walking again by a bare store of the state, without entering the walking state, so
 * no route is prepared while the request runs. The unit's next movement visit prepares it, routed
 * around the tower footprints that visit's overlay holds.
 */
class InstantCastRouteTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  @Test
  @DisplayName(
      "a walking Ice Golemite hero that casts holds no route until its next movement visit, which"
          + " routes it around its princess tower's footprint")
  void theRouteWaitsForTheMovementVisit() {
    GameTables tables = Version16Tables.load();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    CharacterEntity hero =
        match.deploy(0, records.unit("IceGolemiteHero"), LEVEL, 0, 6499, 1500, "hero");
    // Alone on the arena it walks for its princess tower's lane, past its own princess tower.
    for (int k = 0; k < 400 && hero.getView().getY() < 2352; k++) {
      match.getBattle().step();
    }
    assertThat(new int[] {hero.getView().getX(), hero.getView().getY()})
        .containsExactly(6344, 2352);
    assertThat(hero.getUnit().movement().getRoute().size()).isPositive();

    // The request, between two steps as a command runs: cast, fired and walking again, routeless.
    hero.requestAbility();
    assertThat(hero.getView().getState()).isEqualTo(GridEntityState.MOVING);
    assertThat(hero.getUnit().movement().getRoute().size()).isZero();

    // The movement visit routes it with the overlay of its own step: past the tower's footprint by
    // the cells of column 9, not those of column 8 an empty overlay would route it through.
    match.getBattle().step();
    Route route = hero.getUnit().movement().getRoute();
    assertThat(route.toArray()).contains(441, 477).doesNotContain(440, 476);
    assertThat(new int[] {hero.getView().getX(), hero.getView().getY()})
        .containsExactly(6333, 2402);

    // 62 steps after the cast it stands where the game's hero does as the ability's two circles
    // end, having turned for the waypoint of column 9.
    for (int k = 1; k < 62; k++) {
      match.getBattle().step();
    }
    assertThat(new int[] {hero.getView().getX(), hero.getView().getY()})
        .containsExactly(5027, 4854);
  }
}
