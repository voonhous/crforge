package org.crforge.core.battle.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.AliveTimer;
import org.crforge.core.battle.action.CreateParallelProjectiles;
import org.crforge.core.battle.action.ReadyChampionAbility;
import org.crforge.core.battle.action.SetIndicatorOnTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Action rows of the 16.402.18 tables whose class or columns differ from 14.593.1's. */
class Version16ActionRowsTest {

  /** A binding that compiles nothing: every expression answers 0, every variable its hash. */
  private static final ActionBinding INERT_BINDING =
      new ActionBinding() {
        @Override
        public IntSupplier expression(String text) {
          return () -> 0;
        }

        @Override
        public int variableKey(String name) {
          return name.hashCode();
        }

        @Override
        public LongSupplier tags() {
          return () -> 0;
        }
      };

  private static ActionRows rows() {
    GameTables tables = GameData.tables();
    return new ActionRows(tables, new BattleRecords(tables));
  }

  @Test
  @DisplayName(
      "the alive timer's class name lost its Data ending in 16.402.18: the Baby Dragon evolution"
          + " wind's timer is built as the same class, with its age and action")
  void theAliveTimerIsBuiltUnderItsNewClassName() {
    AliveTimer timer = (AliveTimer) rows().build("wind_at_health_action", INERT_BINDING);
    assertThat(timer.getAgesMs()).containsExactly(5950);
    assertThat(timer.getActions()).hasSize(1);
    assertThat(timer.getActions().get(0).name()).isEqualTo("baby_dragon_evo_wind_end_blow");
    assertThat(timer.isAllowRepeat()).isTrue();
  }

  @Test
  @DisplayName(
      "the Mega Minion hero's mark builds its died action, the ready action that forces the"
          + " cooldown")
  void theMarkBuildsItsDiedAction() {
    SetIndicatorOnTarget mark =
        (SetIndicatorOnTarget) rows().build("MegaMinion_hero_mark_target", INERT_BINDING);
    ReadyChampionAbility died = (ReadyChampionAbility) mark.columns().onTargetDied();
    assertThat(died.name()).isEqualTo("MegaMinion_hero_reset_ability");
    assertThat(died.isForceCooldown()).isTrue();
  }

  @Test
  @DisplayName(
      "the hero Elite Archer's triple shot is an ActionCreateParallelProjectiles in 16.402.18: it"
          + " is built with its projectile row, its count and its spread")
  void theTripleShotIsBuiltUnderItsNewClass() {
    CreateParallelProjectiles shot =
        (CreateParallelProjectiles)
            rows().build("EliteArcherHero_Triple_Shot_Action", INERT_BINDING);
    assertThat(shot.getProjectile()).isEqualTo("EliteArcherHero_Ability_Triple_Shot_Projectile");
    assertThat(shot.getCount()).isEqualTo(2);
    assertThat(shot.getDistance()).isEqualTo(1500);
  }
}
