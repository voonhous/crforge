package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A hopping projectile whose launcher has left the battle: the Electro Spirit dies on its launch,
 * so every hop of its shot comes after its launcher left. The shot hops on as one whose launcher is
 * still there would - from where it landed, to the nearest enemy it has not hit, at its own level
 * and side - with itself as its own owner, so its owner id becomes its own id; an owner that is no
 * character is what every reader of the owner makes of none.
 */
class BattleChainHopOrphanTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  @Test
  @DisplayName("an Electro Spirit's shot hops on along three Knights after the spirit has left")
  void theShotHopsOnAfterItsLauncherLeft() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    Battle battle = match.getBattle();
    List<String> hits = new ArrayList<>();
    List<ProjectileEntity> shots = new ArrayList<>();
    List<Integer> spiritGone = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileLaunched(int tick, ProjectileEntity projectile) {
                shots.add(projectile);
              }

              @Override
              public void projectileImpacted(
                  int tick,
                  ProjectileEntity projectile,
                  WorldEntity target,
                  int damage,
                  DamageResult result) {
                hits.add(target.name() + " " + damage + " owner " + projectile.getOwner());
              }

              @Override
              public void entityRemoved(int tick, WorldEntity removed) {
                if (removed.name().equals("ElectroSpirit")) {
                  spiritGone.add(tick);
                }
              }
            });
    // Three Knights in a row, each within the shot's hop radius of the next, far from every
    // tower; the spirit of the other side runs at the nearest.
    match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 9000, 15500, "K1");
    match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 10500, 15500, "K2");
    match.deploy(0, GameData.unit("Knight"), LEVEL, 0, 12000, 15500, "K3");
    match.deploy(0, GameData.unit("ElectroSpirit"), LEVEL, 1, 9000, 18000);
    for (int tick = 0; tick < 200 && (shots.isEmpty() || !shots.get(0).isReleased()); tick++) {
      battle.step();
    }

    assertThat(spiritGone).as("the spirit leaves on its launch").hasSize(1);
    assertThat(shots).hasSize(1);
    ProjectileEntity shot = shots.get(0);
    assertThat(shot.isReleased()).isTrue();
    // The first impact and both hops land the shot's full damage, each Knight once.
    int damage = shot.damage();
    assertThat(hits)
        .containsExactly(
            "K1 " + damage + " owner null",
            "K2 " + damage + " owner null",
            "K3 " + damage + " owner null");
    assertThat(shot.getChainedHits()).isEqualTo(3);
    // Each hop launches it with itself as its owner: the owner id is its own.
    assertThat(shot.getOwnerId()).isEqualTo(shot.getId());
    assertThat(shot.getRoot()).isNull();
  }
}
