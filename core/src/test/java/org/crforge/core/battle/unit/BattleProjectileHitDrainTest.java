package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When the damage of a projectile's hit on its one target lands within its tick. The game queues it
 * for the holder's damage drain, as it queues a direct hit, and the damage entry's refusals, the
 * immunity left after a dash among them, are tested as the drain deals it: after the target's own
 * state visit of that tick has counted its immunity down.
 *
 * <p>The scene: the bottom side's Bandit runs up the left lane at the top side's Knight, held where
 * it stands in reach of the top side's left princess tower, and dashes at it. One of the tower's
 * arrows reaches the Bandit while it dashes, refused; the next reaches it on the step after its
 * dash lands, with 50 ms of its 100 ms immunity left as the step begins; the Bandit's visit of that
 * step counts it to 0. The Bandit's row gives it 150 ms of immunity, which leaves 50 ms after that
 * visit, so the scene's tables give it 100 ms.
 */
class BattleProjectileHitDrainTest {

  /** The level of the towers and of both units. */
  private static final int LEVEL = 1;

  /** Where the Bandit is placed, on the bottom side's half of the left lane. */
  private static final int BANDIT_X = 3500;

  private static final int BANDIT_Y = 16000;

  /** Where the Knight is held, in reach of the top side's left princess tower. */
  private static final int KNIGHT_Y = 23850;

  /** Long enough for the Bandit's run, its dash and the arrow after it. */
  private static final int TICKS = 200;

  /**
   * What the scene records: the tick the dash landed on and every arrow that reached the Bandit.
   */
  private static final class Record {
    int landing = -1;
    final List<String> arrows = new ArrayList<>();
    CharacterEntity bandit;
  }

  /**
   * Runs the scene to two steps past the Bandit's dash landing, recording each arrow on the Bandit
   * as its tick, whether it landed, and the Bandit's immunity left as it was dealt.
   */
  private static Record run(GameTables tables) {
    BattleRecords records = new BattleRecords(tables);
    Standard1v1Battle match = new Standard1v1Battle(tables, LEVEL, true);
    Record record = new Record();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void dashLanded(
                  int tick, CharacterEntity unit, WorldEntity hit, int damage, boolean area) {
                if (unit == record.bandit && record.landing < 0) {
                  record.landing = tick;
                }
              }

              @Override
              public void projectileImpacted(
                  int tick,
                  ProjectileEntity projectile,
                  WorldEntity target,
                  int damage,
                  DamageResult result) {
                if (target == record.bandit) {
                  record.arrows.add(
                      tick
                          + " "
                          + result.landed()
                          + " "
                          + record.bandit.getUnit().timers().getDashImmunityRemainingMs());
                }
              }
            });
    record.bandit =
        match.deploy(0, records.unit("Assassin"), LEVEL, 0, BANDIT_X, BANDIT_Y, "Bandit");
    CharacterEntity knight =
        match.deploy(0, records.unit("Knight"), LEVEL, 1, BANDIT_X, KNIGHT_Y, "Knight");
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    for (int tick = 0; tick < TICKS; tick++) {
      match.getBattle().step();
      if (record.landing >= 0 && match.getBattle().getTick() > record.landing + 2) {
        break;
      }
    }
    assertThat(record.landing).as("the Bandit dashed").isPositive();
    return record;
  }

  @Test
  @DisplayName(
      "an arrow one step after the Bandit's dash lands is dealt at the"
          + " damage drain, once the Bandit's visit has counted its immunity down to 0")
  void theArrowLandsAtTheDrain(@TempDir Path folder) throws IOException {
    // A chosen synthetic immunity of 100 ms, two visits, in place of the shipped row's 150: short
    // enough that the Bandit's visit of the step after the landing counts it down to 0 before the
    // arrow of that step lands at the drain.
    Record record =
        run(
            GameData.altered(
                folder,
                "characters",
                rows -> GameData.columns(rows, "Assassin").put("DashImmuneToDamageTime", 100)));
    assertThat(record.arrows).hasSize(2);
    assertThat(record.arrows.get(0)).as("an arrow during the dash").endsWith(" false 100");
    assertThat(record.arrows.get(1)).isEqualTo((record.landing + 1) + " true 0");
  }
}
