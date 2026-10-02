package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The Boss Bandit's warp where the reference runs do not take it: a landing on water, past the
 * arena's edge, on the other side and inside a tower's footprint, each where the game lands it; and
 * a warp with a projectile aimed at the unit, which is refused.
 */
class BattleWarpTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The warp row the Boss Bandit's ability runs. */
  private static final String WARP = "BossBandit_ability_warp";

  /** Long enough for a Musketeer to deploy and fire. */
  private static final int TICKS = 120;

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    // Back toward its own side, on either side.
    "side 0, 0, 3500, 12000, 3500, 6000",
    "side 1, 1, 6000, 14000, 6000, 20000",
    // The river cell and the three after it are water: the first dry row, on the far bank.
    "water, 0, 6000, 22000, 6000, 17250",
    // Clamped to the edge, a corner cell that is blocked: two rows in.
    "edge, 0, 3500, 4000, 3500, 1250",
    // A tower's cells carry a lane, so they are not blocked: it lands inside the footprint.
    "into a tower, 1, 3500, 19500, 3500, 25500"
  })
  @DisplayName("a warp lands where the game lands it")
  void theWarpLandsWhereTheGameDoes(
      String name, int side, int x, int y, int landingX, int landingY) {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity bandit =
        match.deploy(0, GameData.unit("BossBandit"), LEVEL, side, x, y, "BossBandit");
    match.getBattle().step();
    assertThat(new int[] {bandit.getView().getX(), bandit.getView().getY()})
        .as("where it stands as it warps")
        .containsExactly(x, y);

    bandit.actionHolder().start(warp(match, bandit));

    assertThat(new int[] {bandit.getView().getX(), bandit.getView().getY()})
        .containsExactly(landingX, landingY);
  }

  @Test
  @DisplayName("a warp with a projectile aimed at the unit is refused")
  void aWarpWithAProjectileAimedAtTheUnitIsRefused() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    CharacterEntity bandit =
        match.deploy(0, GameData.unit("BossBandit"), LEVEL, 0, 3500, 12000, "BossBandit");
    bandit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    CharacterEntity musketeer =
        match.deploy(0, GameData.unit("Musketeer"), LEVEL, 1, 3500, 17000, "Musketeer");
    musketeer.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    for (int tick = 0; tick < TICKS && !aimedAt(match, bandit); tick++) {
      match.getBattle().step();
    }
    assertThat(aimedAt(match, bandit)).as("a projectile in flight at the Boss Bandit").isTrue();

    assertThatThrownBy(() -> bandit.actionHolder().start(warp(match, bandit)))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(WARP + " warps BossBandit with")
        .hasMessageContaining("aimed at it, whose drop no reference holds");
  }

  /** The warp row, built for the unit. */
  private static BattleAction warp(Standard1v1Battle match, CharacterEntity unit) {
    return GameData.actions().build(WARP, match.getWorld().binding(unit));
  }

  /** Whether a live projectile has the unit as its target. */
  private static boolean aimedAt(Standard1v1Battle match, CharacterEntity unit) {
    for (BattleEntity entity : match.getBattle().getHolder().entities()) {
      if (entity instanceof ProjectileEntity p && p.getTarget() == unit) {
        return true;
      }
    }
    return false;
  }
}
