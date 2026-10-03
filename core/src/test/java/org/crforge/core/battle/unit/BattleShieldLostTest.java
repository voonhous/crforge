package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.RowAction;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The action a unit runs as its shield breaks, and the charge range a buff gives, where the
 * reference runs do not take them: the cause each path names, a break inside a pending pass, a
 * kill's break, the buff's source, the charge reset as the buff is listed, and the refusals.
 */
class BattleShieldLostTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** A battle with the towers passive, and every action a broken shield scheduled. */
  private static final class Scene {
    final Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    final List<String> scheduled = new ArrayList<>();

    Scene() {
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void shieldLostScheduled(
                    int tick,
                    WorldEntity unit,
                    String action,
                    SpawnHost cause,
                    boolean inPendingPass) {
                  scheduled.add(
                      "%s %s %s %b"
                          .formatted(
                              unit.name(),
                              action,
                              cause == null ? null : cause.name(),
                              inPendingPass));
                }
              });
    }

    /** A unit placed on tick 0 that never moves, under a name of its own. */
    CharacterEntity still(int side, String row, int x, int y, String name) {
      CharacterEntity unit = match.deploy(0, GameData.unit(row), LEVEL, side, x, y, name);
      unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      return unit;
    }

    /** Steps past every unit's deploy. */
    void deployed() {
      for (int i = 0; i < 30; i++) {
        match.getBattle().step();
      }
    }

    BattleWorld world() {
      return match.getWorld();
    }
  }

  /** The names of the actions queued on a holder. */
  private static List<String> queued(ActionHolder holder) {
    return holder.queued().stream().map(q -> q.action().name() + " " + q.ticks()).toList();
  }

  @Test
  @DisplayName(
      "a hit that breaks the shield outside a pending pass queues the row's action with no ticks"
          + " on the unit, the hitting unit its cause; a hit short of it queues nothing")
  void aBreakQueuesTheAction() {
    Scene scene = new Scene();
    CharacterEntity wizard = scene.still(0, "Wizard_EV1", 3500, 12000, "w");
    CharacterEntity knight = scene.still(1, "Knight", 3500, 24000, "k");
    scene.deployed();
    int shield = wizard.getHitPoints().getShield();

    scene.world().dealDamage(knight, wizard.getTargetView(), shield - 1, 0, 1);
    assertThat(scene.scheduled).isEmpty();

    int hitPoints = wizard.getHitPoints().getHitPoints();
    scene.world().dealDamage(knight, wizard.getTargetView(), 500, 0, 1);

    assertThat(wizard.getHitPoints().getShield()).isZero();
    assertThat(wizard.getHitPoints().getHitPoints())
        .as("the excess past the shield is lost")
        .isEqualTo(hitPoints);
    assertThat(scene.scheduled).containsExactly("w Wizard_EV1_ShieldLost k false");
    assertThat(queued(wizard.actionHolder()))
        .containsExactly("Wizard_EV1_ShieldLost 0", "Wizard_EV1_ShieldLostAoE 0");
    assertThat(wizard.actionHolder().queuedInstigators())
        .containsExactly(knight.actionHolder(), knight.actionHolder());
  }

  @Test
  @DisplayName("a unit whose row has no such action breaks its shield and schedules nothing")
  void aRowWithoutTheActionSchedulesNothing() {
    Scene scene = new Scene();
    CharacterEntity prince = scene.still(0, "DarkPrince", 3500, 12000, "p");
    CharacterEntity knight = scene.still(1, "Knight", 3500, 24000, "k");
    scene.deployed();

    scene.world().dealDamage(knight, prince.getTargetView(), 1000, 0, 1);

    assertThat(prince.getHitPoints().getShield()).isZero();
    assertThat(scene.scheduled).isEmpty();
    assertThat(queued(prince.actionHolder())).isEmpty();
  }

  @Test
  @DisplayName(
      "a kill with the shield up breaks only the shield, the unit lives, and the killer is the"
          + " cause; the king's circle names none")
  void aKillBreaksTheShield() {
    Scene scene = new Scene();
    CharacterEntity wizard = scene.still(0, "Wizard_EV1", 3500, 12000, "w");
    CharacterEntity recruit = scene.still(0, "Recruit_EV1", 6500, 12000, "r");
    CharacterEntity knight = scene.still(1, "Knight", 3500, 24000, "k");
    scene.deployed();

    scene.world().kill(wizard, knight);
    scene.world().circleKill(recruit, 1000);

    assertThat(wizard.getHitPoints().getHitPoints()).isPositive();
    assertThat(recruit.getHitPoints().getHitPoints()).isPositive();
    assertThat(scene.scheduled)
        .containsExactly("w Wizard_EV1_ShieldLost k false", "r Recruit_EV1_StartCharge null false");
  }

  @Test
  @DisplayName(
      "a break inside a pending pass starts the action at once, its part with it: nothing is"
          + " queued and the explosion is made")
  void aBreakInsideAPendingPassStartsAtOnce() {
    Scene scene = new Scene();
    CharacterEntity wizard = scene.still(0, "Wizard_EV1", 3500, 12000, "w");
    CharacterEntity knight = scene.still(1, "Knight", 3500, 24000, "k");
    scene.deployed();
    List<String> made = new ArrayList<>();
    scene
        .world()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaEffectCreated(
                  int tick, AreaEffectEntity areaEffect, String how, String source) {
                made.add(areaEffect.getData().name() + " " + areaEffect.side());
              }
            });
    // An action of the Knight's that hits the Wizard as it runs, in the Knight's pending pass.
    knight
        .actionHolder()
        .schedule(
            new RowAction(ActionRow.named("hit")) {
              @Override
              public ActionInstance start(ActionHolder holder) {
                scene.world().dealDamage(knight, wizard.getTargetView(), 1000, 0, 1);
                return null;
              }
            },
            0);

    scene.match.getBattle().step();

    assertThat(scene.scheduled).containsExactly("w Wizard_EV1_ShieldLost k true");
    assertThat(queued(wizard.actionHolder())).isEmpty();
    assertThat(made).containsExactly("Wizard_EV1_ShieldLostExplosion 0");
  }

  @Test
  @DisplayName(
      "the Recruit's charge buff is its own, whatever caused the action: its source, level and"
          + " side; listing it resets the charge from none to 0, and every reset after leaves 0")
  void theChargeBuff() {
    Scene scene = new Scene();
    CharacterEntity recruit = scene.still(0, "Recruit_EV1", 3500, 12000, "r");
    CharacterEntity knight = scene.still(1, "Knight", 3500, 24000, "k");
    scene.deployed();
    List<String> resets = new ArrayList<>();
    scene
        .world()
        .addObserver(
            new WorldObserver() {
              @Override
              public void buffChargeReset(
                  int tick, CharacterEntity unit, BuffInstance instance, int before, int after) {
                resets.add(
                    "%s %s %d %d".formatted(unit.name(), instance.getBuff().name(), before, after));
              }
            });
    assertThat(recruit.getUnit().movement().getChargeProgress())
        .isEqualTo(MovementState.CHARGE_INACTIVE);

    recruit
        .actionHolder()
        .start(
            scene
                .world()
                .getActions()
                .build("Recruit_EV1_StartCharge", scene.world().binding(recruit)),
            knight.actionHolder());

    assertThat(recruit.getBuffs().items())
        .extracting(
            i ->
                "%s %s %d %d"
                    .formatted(
                        i.getBuff().name(), i.getSource().name(), i.getSide(), i.getPackedLevel()))
        .containsExactly("RecruitsCharge_EV1 r 0 %d".formatted(recruit.getPackedLevel()));
    assertThat(resets).containsExactly("r RecruitsCharge_EV1 -1 0");
    assertThat(recruit.getBuffs().overrideChargeRange()).isEqualTo(250);
    recruit.getUnit().movement().setChargeProgress(4000);
    recruit.resetCharge();
    assertThat(recruit.getUnit().movement().getChargeProgress()).isZero();
  }

  @Test
  @DisplayName(
      "a swap and a clone's copy while a charge range buff is listed, its removal and the buff on"
          + " a unit that fires are refused")
  void theChargeBuffRefusals() {
    Scene scene = new Scene();
    CharacterEntity recruit = scene.still(0, "Recruit_EV1", 3500, 12000, "r");
    CharacterEntity musketeer = scene.still(0, "Musketeer", 6500, 12000, "m");
    CharacterEntity knight = scene.still(1, "Knight", 3500, 24000, "k");
    scene.deployed();
    BuffData charge = scene.world().buffData("RecruitsCharge_EV1");
    recruit.getBuffs().apply(charge, 999999, recruit.getPackedLevel(), recruit, 0);

    assertThatThrownBy(() -> recruit.changeData("Recruit", false))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("a charge or a river jump");
    assertThatThrownBy(() -> knight.getBuffs().copyFrom(recruit.getBuffs()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("which gives a charge range, not modelled");
    assertThatThrownBy(() -> recruit.getBuffs().removeRow("RecruitsCharge_EV1"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("which gives a charge range, not modelled");
    assertThatThrownBy(
            () ->
                musketeer
                    .getBuffs()
                    .apply(charge, 999999, musketeer.getPackedLevel(), musketeer, 0))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("a charge range on a unit that fires");
  }
}
