package org.crforge.parity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.unit.AreaEffectEntity;
import org.crforge.core.battle.unit.CharacterEntity;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.battle.unit.WorldObserver;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Monk's Deflect of data version 16.402.18: its ability's activation group spawns the area
 * effect Deflect, a row of the filter form (it names the filter aeo_enemy_no_buildings in place of
 * hit switches) that sets DeflectProjectilesEnabled. The deflection pass of a flying projectile
 * reads only that switch, the area effect's radius and its team, never its filter, so an enemy shot
 * within its radius is turned around at its shooter as for any deflecting area effect.
 */
class MonkDeflectTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for a placed Monk to deploy, cast and deflect a shot. */
  private static final int TICKS = 300;

  @Test
  @DisplayName("the Monk's Deflect turns a Musketeer's shot around at the Musketeer, which it hits")
  void deflectTurnsAShotOnItsShooter() {
    GameTables tables = Version16Tables.load();
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, false);
    List<ProjectileEntity> deflected = new ArrayList<>();
    List<String> deflectors = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileDeflected(
                  int tick,
                  AreaEffectEntity deflector,
                  ProjectileEntity projectile,
                  WorldEntity parent,
                  WorldEntity source) {
                deflected.add(projectile);
                deflectors.add(deflector.getData().name());
              }
            });
    CharacterEntity monk = match.deploy(0, records.unit("Monk"), LEVEL, 0, 3500, 10000, "monk");
    CharacterEntity musketeer =
        match.deploy(0, records.unit("Musketeer"), LEVEL, 1, 3500, 15500, "musketeer");
    int tick = 0;
    // Hold the Monk where it deployed and request its ability.
    while (monk.getView().getState() == GridEntityState.DEPLOYING
        || monk.getView().getState() == GridEntityState.WAITING_TO_DEPLOY
        || monk.getId() == 0) {
      match.getBattle().step();
      tick++;
      assertThat(tick).as("the Monk deploys").isLessThan(TICKS);
    }
    monk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    musketeer.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    monk.requestAbility();
    int full = musketeer.getHitPoints().getHitPoints();
    while (deflected.isEmpty()) {
      match.getBattle().step();
      tick++;
      assertThat(tick).as("a shot is deflected").isLessThan(TICKS);
    }

    ProjectileEntity shot = deflected.get(0);
    assertThat(deflectors.get(0)).isEqualTo("Deflect");
    assertThat(shot.side()).as("the shot flies for the Monk's side").isEqualTo(monk.side());
    assertThat(shot.getOwner()).isSameAs(monk);
    assertThat(shot.getTarget()).isSameAs(musketeer);
    assertThat(shot.getDeflections()).isEqualTo(1);
    int damage = shot.damage();
    while (!shot.isReleased()) {
      match.getBattle().step();
    }
    assertThat(musketeer.getHitPoints().getHitPoints())
        .as("the Musketeer takes its own shot")
        .isEqualTo(full - damage);
  }
}
