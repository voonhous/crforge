package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.projectile.ProjectileData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.math.FixedMath;
import org.crforge.core.pathfinding.target.TargetingState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Hunter's line fan when its target is gone at the launch: a Skeleton the Hunter has started
 * its attack on is zapped during the load, and the Hunter fires its volley with no target.
 *
 * <p>The first projectile flies at where the Skeleton stood; the hit then moves the stored
 * reference position to the Hunter's range ahead of it along its facing, and every further
 * projectile is aimed along the facing turned by its fan step, from the Hunter. The counts, the
 * spread, the range and the projectile's flight range are read from the rows.
 */
class BattleHunterFanNoTargetTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  @Test
  @DisplayName(
      "a Hunter whose target dies during its load fans its volley along its facing and moves its"
          + " stored reference to its range ahead")
  void theVolleyFansAlongTheFacingWithoutATarget() {
    BattleRecords records = GameData.records();
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    Battle battle = match.getBattle();
    BattleWorld world = match.getWorld();
    CharacterEntity hunter = match.deploy(0, records.unit("Hunter"), LEVEL, 0, 9000, 15000);
    CharacterEntity skeleton = match.deploy(0, records.unit("Skeleton"), LEVEL, 1, 9000, 18000);
    // The Hunter starts its attack on the Skeleton; the Zap lands during the load.
    for (int tick = 0;
        tick < 200 && hunter.getView().getState() != GridEntityState.ATTACKING;
        tick++) {
      battle.step();
    }
    assertThat(hunter.getView().getState())
        .as("the Hunter attacks")
        .isEqualTo(GridEntityState.ATTACKING);
    assertThat(world.projectiles()).as("no volley yet").isEmpty();
    GridEntity at = skeleton.getView();
    match.play(battle.getTick() + 1, records.card("Zap"), LEVEL, 0, at.getX(), at.getY(), "Zap");
    List<ProjectileEntity> volley = new ArrayList<>();
    for (int tick = 0; tick < 100 && volley.isEmpty(); tick++) {
      battle.step();
      for (ProjectileEntity p : world.projectiles()) {
        if (p.getOwner() == hunter) {
          volley.add(p);
        }
      }
    }
    assertThat(HitPoints.alive(skeleton.getHitPoints())).as("the Skeleton zapped").isFalse();
    UnitData unit = hunter.getData();
    assertThat(volley).as("the whole volley").hasSize(unit.multipleProjectiles());

    GridEntity own = hunter.getView();
    // The stored reference: the Hunter's range ahead of it along its facing.
    int[] ahead = {own.getDirX(), own.getDirY()};
    FixedMath.normalize(ahead, unit.range());
    TargetingState t = hunter.getTargeting();
    assertThat(new int[] {t.getLastReferenceX(), t.getLastReferenceY()})
        .as("the stored reference moved ahead of the Hunter")
        .containsExactly(own.getX() + ahead[0], own.getY() + ahead[1]);

    // Every further projectile: the facing turned by its fan step, from the Hunter, flown out to
    // the projectile's range.
    int count = unit.multipleProjectiles();
    for (int k = 1; k < count; k++) {
      ProjectileEntity p = volley.get(k);
      ProjectileData data = p.getData();
      int fan = (k & 1) != 0 ? (k + 1) >> 1 : -((k + 1) >> 1);
      int[] dir = {own.getDirX(), own.getDirY()};
      FixedMath.rotate1024(dir, fan * unit.areaDamageRadius() / count);
      FixedMath.normalize(dir, data.projectileRange());
      assertThat(p.getTarget()).as("projectile %d has no target", k).isNull();
      assertThat(new int[] {p.getAimX(), p.getAimY()})
          .as("projectile %d aimed along the turned facing", k)
          .containsExactly(own.getX() + dir[0], own.getY() + dir[1]);
    }
  }
}
