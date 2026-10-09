package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When the hit of a dash's landing lands within its tick. The game hands it to the same damage
 * entry as a direct hit, which queues it for the holder's damage drain after every post-hook, so a
 * unit the landing kills still takes its movement visit of that tick.
 *
 * <p>The scene: the bottom side's Bandit, which dashes at a target between its dash's least and
 * greatest range, walks up the left lane and dashes at the top side's Giant coming down it. The
 * Giant, which targets buildings only, walks on, and the dash's single-target landing hit, larger
 * than the Giant's hit points, kills it on the landing's tick. The Giant is placed after the
 * Bandit, so its movement visit of each tick comes after the Bandit's, where the landing is. The
 * scene writes every column its outcome is read from - both units' rows, the towers' places and the
 * columns of theirs the walk reads - and places both units at the first level, where their stats
 * are their rows' own, so its ticks, hit points and points are its own and not a version's. The
 * same scene with the Giant written with hit points no hit takes is the control: it shows where the
 * Giant's walking step of each tick takes it.
 */
class BattleDashLandingDrainTest {

  /** The level both units are placed at: the first, whose stats are the rows' own. */
  private static final int LEVEL = 1;

  /** The Giant's hit points and the Bandit's dash damage, as the scene writes them. */
  private static final int GIANT_HIT_POINTS = 150;

  private static final int DASH_DAMAGE = 152;

  /**
   * The tick the Bandit's dash lands: the scene's own, as every column it follows from is written.
   */
  private static final int LANDING = 54;

  /** Where the Giant stands after its last walking step before the landing's tick. */
  private static final int LAST_X = 3680;

  private static final int LAST_Y = 16538;

  /** Where its walking step of the landing's tick takes it. */
  private static final int STEP_X = 3682;

  private static final int STEP_Y = 16487;

  /** The configured tables with the scene's columns written, the Giant's hit points given. */
  private static GameTables written(Path folder, int giantHitPoints) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "Assassin")
              .put("Hitpoints", 354)
              .put("Damage", 76)
              .put("HitSpeed", 1000)
              .put("LoadTime", 600)
              .put("Speed", 90)
              .put("Mass", 3)
              .put("CollisionRadius", 600)
              .put("Range", 750)
              .put("SightRange", 6000)
              .put("DeployTime", 1000)
              .put("DashDamage", DASH_DAMAGE)
              .put("DashMinRange", 3500)
              .put("DashMaxRange", 6000)
              .put("DashCooldown", 800)
              .put("DashImmuneToDamageTime", 150)
              .put("JumpSpeed", 500);
          GameData.columns(rows, "Giant")
              .put("Hitpoints", giantHitPoints)
              .put("Damage", 99)
              .put("HitSpeed", 1500)
              .put("LoadTime", 1000)
              .put("Speed", 45)
              .put("StopMovementAfterMS", 640)
              .put("WaitMS", 100)
              .put("Mass", 18)
              .put("CollisionRadius", 750)
              .put("Range", 1200)
              .put("SightRange", 7500)
              .put("SightClip", 2000)
              .put("SightClipSide", 2000)
              .put("DeployTime", 1000)
              .put("ProjectileStartRadius", 450)
              .put("ProjectileStartZ", 450);
        });
    GameData.writeTowers(folder);
    return GameTables.load(folder);
  }

  /** The scene: the towers passive, the Bandit placed first and the Giant after it. */
  private static final class Scene {
    final Standard1v1Battle match;
    final CharacterEntity bandit;
    final CharacterEntity giant;

    /** Each landing of the Bandit's dash: its tick, and 1 when its hit was on the Giant. */
    final List<int[]> landings = new ArrayList<>();

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      bandit =
          match.deploy(
              0, match.getWorld().getRecords().unit("Assassin"), LEVEL, 0, 3500, 10000, "Bandit");
      giant =
          match.deploy(
              0, match.getWorld().getRecords().unit("Giant"), LEVEL, 1, 3500, 18000, "Giant");
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void dashLanded(
                    int tick, CharacterEntity unit, WorldEntity hit, int damage, boolean area) {
                  if (unit == bandit) {
                    landings.add(new int[] {tick, hit == giant ? 1 : 0});
                  }
                }
              });
    }

    /** Steps the battle until its tick is the given one. */
    void stepTo(int tick) {
      while (match.getBattle().getTick() < tick) {
        match.getBattle().step();
      }
    }
  }

  @Test
  @DisplayName(
      "a dash's landing hit lands at the damage drain, so the Giant it kills still takes its"
          + " walking step of that tick")
  void theGiantTheLandingKillsStillWalksItsStep(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(written(folder.resolve("scene"), GIANT_HIT_POINTS));
    scene.stepTo(LANDING - 1);
    assertThat(scene.landings).as("no landing yet").isEmpty();
    assertThat(scene.giant.getHitPoints().getHitPoints())
        .as("alive before the landing")
        .isEqualTo(GIANT_HIT_POINTS);
    assertThat(scene.giant.getView().getX()).isEqualTo(LAST_X);
    assertThat(scene.giant.getView().getY()).isEqualTo(LAST_Y);

    scene.stepTo(LANDING);
    // The landing's tick as the world counts it, one below the battle's after the step.
    assertThat(scene.landings).as("one landing, its hit on the Giant").hasSize(1);
    assertThat(scene.landings.get(0)).containsExactly(LANDING - 1, 1);
    assertThat(scene.giant.getHitPoints().getHitPoints()).as("killed").isZero();
    assertThat(scene.giant.getView().getX()).as("one walking step on").isEqualTo(STEP_X);
    assertThat(scene.giant.getView().getY()).isEqualTo(STEP_Y);

    // The control's Giant takes the landing's hit and lives: its step of that tick takes it to the
    // same point.
    Scene control = new Scene(written(folder.resolve("control"), 1_000_000));
    control.stepTo(LANDING);
    assertThat(control.giant.getHitPoints().getHitPoints())
        .as("the control's Giant took the hit")
        .isEqualTo(1_000_000 - DASH_DAMAGE);
    assertThat(new int[] {control.giant.getView().getX(), control.giant.getView().getY()})
        .containsExactly(STEP_X, STEP_Y);
  }
}
