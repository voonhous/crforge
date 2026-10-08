package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.crforge.core.battle.Battle;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.deploy.DeployCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An entity killed during a tick is still visited by the rest of that tick, except that its death
 * switches its movement component off. Two Knights that meet on the left lane hit each other on the
 * same ticks; on the tick of their last hits the first one visited kills the other, whose own hit,
 * due on the same tick, still lands, and both leave the battle in that tick's closing cleanup. The
 * scene writes the Knight's row, the towers' places and the columns of theirs the walk reads, and
 * plays both at the first level, so its ticks and hit points are its own and not a version's.
 */
class BattleDeathTickTest {

  /** The level both Knights are played at: the first, whose stats are the row's own. */
  private static final int LEVEL = 1;

  /** The Knight's hit points and damage, as the scene writes them. */
  private static final int HIT_POINTS = 690;

  private static final int DAMAGE = 79;

  /** The tick of the last hits: the scene's own, as every column it follows from is written. */
  private static final int LAST_HITS = 271;

  /** Each Knight's hit points before the last hits: eight of the other's hits taken. */
  private static final int LEFT = HIT_POINTS - 8 * DAMAGE;

  /** The configured tables with the Knight's row, the towers' places and their columns written. */
  private static GameTables written(Path folder) throws IOException {
    GameData.altered(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "Knight")
                .put("Hitpoints", HIT_POINTS)
                .put("Damage", DAMAGE)
                .put("HitSpeed", 1200)
                .put("LoadTime", 700)
                .put("Speed", 60)
                .put("Mass", 6)
                .put("CollisionRadius", 500)
                .put("Range", 1200)
                .put("SightRange", 5500)
                .put("DeployTime", 1000)
                .put("ProjectileStartRadius", 450)
                .put("ProjectileStartZ", 450));
    GameData.writeTowers(folder);
    return GameTables.load(folder);
  }

  @Test
  @DisplayName("two Knights that land their last hits on one tick both die, their movement off")
  void twoKnightsKillEachOtherOnOneTick(@TempDir Path folder) throws IOException {
    Standard1v1Battle match = new Standard1v1Battle(written(folder), LEVEL);
    DeployCard knight = match.getWorld().getRecords().card("Knight");
    Battle battle = match.getBattle();
    match.play(0, knight, LEVEL, 0, 3500, 12000, "Blue");
    match.play(0, knight, LEVEL, 1, 3500, 20000, "Red");

    for (int tick = 0; tick < LAST_HITS; tick++) {
      battle.step();
    }
    CharacterEntity blue = match.getPlays().get(0).units().get(0);
    CharacterEntity red = match.getPlays().get(1).units().get(0);
    assertThat(blue.getHitPoints().getHitPoints()).isEqualTo(LEFT);
    assertThat(red.getHitPoints().getHitPoints()).isEqualTo(LEFT);
    assertThat(blue.getView().isMovementActive()).isTrue();

    battle.step();
    assertThat(red.getHitPoints().getHitPoints()).as("Blue, visited first, kills Red").isZero();
    assertThat(blue.getHitPoints().getHitPoints()).as("and Red's due hit still lands").isZero();
    assertThat(red.getView().isMovementActive()).as("death switches movement off").isFalse();
    assertThat(blue.getView().isMovementActive()).isFalse();
    assertThat(red.getView().isMovementComponent()).as("the component stays").isTrue();
    assertThat(battle.getHolder().entities()).doesNotContain(blue, red);
  }
}
