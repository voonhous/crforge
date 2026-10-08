package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.TargetIndicatorAttack;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.combat.RarityTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Goblin Machine's rocket where the reference runs do not reach, in the native cases stun,
 * moved and killed_in_flight: a machine with a Knight in melee and a Musketeer behind it, every
 * tick twenty later than there, where the units are placed out of their deploy. A Zap ends the
 * attack and the machine marks the Musketeer again as it comes back; a Musketeer moved off after it
 * was marked is missed; the machine killed with its rocket in flight stops its run and ends its
 * signal, and the rocket still lands. A signal whose maker leaves before it is admitted is refused.
 * These scenes write every column their logs are read from: the machine's row, its rocket attack
 * and its signal, the rocket, the Knight, the Musketeer and the Zap.
 */
class BattleTargetIndicatorTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The rocket's damage, as the scenes write it. */
  private static final int ROCKET_DAMAGE = 119;

  /** The rocket's damage at {@link #LEVEL} of its Common row: ten steps up. */
  private static final int ROCKET_HIT = ROCKET_DAMAGE * RarityTable.COMMON.multiplier(9) / 100;

  /** The configured tables with the scenes' columns written. */
  private static GameTables written(Path folder) throws IOException {
    GameData.altered(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "GoblinMachine")
              .put("Hitpoints", 885)
              .put("Damage", 91)
              .put("HitSpeed", 1200)
              .put("LoadTime", 700)
              .put("AttackFinishTime", 500)
              .put("AttackStateCount", 2)
              .put("Speed", 60)
              .put("Mass", 18)
              .put("CollisionRadius", 750)
              .put("Range", 1200)
              .put("SightRange", 5500)
              .put("DeployTime", 1000)
              .put("ProjectileStartRadius", 500)
              .put("ProjectileStartZ", 1900);
          GameData.columns(rows, "Knight")
              .put("Hitpoints", 690)
              .put("Damage", 79)
              .put("HitSpeed", 1200)
              .put("LoadTime", 700)
              .put("Speed", 60)
              .put("Mass", 6)
              .put("CollisionRadius", 500)
              .put("Range", 1200)
              .put("SightRange", 5500)
              .put("DeployTime", 1000);
          GameData.columns(rows, "Musketeer")
              .put("Hitpoints", 282)
              .put("HitSpeed", 1000)
              .put("LoadTime", 300)
              .put("Speed", 60)
              .put("Mass", 5)
              .put("CollisionRadius", 500)
              .put("Range", 6000)
              .put("SightRange", 6000)
              .put("DeployTime", 1000)
              .put("DeployDelay", 300)
              .put("ProjectileStartRadius", 450)
              .put("ProjectileStartZ", 450);
        });
    GameData.alterLoaded(
        folder,
        "actions",
        actions ->
            ((ObjectNode) actions.get("goblin_machine_rocket").get("fields"))
                .put("AttackCooldown", 4000)
                .put("AttackDelay", 1000)
                .put("LoadTime", 1500)
                .put("MinimumRange", 2500)
                .put("ProjectileOffsetToCharacterLookDirection", -1200)
                .put("ProjectileStartZ", 5000)
                .put("Range", 5000)
                .put("TargetIndicatorDelay", 0));
    GameData.alterLoaded(
        folder,
        "projectiles",
        rows ->
            GameData.columns(rows, "GoblinMachineRocketProjectile")
                .put("Damage", ROCKET_DAMAGE)
                .put("Radius", 1500)
                .put("Speed", 350)
                .put("Gravity", 44));
    GameData.alterLoaded(
        folder,
        "area_effect_objects",
        rows -> {
          GameData.columns(rows, "goblin_machine_rocket_target_signal").put("LifeDuration", 9999);
          GameData.columns(rows, "Zap")
              .put("BuffTime", 500)
              .put("Radius", 2500)
              .put("LifeDuration", 1)
              .putObject("Damage")
              .put("BaseDamage", 75)
              .put("TowerDamage", 19);
        });
    return GameTables.load(folder);
  }

  /** A battle with the towers holding fire, the machine, the Knight and the Musketeer. */
  private static final class Scene {
    final Standard1v1Battle match;
    final CharacterEntity machine;
    final CharacterEntity knight;
    final CharacterEntity musketeer;
    final List<String> log = new ArrayList<>();
    int tick;

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      machine = match.deploy(0, unit("GoblinMachine"), LEVEL, 0, 3500, 12000, "M");
      knight = match.deploy(0, unit("Knight"), LEVEL, 1, 3500, 13600, "K");
      musketeer = match.deploy(0, unit("Musketeer"), LEVEL, 1, 3500, 16500, "U");
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void targetIndicatorLogged(
                    int t, CharacterEntity unit, TargetIndicatorAttack.Event event) {
                  if (event instanceof TargetIndicatorAttack.Stepped e) {
                    log.add(
                        "%d step %s load %d %d cooldown %d %d"
                            .formatted(
                                tick,
                                e.calls(),
                                e.before().loadMs(),
                                e.after().loadMs(),
                                e.before().cooldownMs(),
                                e.after().cooldownMs()));
                  } else if (event instanceof TargetIndicatorAttack.Shot e) {
                    log.add(
                        "%d shoot from %d %d %d aim %d %d"
                            .formatted(tick, e.x(), e.y(), e.z(), e.aimX(), e.aimY()));
                  } else if (event instanceof TargetIndicatorAttack.SignalEnded) {
                    log.add(tick + " signal_ended");
                  } else if (event instanceof TargetIndicatorAttack.Stopped e) {
                    log.add("%d stop %s".formatted(tick, e.calls()));
                  }
                }

                @Override
                public void projectileImpacted(
                    int t,
                    ProjectileEntity projectile,
                    WorldEntity target,
                    int damage,
                    DamageResult result) {
                  if (projectile.getData().name().equals("GoblinMachineRocketProjectile")) {
                    log.add("%d impact %s %d".formatted(tick, target.name(), damage));
                  }
                }
              });
    }

    UnitData unit(String row) {
      return match.getWorld().getRecords().unit(row);
    }

    void stepTo(int last) {
      for (; tick <= last; tick++) {
        match.getBattle().step();
      }
    }
  }

  @Test
  @DisplayName(
      "a Zap on the machine ends the attack and its signal on the next tick and holds the load;"
          + " ten ticks on the machine marks the Musketeer again and shoots twenty after that")
  void aStunEndsTheAttack(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(written(folder));
    scene.match.placeAreaEffect(60, "Zap", LEVEL, 1, 3500, 12000, "zap");
    scene.stepTo(91);
    assertThat(scene.log)
        .containsExactly(
            "50 step [find 5000008, begin, signal 0 5000008 3000000] load 1500 1550 cooldown -1 -1",
            "61 signal_ended",
            "61 step [abort 1, remove 0 3000000] load 2050 2050 cooldown -1 -1",
            "71 step [find 5000008, begin, signal 0 5000008 3000002] load 2050 2100 cooldown -1 -1",
            "91 shoot from 3500 10800 5000 aim 3500 16500",
            "91 step [shoot 0 3000002 4000003 3500 10800 5000] load 3050 3100 cooldown -1 50");
  }

  @Test
  @DisplayName(
      "a Musketeer moved 3000 aside after it was marked is missed: the rocket flies to the"
          + " signal's point and hits nobody")
  void aTargetThatWalksOffIsMissed(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(written(folder));
    scene.stepTo(59);
    scene.musketeer.getView().setX(6500);
    scene.stepTo(95);
    assertThat(scene.log)
        .containsExactly(
            "50 step [find 5000008, begin, signal 0 5000008 3000000] load 1500 1550 cooldown -1 -1",
            "70 shoot from 3500 10800 5000 aim 3500 16500",
            "70 step [shoot 0 3000000 4000002 3500 10800 5000] load 2500 2550 cooldown -1 50",
            // The rocket, at Speed 350, lands 18 steps after the shot: the load and cooldown run on
            // 50 a step from 2550 and 50.
            "88 signal_ended",
            "88 step [finished 0 done, remove 0 3000000] load 3400 3450 cooldown 900 950");
    assertThat(scene.musketeer.getHitPoints().getHitPoints())
        .isEqualTo(scene.musketeer.getHitPoints().getMaximum());
  }

  @Test
  @DisplayName(
      "the machine killed with its rocket in flight stops its run as it leaves, which ends its"
          + " signal; the rocket still lands on the Musketeer")
  void theRunStopsAsTheMachineDies(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(written(folder));
    scene.stepTo(79);
    scene.match.getWorld().kill(scene.machine, null);
    scene.stepTo(93);
    assertThat(scene.log)
        .containsExactly(
            "50 step [find 5000008, begin, signal 0 5000008 3000000] load 1500 1550 cooldown -1 -1",
            "70 shoot from 3500 10800 5000 aim 3500 16500",
            "70 step [shoot 0 3000000 4000002 3500 10800 5000] load 2500 2550 cooldown -1 50",
            "80 signal_ended",
            "80 stop [stop 1, remove 0 3000000]",
            "87 impact U " + ROCKET_HIT);
  }

  @Test
  @DisplayName(
      "a signal whose machine leaves on the tick it is made would be destroyed unread as it is"
          + " admitted, which is refused")
  void aSignalWhoseMakerLeftIsRefused(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(written(folder));
    scene
        .match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void targetIndicatorLogged(
                  int t, CharacterEntity unit, TargetIndicatorAttack.Event event) {
                if (event instanceof TargetIndicatorAttack.Signalled) {
                  scene.match.getWorld().kill(unit, null);
                }
              }
            });
    scene.stepTo(49);
    assertThatThrownBy(() -> scene.stepTo(50))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("admitted after its maker left");
  }

  @Test
  @DisplayName(
      "a machine fighting a princess tower marks the king beyond the ring's outer circle: the"
          + " query lists the king by its square, and the finder's ring test asks only the inner"
          + " edge")
  void theKingIsMarkedByItsSquare() {
    Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    // 2244 from the left princess tower, inside the ring's inner edge and in melee reach, and
    // 7689 from the king's centre, beyond its radius plus the 5750 outer reach: the point clamped
    // into the king's square is 5713 away, within the query's 5750.
    CharacterEntity machine =
        match.deploy(0, GameData.unit("GoblinMachine"), LEVEL, 0, 3857, 23284, "M");
    TowerEntity king = match.getWorld().kingTower(1);
    List<Integer> marked = new ArrayList<>();
    match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void targetIndicatorLogged(
                  int t, CharacterEntity unit, TargetIndicatorAttack.Event event) {
                if (event instanceof TargetIndicatorAttack.Signalled e) {
                  marked.add(e.target());
                }
              }
            });
    for (int tick = 0; tick < 80; tick++) {
      match.getBattle().step();
    }
    assertThat(new int[] {machine.getView().getX(), machine.getView().getY()})
        .containsExactly(3857, 23284);
    assertThat(marked).isNotEmpty().allMatch(id -> id == king.getId());
  }
}
