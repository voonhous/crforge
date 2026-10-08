package org.crforge.core.battle.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.crforge.core.battle.Shipped.actionName;
import static org.crforge.core.battle.Shipped.actionNames;
import static org.crforge.core.battle.Shipped.number;
import static org.crforge.core.battle.Shipped.numbers;
import static org.crforge.core.battle.Shipped.text;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.AliveTimer;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.CreateParallelProjectiles;
import org.crforge.core.battle.action.ReadyChampionAbility;
import org.crforge.core.battle.action.SetIndicatorOnTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Action rows built under the class names the tables write: the alive timer without its Data
 * ending, the parallel projectiles of the hero Elite Archer's triple shot, and the ready action the
 * Mega Minion hero's mark runs as its target dies.
 */
class ActionClassNamesTest {

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
      "the alive timer's class name without its Data ending: the Baby Dragon evolution wind's"
          + " timer is built as the alive timer, with its ages and actions")
  void theAliveTimerIsBuiltUnderItsClassName() {
    String row = "wind_at_health_action";
    assertThat(GameData.tables().action(row).classType())
        .isEqualTo("ActionAeoRunActionAtAliveTimer");
    AliveTimer timer = (AliveTimer) rows().build(row, INERT_BINDING);
    assertThat(timer.getAgesMs()).containsExactlyElementsOf(numbers(row, "AliveTimeList"));
    assertThat(timer.getActions())
        .extracting(BattleAction::name)
        .containsExactlyElementsOf(actionNames(row, "Actions"));
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
    assertThat(died.name())
        .isEqualTo(actionName("MegaMinion_hero_mark_target", "OnTargetDiedAction"));
    assertThat(died.isForceCooldown()).isTrue();
  }

  @Test
  @DisplayName(
      "the hero Elite Archer's triple shot is an ActionCreateParallelProjectiles: it is built with"
          + " its projectile row, its count and its spread")
  void theTripleShotIsBuiltUnderItsClassName() {
    String row = "EliteArcherHero_Triple_Shot_Action";
    assertThat(GameData.tables().action(row).classType())
        .isEqualTo("ActionCreateParallelProjectiles");
    CreateParallelProjectiles shot = (CreateParallelProjectiles) rows().build(row, INERT_BINDING);
    assertThat(shot.getProjectile()).isEqualTo(text(row, "ProjectileType"));
    assertThat(shot.getCount()).isEqualTo(number(row, "ProjectileCount"));
    assertThat(shot.getDistance()).isEqualTo(number(row, "ProjectileDistance"));
  }
}
