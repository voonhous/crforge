package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An area effect's projectile where the reference runs leave it: whom the chooser of a Lightning
 * picks, a hit with nobody to strike, the update's end skipped then, and the area effect as the
 * projectile's launcher.
 */
class BattleAreaEffectLaunchTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** A tick after every unit placed on the first one has deployed. */
  private static final int CAST_TICK = 25;

  /** The Lightning's point, on the bottom side's left, away from every tower. */
  private static final int X = 3500;

  private static final int Y = 11000;

  /** A battle with the towers passive that logs every launch of an area effect. */
  private static final class Scene {
    final Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    final List<AreaEffectEntity.Choice> choices = new ArrayList<>();
    final List<ProjectileEntity> launched = new ArrayList<>();
    final List<String> impacts = new ArrayList<>();

    Scene() {
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void areaEffectLaunched(
                    int tick,
                    AreaEffectEntity areaEffect,
                    int hit,
                    int bound,
                    AreaEffectEntity.Choice choice,
                    ProjectileEntity projectile) {
                  choices.add(choice);
                  if (projectile != null) {
                    launched.add(projectile);
                  }
                }

                @Override
                public void projectileImpacted(
                    int tick,
                    ProjectileEntity projectile,
                    WorldEntity target,
                    int damage,
                    DamageResult result) {
                  impacts.add(target.name() + " " + damage);
                }
              });
    }

    /** A unit placed at a tick that never moves, under a name of its own. */
    CharacterEntity still(int tick, int side, String row, int x, int y, String name) {
      CharacterEntity unit = match.deploy(tick, GameData.unit(row), LEVEL, side, x, y, name);
      unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      return unit;
    }

    /** The ticks stepped so far. */
    int tick;

    /** Casts a Lightning of side 1 at the point and steps through its life and one tick more. */
    void lightning(int castTick) {
      match.placeAreaEffect(castTick, "Lightning", LEVEL, 1, X, Y, "L");
      step(castTick + 31 - tick);
    }

    void lightning() {
      lightning(CAST_TICK);
    }

    void step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        match.getBattle().step();
        tick++;
      }
    }

    List<String> chosen() {
      return choices.stream().map(c -> c.chosen() == null ? null : c.chosen().name()).toList();
    }
  }

  @Test
  @DisplayName(
      "each hit strikes the enemy with the most hit points and shield not struck before, the first"
          + " of equals kept")
  void theBiggestNotStruckBefore() {
    Scene scene = new Scene();
    scene.still(0, 0, "Musketeer", X - 1000, Y, "Musketeer");
    scene.still(0, 0, "DeliveryRecruit", X + 1000, Y, "Recruit");
    scene.still(0, 0, "Knight", X, Y - 1000, "first");
    scene.still(0, 0, "Knight", X, Y + 1000, "second");
    scene.lightning();

    // The Recruit's shield lifts it above the Musketeer: 547 and 240 against 721.
    assertThat(scene.chosen()).containsExactly("first", "second", "Recruit");
    assertThat(scene.choices.get(2).candidates())
        .extracting(c -> c.target().name() + " " + c.size())
        .containsExactly("Musketeer 721", "Recruit 787");
    assertThat(scene.launched)
        .extracting(p -> p.getTarget().name())
        .containsExactly("first", "second", "Recruit");
  }

  @Test
  @DisplayName(
      "the chooser passes a friend and a unit outside its circle by; with nobody left a hit"
          + " launches nothing")
  void onlyTheEnemiesInItsCircle() {
    Scene scene = new Scene();
    scene.still(0, 1, "Knight", X, Y + 1000, "friend");
    scene.still(0, 0, "Golem", X + 6000, Y, "far");
    scene.still(0, 0, "Minion", X - 1000, Y, "minion");
    scene.lightning();

    assertThat(scene.chosen()).containsExactly("minion", null, null);
    assertThat(scene.choices.get(0).refused()).extracting(WorldEntity::name).contains("friend");
    assertThat(scene.launched).hasSize(1);
  }

  @Test
  @DisplayName("a hidden Tesla is no candidate for a Lightning, which does not reach hidden units")
  void aHiddenTeslaIsPassedBy() {
    Scene scene = new Scene();
    CharacterEntity tesla = scene.still(0, 0, "Tesla", X + 1500, Y, "tesla");
    scene.still(0, 0, "Minion", X - 1500, Y, "minion");
    // Its deploy ends on the twentieth step, and it hides some sixteen visits later.
    while (scene.tick < 60 && !tesla.hidden()) {
      scene.step(1);
    }
    assertThat(tesla.hidden()).isTrue();
    scene.lightning(scene.tick);

    assertThat(scene.choices.get(0).candidates())
        .extracting(c -> c.target().name())
        .containsExactly("minion");
  }

  @Test
  @DisplayName(
      "a hit with nobody to strike is lost: a unit placed in the circle after it is struck by the"
          + " next")
  void aHitWithNobodyIsLost() {
    Scene scene = new Scene();
    // The first hit falls on the cast tick and nine more.
    scene.still(CAST_TICK + 12, 0, "Knight", X, Y, "late");
    scene.lightning();

    assertThat(scene.chosen()).containsExactly(null, "late", null);
    assertThat(scene.launched).hasSize(1);
    assertThat(scene.impacts).containsExactly("late 1057");
  }

  @Test
  @DisplayName(
      "an update whose launch found nobody ends there: the life-end action on its last update is"
          + " not scheduled")
  void aLaunchWithNobodySkipsTheLifeEndAction() {
    for (boolean anyone : new boolean[] {false, true}) {
      Scene scene = new Scene();
      // One enemy for each hit, since none is struck twice.
      if (anyone) {
        for (int i = 0; i < 3; i++) {
          scene.still(0, 0, "Knight", X - 1000 + 1000 * i, Y, "knight_" + i);
        }
      }
      scene.step(CAST_TICK);
      // A Lightning whose third hit falls on its last update, with a life-end action.
      AreaEffectData row =
          GameData.records().areaEffect("Lightning").toBuilder()
              .hitSpeedMs(500)
              .onLifeTimeEndAction("goblin_machine_signal_core")
              .build();
      BattleWorld world = scene.match.getWorld();
      AreaEffectEntity lightning =
          new AreaEffectEntity(
              world, row, 1, X, Y, PackedLevel.pack(LEVEL - 1, row.rarity()), null, null);
      world.getHolder().add(lightning);
      lightning.setName("L");
      // What its holder has queued or started by the time it leaves.
      List<String> scheduled = new ArrayList<>();
      lightning
          .actionHolder()
          .setListener(
              new ActionHolder.Listener() {
                @Override
                public void starting(BattleAction action, int phase, boolean queued) {
                  scheduled.add("started");
                }
              });
      world.addObserver(
          new WorldObserver() {
            @Override
            public void areaEffectRemoved(int tick, AreaEffectEntity areaEffect) {
              areaEffect.actionHolder().queued().forEach(q -> scheduled.add("queued"));
            }
          });
      scene.step(32);

      assertThat(scene.choices).hasSize(3);
      assertThat(scheduled).as("anyone in the circle: " + anyone).hasSize(anyone ? 1 : 0);
    }
  }

  @Test
  @DisplayName(
      "a Royal Delivery's projectile has the area effect as its launcher and creator, forgotten as"
          + " it leaves")
  void theAreaEffectIsTheLauncher() {
    Scene scene = new Scene();
    List<AreaEffectEntity> admitted = new ArrayList<>();
    scene
        .match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void areaEffectAdmitted(int tick, AreaEffectEntity areaEffect) {
                admitted.add(areaEffect);
              }
            });
    scene.match.placeAreaEffect(CAST_TICK, "RoyalDeliveryArea", LEVEL, 0, X, Y, "RD");
    // Its fortieth update launches; the same step's cleanup removes it.
    scene.step(CAST_TICK + 39);
    assertThat(scene.launched).isEmpty();
    ProjectileEntity[] seen = new ProjectileEntity[1];
    String[] names = new String[1];
    scene
        .match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileLaunched(int tick, ProjectileEntity projectile) {
                seen[0] = projectile;
                names[0] = projectile.launcherName();
                assertThat(projectile.getAreaLauncher()).isSameAs(admitted.get(0));
                assertThat(projectile.actionCreator()).isSameAs(admitted.get(0));
                assertThat(projectile.getOwner()).isNull();
                assertThat(projectile.getTarget()).isNull();
              }
            });
    scene.step(1);

    assertThat(names[0]).isEqualTo("RD");
    assertThat(seen[0].getAreaLauncher()).as("forgotten as it leaves").isNull();
    assertThat(seen[0].launcherName()).isNull();
    assertThat(seen[0].actionCreator()).isNull();
  }

  @Test
  @DisplayName(
      "an area effect whose projectile sets a column its impact does not model is refused as it is"
          + " created")
  void aLaunchedRowNotModelledIsRefused(@TempDir Path folder) throws IOException {
    Standard1v1Battle match =
        new Standard1v1Battle(
            GameData.altered(
                folder,
                "projectiles",
                rows -> GameData.columns(rows, "LighningSpell").put("MinPushback", 100)),
            LEVEL,
            false);
    match.placeAreaEffect(CAST_TICK, "Lightning", LEVEL, 1, X, Y, "L");

    assertThatThrownBy(
            () -> {
              for (int i = 0; i <= CAST_TICK; i++) {
                match.getBattle().step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("launches LighningSpell");
  }
}
