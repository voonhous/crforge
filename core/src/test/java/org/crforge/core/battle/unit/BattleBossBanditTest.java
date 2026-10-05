package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionOwner;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The killer's hook where the reference runs do not take it: its place in a death, between the
 * dying unit's death slot and its death hooks, and a projectile's kill for a launcher with a
 * killed-done action, which is refused.
 */
class BattleBossBanditTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The killer's point, on the bottom side's left, at a cell's centre. */
  private static final int X = 3250;

  private static final int Y = 11250;

  /** How far ahead of it its victim stands: inside a melee reach, short of a dash. */
  private static final int AHEAD = 1000;

  /** Long enough for a deploy and a first hit. */
  private static final int TICKS = 120;

  @Test
  @DisplayName(
      "a kill schedules the killer's killed-done check after the dying unit's death damage and"
          + " before its death hooks, and the check runs in the killer's next pending pass")
  void theHookComesBetweenTheDeathSlotAndTheDeathHooks() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    List<String> log = new ArrayList<>();
    match.getWorld().addObserver(observer(log));
    CharacterEntity bandit =
        match.deploy(0, GameData.unit("BossBandit"), LEVEL, 0, X, Y, "BossBandit");
    bandit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    CharacterEntity golemite =
        match.deploy(0, GameData.unit("Golemite"), LEVEL, 1, X, Y + AHEAD, "Golemite");
    golemite.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    golemite.getHitPoints().setHitPoints(1);

    for (int tick = 0; tick < TICKS; tick++) {
      match.getBattle().step();
      if (log.stream().anyMatch(line -> line.startsWith("checked"))) {
        break;
      }
    }

    assertThat(log)
        .containsExactly(
            "hit Golemite",
            "area Golemite BossBandit",
            "killed_done BossBandit Golemite BossBandit_won_against_bandit_check false",
            "checked BossBandit BossBandit_won_against_bandit_check Golemite Golemite null");
  }

  @Test
  @DisplayName(
      "a killer's hook is scheduled before the death hooks of the unit it killed, and each check"
          + " misses a Boss Bandit")
  void theKillersHookComesBeforeTheKilledUnitsHooks() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    List<String> log = new ArrayList<>();
    match.getWorld().addObserver(observer(log));
    CharacterEntity blue =
        match.deploy(0, GameData.unit("BossBandit"), LEVEL, 0, X, Y, "BlueBandit");
    blue.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    CharacterEntity red =
        match.deploy(0, GameData.unit("BossBandit"), LEVEL, 1, X, Y + AHEAD, "RedBandit");
    red.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    red.getHitPoints().setHitPoints(1);

    for (int tick = 0; tick < TICKS; tick++) {
      match.getBattle().step();
      if (log.stream().filter(line -> line.startsWith("checked")).count() == 2) {
        break;
      }
    }

    assertThat(log)
        .containsExactly(
            "hit RedBandit",
            "killed_done BlueBandit RedBandit BossBandit_won_against_bandit_check false",
            "death_hooks RedBandit BlueBandit [BossBandit_defeated_by_bandit_check]",
            // The red one, killed, is still visited for the rest of the tick and lands its hit.
            "hit BlueBandit",
            "checked BlueBandit BossBandit_won_against_bandit_check RedBandit BossBandit null",
            "checked RedBandit BossBandit_defeated_by_bandit_check BlueBandit BossBandit null");
  }

  @Test
  @DisplayName(
      "a projectile's kill reaches its launcher: the Musketeer's killed-done action is scheduled"
          + " on it, the Knight its cause")
  void aProjectileKillReachesItsLauncher() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    List<String> log = new ArrayList<>();
    match.getWorld().addObserver(observer(log));
    UnitData shooter =
        GameData.unit("Musketeer").toBuilder()
            .onKilledDoneAction("BossBandit_won_against_bandit_check")
            .build();
    CharacterEntity musketeer = match.deploy(0, shooter, LEVEL, 0, X, Y, "Musketeer");
    musketeer.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    CharacterEntity knight = match.deploy(0, GameData.unit("Knight"), LEVEL, 1, X, Y + 4000);
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    knight.getHitPoints().setHitPoints(1);

    for (int tick = 0; tick < TICKS; tick++) {
      match.getBattle().step();
    }
    // Scheduled outside a pending pass, from the projectile's hit, and checked as it runs.
    assertThat(log)
        .contains(
            "killed_done Musketeer Knight BossBandit_won_against_bandit_check false",
            "checked Musketeer BossBandit_won_against_bandit_check Knight Knight null");
  }

  /** Logs every hit, the area hits among them, every hook scheduled and every check of a cause. */
  private static WorldObserver observer(List<String> log) {
    return new WorldObserver() {
      @Override
      public void damageDealt(int tick, WorldEntity target, int damage, DamageResult result) {
        log.add("hit " + target.name());
      }

      @Override
      public void areaHit(
          int tick,
          WorldEntity attacker,
          WorldEntity victim,
          int damage,
          int hitId,
          DamageResult result) {
        log.add("area " + attacker.name() + " " + victim.name());
      }

      @Override
      public void killedDoneScheduled(
          int tick, WorldEntity killer, WorldEntity killed, String action, boolean inPendingPass) {
        log.add(
            "killed_done %s %s %s %s"
                .formatted(killer.name(), killed.name(), action, inPendingPass));
      }

      @Override
      public void deathHooksScheduled(
          int tick,
          WorldEntity dying,
          BattleEntity attacker,
          int side,
          List<String> hooks,
          boolean inPendingPass) {
        log.add(
            "death_hooks %s %s %s".formatted(dying.name(), ((WorldEntity) attacker).name(), hooks));
      }

      @Override
      public void instigatorChecked(
          int tick, WorldEntity owner, String action, ActionOwner instigator, String scheduled) {
        log.add(
            "checked %s %s %s %s %s"
                .formatted(
                    owner.name(),
                    action,
                    ((WorldEntity) instigator).name(),
                    instigator.actionRowName(),
                    scheduled));
      }
    };
  }
}
