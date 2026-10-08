package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When a direct hit's buff on damage is applied within its tick, and so how long a 500 ms stun
 * holds what it lands on. The game applies the attacker's BuffOnDamage at the holder's damage
 * drain, right after the hit's damage, once the damage entry has let the hit through: after the
 * buff pass of that tick, so the instance is first counted down on the next tick and holds its
 * target for all of its 500 / 50 = 10 visits.
 *
 * <p>The scene: the top side's Electro Wizard stands in reach of the bottom side's left princess
 * tower, which shoots back. The Wizard's first zap lands on the tower with ZapFreeze for 500 ms:
 * the scene writes the Wizard's stun time and its two targets a hit.
 */
class BattleBuffOnDamageDrainTest {

  /** The level of the towers and of the Wizard. */
  private static final int LEVEL = 1;

  /** Where the Wizard stands, in reach of the bottom side's left princess tower. */
  private static final int WIZARD_X = 3500;

  private static final int WIZARD_Y = 11000;

  /** Long enough for the Wizard's deploy and its first zap. */
  private static final int TICKS = 200;

  /** The ticks recorded after the zap's own. */
  private static final int AFTER = 14;

  /** Side 0's left princess tower, the one the Wizard stands in reach of. */
  private static TowerEntity leftTower(Standard1v1Battle match) {
    return match.getWorld().getHolder().entities().stream()
        .filter(TowerEntity.class::isInstance)
        .map(TowerEntity.class::cast)
        .filter(t -> t.side() == 0 && !t.getData().king() && t.x() < 9000)
        .findFirst()
        .orElseThrow();
  }

  /** The ZapFreeze time left on an entity, or -1 when it carries none. */
  private static int zapFreezeLeft(WorldEntity entity) {
    for (BuffInstance instance : entity.getBuffs().items()) {
      if (instance.getBuff().name().equals("ZapFreeze")) {
        return instance.getRemaining();
      }
    }
    return -1;
  }

  /**
   * What the scene records from the tick the Wizard's first zap lands on the tower: the tower's
   * ZapFreeze time left at the end of that tick and of each tick after it, and the first tick after
   * the zap on which the tower's attack time moves again.
   */
  private static final class Record {
    int zapTick = -1;
    final List<Integer> left = new ArrayList<>();
    int resumed = -1;
  }

  /** Runs the scene to {@link #AFTER} ticks past the Wizard's first zap on the tower. */
  private static Record run(GameTables tables) {
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, true);
    TowerEntity tower = leftTower(match);
    Record record = new Record();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void damageDealt(
                  int tick, WorldEntity target, int damage, DamageResult result) {
                if (target == tower && record.zapTick < 0) {
                  record.zapTick = tick;
                }
              }
            });
    match.deploy(
        0,
        match.getWorld().getRecords().unit("ElectroWizard"),
        LEVEL,
        1,
        WIZARD_X,
        WIZARD_Y,
        "Wizard");
    int attackTime = -1;
    for (int step = 0; step < TICKS; step++) {
      int tick = match.getBattle().getTick();
      match.getBattle().step();
      if (record.zapTick < 0) {
        continue;
      }
      int now = tower.getTargeting().getAttackTimerMs();
      if (tick > record.zapTick && record.resumed < 0 && now != attackTime) {
        record.resumed = tick;
      }
      attackTime = now;
      record.left.add(zapFreezeLeft(tower));
      if (tick >= record.zapTick + AFTER) {
        break;
      }
    }
    assertThat(record.zapTick).as("the Wizard zapped the tower").isPositive();
    return record;
  }

  @Test
  @DisplayName(
      "the zap's ZapFreeze is applied at the damage drain: whole at the"
          + " end of its tick, held for ten ticks, the tower's attack resuming on the eleventh")
  void theStunIsAppliedAtTheDrain(@TempDir Path folder) throws IOException {
    Record record =
        run(
            GameData.altered(
                folder,
                "characters",
                rows ->
                    GameData.columns(rows, "ElectroWizard")
                        .put("BuffOnDamageTime", 500)
                        .put("MultipleTargets", 2)));
    assertThat(record.left.subList(0, 11))
        .containsExactly(500, 450, 400, 350, 300, 250, 200, 150, 100, 50, -1);
    assertThat(record.resumed).isEqualTo(record.zapTick + 11);
  }
}
