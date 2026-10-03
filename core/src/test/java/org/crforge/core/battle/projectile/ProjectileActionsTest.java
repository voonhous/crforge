package org.crforge.core.battle.projectile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.crforge.core.battle.GameData;
import org.crforge.core.battle.unit.Standard1v1Battle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A projectile's own actions where no reference run shows them: the names its expressions may read,
 * and the swap of its row for one whose battle columns differ.
 */
class ProjectileActionsTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  private static ProjectileEntity axe(Standard1v1Battle match) {
    return new ProjectileEntity(
        match.getWorld(), GameData.records().projectile("AxeMan_EV1_Projectile_Strong1"), 0);
  }

  @Test
  @DisplayName(
      "a projectile's expressions read its sweep's distance from its start, and any other name is"
          + " refused as the row is built")
  void aProjectileAnswersItsDistanceOnly() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    ProjectileEntity axe = axe(match);
    ProjectileBinding binding = new ProjectileBinding(match.getWorld(), axe);
    axe.setPingpongDistance(2802);

    assertThat(binding.expression("get_ping_pong_projectile_distance > 2500").getAsInt())
        .isEqualTo(1);
    assertThat(binding.expression("get_ping_pong_projectile_distance").getAsInt()).isEqualTo(2802);
    assertThatThrownBy(() -> binding.expression("x"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("get_ping_pong_projectile_distance");
  }

  @Test
  @DisplayName(
      "the axe takes its other forms, which differ only in what they show; a row whose battle"
          + " columns differ is refused")
  void aSwapKeepsTheBattleColumns() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    ProjectileEntity axe = axe(match);

    axe.changeProjectileData("AxeMan_EV1_Projectile_Normal");
    assertThat(axe.getData().name()).isEqualTo("AxeMan_EV1_Projectile_Normal");
    axe.changeProjectileData("AxeMan_EV1_Projectile_Strong2");
    assertThat(axe.getData().name()).isEqualTo("AxeMan_EV1_Projectile_Strong2");
    assertThatThrownBy(() -> axe.changeProjectileData("AxeManProjectile"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("whose columns differ");
  }
}
