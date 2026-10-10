package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A unit that dashes asks, each tick, whether its reference is in its dash range, and the answer is
 * no while any buff it carries pulls: a buff with an attraction or a lateral push, as a Tornado's.
 * The question comes before the wind-up's own "yes while it runs", so a pull that reaches a unit
 * winding up its dash clears the wind-up, and the unit walks on, pulled, instead of dashing; once
 * the buff is gone its next wind-up starts from the full DashCooldown.
 *
 * <p>The scene: the bottom side's Mega Knight walks up the left lane at the top side's princess
 * tower, the towers passive, and starts its wind-up as the tower comes into its dash range. On the
 * wind-up's first tick a Tornado of the top side, in the filter form, is placed on the Mega
 * Knight's point; its first hit, on the next step, applies its buff. The scene writes every column
 * its outcome is read from: the Mega Knight's movement, sight and dash columns, the towers, the
 * Tornado's circle, timings and buff time, and its buff's pull. The control writes the buff with no
 * attraction: the Mega Knight then dashes as its wind-up runs out, under the same buff.
 */
class BattleDashUnderPullTest {

  /** The level the scene's units are placed at: the first, whose stats are the rows' own. */
  private static final int LEVEL = 1;

  /** The Mega Knight's wind-up before a dash, in milliseconds, as the scene writes it. */
  private static final int DASH_COOLDOWN = 900;

  /** How long the Tornado's buff lasts after a hit, and how long the Tornado lives. */
  private static final int BUFF_TIME = 500;

  private static final int TORNADO_LIFE = 1050;

  /**
   * The configured tables with the scene's columns written.
   *
   * @param folder the folder the tables are copied into
   * @param attract the Tornado buff's AttractPercentage: 360 for the pull, 0 for the control
   */
  private static GameTables written(Path folder, int attract) throws IOException {
    BattleAreaEffectFilterFormTest.filterForm(
        folder,
        "Tornado",
        columns -> {
          columns.put("Radius", 5500);
          columns.put("HitSpeed", 50);
          columns.put("HitSpeedOffset", 50);
          columns.put("LifeDuration", TORNADO_LIFE);
          columns.put("BuffTime", BUFF_TIME);
        });
    GameData.alterLoaded(
        folder,
        "character_buffs",
        rows -> {
          ObjectNode buff = GameData.columns(rows, "Tornado");
          buff.put("PushSpeedFactor", 100);
          buff.put("AttractPercentage", attract);
          buff.put("DamagePerSecond", 0);
          buff.remove(
              List.of(
                  "PushMassFactor", "LateralPushPercentage", "AttractMaxAngle", "SpeedMultiplier"));
        });
    GameData.alterLoaded(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "MegaKnight")
                .put("Hitpoints", 100_000)
                .put("Speed", 60)
                .put("Mass", 18)
                .put("CollisionRadius", 750)
                .put("Range", 1200)
                .put("SightRange", 5500)
                .put("DeployTime", 1000)
                .put("HitSpeed", 1700)
                .put("LoadTime", 1200)
                .put("DashMinRange", 3500)
                .put("DashMaxRange", 5000)
                .put("DashCooldown", DASH_COOLDOWN)
                .put("DashConstantTime", 800)
                .put("DashLandingTime", 300)
                .put("DashDamage", 210)
                .put("DashRadius", 2200)
                .put("JumpHeight", 3000)
                .put("JumpSpeed", 250));
    GameData.writeTowers(folder);
    return GameTables.load(folder);
  }

  /** The scene stepped to the Tornado's first hit; the Mega Knight's states after it, per tick. */
  private static final class Scene {
    final Standard1v1Battle match;
    final CharacterEntity megaKnight;

    /** The tick the wind-up started on, which the Tornado is placed on. */
    final int windupStart;

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      megaKnight =
          match.deploy(
              0, match.getWorld().getRecords().unit("MegaKnight"), LEVEL, 0, 3500, 17500, "MK");
      while (megaKnight.getUnit().targeting().getDashWindupMs() == 0) {
        assertThat(match.getBattle().getTick()).as("the wind-up starts").isLessThan(400);
        match.getBattle().step();
      }
      windupStart = match.getBattle().getTick();
      match.placeAreaEffect(
          windupStart,
          "Tornado",
          LEVEL,
          1,
          megaKnight.getView().getX(),
          megaKnight.getView().getY(),
          "Tornado");
    }

    /** Steps once; returns the Mega Knight's state after the step. */
    int step() {
      match.getBattle().step();
      return megaKnight.getView().getState();
    }
  }

  @Test
  @DisplayName(
      "a unit that carries a pulling buff is never in its dash range: the pull clears its wind-up"
          + " and it walks on instead of dashing")
  void aPullingBuffHoldsTheDashBack(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(written(folder, 360));
    // The buff is applied by the Tornado's hit, after the Mega Knight's own visit of that tick;
    // from the next tick on, each visit finds it carried.
    boolean carriedBefore = false;
    int heldTicks = 0;
    int end = scene.windupStart + (TORNADO_LIFE + BUFF_TIME) / 50;
    while (scene.match.getBattle().getTick() < end) {
      int state = scene.step();
      int tick = scene.match.getBattle().getTick();
      boolean carried = scene.megaKnight.getBuffs().carries("Tornado");
      if (carriedBefore && carried) {
        heldTicks++;
        assertThat(state)
            .as("no dash under the pull, tick %d", tick)
            .isNotEqualTo(GridEntityState.DASHING);
        assertThat(scene.megaKnight.getUnit().targeting().getDashWindupMs())
            .as("the wind-up is cleared under the pull, tick %d", tick)
            .isZero();
      }
      carriedBefore = carried;
    }
    // The wind-up would have run out within the buff's hold.
    assertThat(heldTicks).as("ticks under the pull").isGreaterThan(DASH_COOLDOWN / 50);
  }

  @Test
  @DisplayName(
      "the control: under the same buff without a pull, the wind-up runs out and the unit dashes")
  void aBuffWithoutAPullLetsTheDashStart(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(written(folder, 0));
    int dashTick = -1;
    while (dashTick < 0 && scene.match.getBattle().getTick() < scene.windupStart + 40) {
      if (scene.step() == GridEntityState.DASHING) {
        dashTick = scene.match.getBattle().getTick();
        assertThat(scene.megaKnight.getBuffs().carries("Tornado"))
            .as("the buff is on the Mega Knight as it dashes")
            .isTrue();
      }
    }
    // The wind-up's first step took its first 50 ms off; the step that takes the last one starts
    // the dash.
    assertThat(dashTick).isEqualTo(scene.windupStart + DASH_COOLDOWN / 50 - 1);
  }
}
