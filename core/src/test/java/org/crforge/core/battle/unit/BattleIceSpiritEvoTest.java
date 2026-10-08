package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Ice Spirit's second freeze where the reference runs do not take it: the area that
 * follows the unit the spirit hit, once that unit leaves.
 */
class BattleIceSpiritEvoTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  @Test
  @DisplayName(
      "the area following the unit the spirit hit stays on that unit's last point once it leaves,"
          + " and still hits there, 3000 ms after the impact, the unit beside it")
  void theAreaStaysWhereItsUnitLeft() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    List<AreaEffectEntity> made = new ArrayList<>();
    List<String> hits = new ArrayList<>();
    int[] tick = {0};
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileAreaEffect(
                  int t, ProjectileEntity projectile, AreaEffectEntity areaEffect) {
                made.add(areaEffect);
              }

              @Override
              public void typedHitDealt(
                  int t,
                  WorldEntity source,
                  WorldEntity target,
                  int amount,
                  int damageId,
                  DamageResult result) {
                hits.add(tick[0] + " " + target.name() + " " + amount);
              }
            });
    match.deploy(0, GameData.unit("IceSpirits_EV1"), LEVEL, 0, 3500, 14000, "I");
    CharacterEntity b0 = match.deploy(0, GameData.unit("Barbarian"), LEVEL, 1, 3500, 16600, "B0");
    CharacterEntity b1 = match.deploy(0, GameData.unit("Barbarian"), LEVEL, 1, 2600, 17200, "B1");
    for (CharacterEntity b : List.of(b0, b1)) {
      b.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      b.setActive(CharacterEntity.TARGETING_SLOT, false);
    }
    while (made.isEmpty()) {
      match.getBattle().step();
      tick[0]++;
      assertThat(tick[0]).as("the spirit lands").isLessThan(200);
    }
    int impact = tick[0] - 1;
    AreaEffectEntity area = made.get(0);
    assertThat(area.getFollow()).isSameAs(b0);
    for (int i = 0; i < 10; i++) {
      match.getBattle().step();
      tick[0]++;
    }
    int x = area.getX();
    int y = area.getY();
    match.getWorld().kill(b0, null);
    match.getBattle().step();
    tick[0]++;
    assertThat(area.getFollow()).as("let go").isNull();
    while (match.getBattle().getHolder().entities().contains(area)) {
      match.getBattle().step();
      tick[0]++;
      assertThat(area.getX()).isEqualTo(x);
      assertThat(area.getY()).isEqualTo(y);
    }

    // The filter form's one hit falls on the update its HitSpeedOffset of 3000 ms starts, the
    // 61st, kept by the countdown below 0; its typed hit is dealt at that step's damage drain.
    assertThat(hits).containsExactly((impact + 61) + " B1 110");
  }
}
