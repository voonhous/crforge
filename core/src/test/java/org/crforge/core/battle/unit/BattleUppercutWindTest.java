package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.AliveTimer;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.InertAction;
import org.crforge.core.pathfinding.EntityFlags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Mega Knight's uppercut, the knock it gives, and the evolved Baby Dragon's wind where
 * the reference runs do not take them: the ends of the uppercut, its refusals, the knock's arc and
 * refusal, the wind on the other side, its re-trigger and a new wind after one has ended, a choice
 * by team without a cause, and the actions run at an area effect's ages.
 */
class BattleUppercutWindTest {

  /** Which bit of a tag word each flag is, as the configured tables number the game tags. */
  private static final EntityFlags BITS = EntityFlags.of(GameData.tables());

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** A battle with the towers passive, and what the uppercut, the knock and the wind did. */
  private static final class Scene {
    final Standard1v1Battle match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
    final List<String> log = new ArrayList<>();

    Scene() {
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void uppercutStarted(
                    int tick,
                    CharacterEntity unit,
                    String action,
                    int phase,
                    WorldEntity instigator,
                    WorldEntity target,
                    boolean finished) {
                  log.add("start " + unit.name() + " " + target.name() + " " + finished);
                }

                @Override
                public void uppercutStepped(
                    int tick,
                    CharacterEntity unit,
                    int delay,
                    boolean finished,
                    String outcome,
                    int[] pushPoint) {
                  log.add("update " + unit.name() + " " + outcome + " " + delay);
                }

                @Override
                public void tagWordChanged(int tick, WorldEntity entity, long word) {
                  log.add("tags " + entity.name() + " " + Long.toHexString(word));
                }

                @Override
                public void resetableStarted(
                    int tick,
                    CharacterEntity unit,
                    int phase,
                    WorldEntity instigator,
                    AreaEffectEntity areaEffect,
                    int x,
                    int y) {
                  log.add("wind " + areaEffect.name() + " " + x + " " + y);
                }

                @Override
                public void resetableRetriggered(
                    int tick,
                    CharacterEntity unit,
                    int phase,
                    String areaEffect,
                    Integer countdown) {
                  log.add("retrigger " + areaEffect + " " + countdown);
                }

                @Override
                public void resetableLeft(int tick, CharacterEntity unit, String areaEffect) {
                  log.add("left " + areaEffect);
                }

                @Override
                public void resetableEnded(int tick, CharacterEntity unit, String areaEffect) {
                  log.add("ended " + areaEffect);
                }

                @Override
                public void shapeListed(
                    int tick, AreaEffectEntity areaEffect, List<WorldEntity> listed) {
                  log.add("listed " + listed.stream().map(WorldEntity::name).toList());
                }

                @Override
                public void aliveTimerFired(int tick, AreaEffectEntity areaEffect, String action) {
                  log.add("age " + action);
                }
              });
    }

    /** A unit placed on tick 0 under a name of its own. */
    CharacterEntity unit(int side, String row, int x, int y, String name) {
      return match.deploy(0, GameData.unit(row), LEVEL, side, x, y, name);
    }

    /** Steps the battle the given number of ticks. */
    void step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        match.getBattle().step();
      }
    }

    BattleWorld world() {
      return match.getWorld();
    }

    /** An action row built for an entity. */
    BattleAction row(String name, WorldEntity entity) {
      return world().getActions().build(name, world().binding(entity));
    }

    /** The area effects in the battle. */
    List<AreaEffectEntity> areaEffects() {
      List<AreaEffectEntity> out = new ArrayList<>();
      match
          .getBattle()
          .getHolder()
          .entities()
          .forEach(
              e -> {
                if (e instanceof AreaEffectEntity a) {
                  out.add(a);
                }
              });
      return out;
    }
  }

  @Test
  @DisplayName(
      "an uppercut on a target without a movement component ends as it starts, the unit held for"
          + " one step and nothing pushed")
  void anUppercutOnATowerEndsAtOnce() {
    Scene scene = new Scene();
    CharacterEntity mk = scene.unit(0, "MegaKnight_EV1", 3500, 23500, "mk");
    scene.step(40);
    TowerEntity tower = scene.world().princessTowers(1).get(0);
    mk.getUnit().targeting().setReference(tower.getTargetView());
    scene.log.clear();

    mk.actionHolder().start(scene.row("MegaKnight_EV1_uppercut", mk), tower.actionHolder());
    scene.step(2);

    assertThat(scene.log)
        .containsExactly(
            "start mk " + tower.name() + " true",
            "tags mk " + Long.toHexString(BITS.noMove() | BITS.noAttack()),
            "tags mk 0");
  }

  @Test
  @DisplayName(
      "the uppercut's hold ends as the unit attacks another target in range: the push, then"
          + " the end")
  void theHoldEndsWithAnotherTargetInRange() {
    Scene scene = new Scene();
    CharacterEntity mk = scene.unit(0, "MegaKnight_EV1", 3500, 14000, "mk");
    CharacterEntity knight = scene.unit(1, "Knight", 3500, 15600, "k");
    scene.unit(1, "Knight", 4800, 14000, "near");
    // Just deployed, before its first hit; held where it stands.
    scene.step(22);
    mk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    mk.getUnit().targeting().setReference(knight.getTargetView());
    scene.log.clear();

    mk.actionHolder().start(scene.row("MegaKnight_EV1_uppercut", mk), knight.actionHolder());
    scene.step(1);
    assertThat(knight.getUnit().movement().getAttackPushback()).as("an attack's push").isEqualTo(1);
    scene.step(2);

    // The step after the push keeps the knocked Knight under LOCK_TARGET; the next takes the
    // other Knight, in range.
    assertThat(scene.log.stream().filter(line -> line.startsWith("update")))
        .containsExactly(
            "update mk pushed 1000",
            "update mk waiting 950",
            "update mk other target in range 950");
  }

  @Test
  @DisplayName(
      "the uppercut's push keeps the pushed unit's avoidance blend in a data version whose"
          + " uppercut has no switch to clear it")
  void thePushKeepsTheBlendWithoutTheSwitch() {
    Scene scene = new Scene();
    CharacterEntity mk = scene.unit(0, "MegaKnight_EV1", 3500, 14000, "mk");
    CharacterEntity knight = scene.unit(1, "Knight", 3500, 15600, "k");
    // Just deployed, before its first hit; held where it stands.
    scene.step(22);
    mk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    mk.getUnit().targeting().setReference(knight.getTargetView());
    mk.actionHolder().start(scene.row("MegaKnight_EV1_uppercut", mk), knight.actionHolder());
    knight.getUnit().movement().setAvoidanceBlend(-150);

    scene.step(1);

    assertThat(knight.getUnit().movement().getAttackPushback()).as("an attack's push").isEqualTo(1);
    assertThat(scene.world().uppercutResetsAvoidance()).isFalse();
    // One walking step's decay before the push, none after it.
    assertThat(knight.getUnit().movement().getAvoidanceBlend()).isEqualTo(-140);
  }

  @Test
  @DisplayName(
      "an uppercut started with no current target is refused: the run would read the targeting"
          + " component's previous reference")
  void anUppercutWithoutATargetIsRefused() {
    Scene scene = new Scene();
    CharacterEntity mk = scene.unit(0, "MegaKnight_EV1", 3500, 10000, "mk");
    scene.step(30);
    mk.setActive(CharacterEntity.TARGETING_SLOT, false);

    assertThatThrownBy(
            () -> mk.actionHolder().start(scene.row("MegaKnight_EV1_uppercut", mk), null))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("without a current target");
  }

  @Test
  @DisplayName(
      "the targeting queue's flush takes nothing of a mark at priority 0 and takes one above as"
          + " the reference")
  void theQueueFlush() {
    Scene scene = new Scene();
    CharacterEntity mk = scene.unit(0, "MegaKnight_EV1", 3500, 10000, "mk");
    CharacterEntity knight = scene.unit(1, "Knight", 3500, 22000, "k");
    scene.step(30);

    mk.markTarget(knight, 0);
    scene.step(1);
    mk.markTarget(knight, 0);
    mk.markTarget(knight, 1);
    mk.tauntDrop(false);

    scene.step(1);
    assertThat(mk.getTargeting().getReference()).isSameAs(knight.getTargetView());
  }

  @Test
  @DisplayName(
      "the knock's arc: the rise to its height over half the duration and the fall below 0 on the"
          + " last step, in 32-bit steps")
  void theKnocksArc() {
    int[] heights = {
      1900, 3600, 5100, 6400, 7500, 8400, 9100, 9600, 9900, 10000, 9900, 9600, 9100, 8400, 7500,
      6400, 5100, 3600, 1900, 0, -2100
    };
    int height = 0;
    for (int i = 0; i < heights.length; i++) {
      height = KnockbackRun.arc(1000, 10000, 1000 - 50 * i, height);
      assertThat(height).as("step %d", i).isEqualTo(heights[i]);
    }
    // A half of 7 steps, as the record's translation answers it: the share rounded down, each
    // step's hundredths toward zero, and the fall's first step at the half.
    assertThat(KnockbackRun.arc(700, 1000, 700, 0)).isEqualTo(269);
    assertThat(KnockbackRun.arc(700, 1000, 350, 1000)).isEqualTo(986);
    assertThat(KnockbackRun.arc(700, 1000, 0, 100)).isEqualTo(-198);
  }

  @Test
  @DisplayName("a knock on a unit with an ability, which it would postpone, is refused")
  void aKnockOnAUnitWithAnAbilityIsRefused() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.unit(0, "GoldenKnight", 3500, 10000, "gk");
    scene.step(30);

    assertThatThrownBy(
            () ->
                knight
                    .actionHolder()
                    .start(scene.row("MegaKnight_EV1_uppercut_send_flying", knight), null))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("whose ability it postpones");
  }

  @Test
  @DisplayName(
      "the top side's wind is made and follows 1500 below its dragon, toward the enemy side")
  void theTopSidesWindFacesDown() {
    Scene scene = new Scene();
    CharacterEntity dragon = scene.unit(1, "BabyDragon_EV1", 3500, 20000, "bd");
    scene.step(30);
    dragon.setActive(CharacterEntity.MOVEMENT_SLOT, false);

    dragon.actionHolder().start(scene.row("baby_dragon_evo_wind_action", dragon), null);
    scene.step(2);

    int x = dragon.getView().getX();
    int y = dragon.getView().getY();
    assertThat(scene.log).contains("wind BabyDragon_EV1_wind_aeo_3000000 " + x + " " + (y - 1500));
    AreaEffectEntity wind = scene.areaEffects().get(0);
    assertThat(List.of(wind.getX(), wind.getY())).containsExactly(x, y - 1500);
  }

  @Test
  @DisplayName(
      "a second start gives a live wind its whole life back; once the wind has left, the run ends"
          + " at its next update and a start after that makes a new wind")
  void theWindsRetriggerAndANewWind() {
    Scene scene = new Scene();
    CharacterEntity dragon = scene.unit(0, "BabyDragon_EV1", 3500, 12000, "bd");
    scene.step(30);
    dragon.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    BattleAction row = scene.row("baby_dragon_evo_wind_action", dragon);
    String point = dragon.getView().getX() + " " + (dragon.getView().getY() + 1500);

    dragon.actionHolder().start(row, null);
    scene.step(3);
    AreaEffectEntity first = scene.areaEffects().get(0);
    assertThat(first.getCountdown()).isEqualTo(5850);
    dragon.actionHolder().start(row, null);
    assertThat(first.getCountdown()).isEqualTo(6000);

    first.cutLife(0);
    scene.step(2);
    dragon.actionHolder().start(row, null);

    String name = first.name();
    assertThat(
            scene.log.stream()
                .filter(line -> !line.startsWith("tags") && !line.startsWith("listed")))
        .containsExactly(
            "wind " + name + " " + point,
            "retrigger " + name + " 6000",
            "left " + name,
            "ended " + name,
            "wind BabyDragon_EV1_wind_aeo_3000001 " + point);
  }

  @Test
  @DisplayName("a choice by team with no cause is refused")
  void aChoiceByTeamWithoutACauseIsRefused() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.unit(0, "Knight", 3500, 10000, "k");
    scene.step(30);

    assertThatThrownBy(
            () ->
                knight
                    .actionHolder()
                    .start(scene.row("BabyDragon_EV1_AEO_select_buff", knight), null))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("starts with no cause");
  }

  @Test
  @DisplayName(
      "actions run at an area effect's ages: each once its age is reached, again after its life"
          + " is given back only with repeats; lists that do not match run nothing; a character"
          + " is refused")
  void actionsRunAtAges() {
    for (boolean repeat : List.of(true, false)) {
      Scene scene = new Scene();
      CharacterEntity dragon = scene.unit(0, "BabyDragon_EV1", 3500, 12000, "bd");
      scene.step(30);
      dragon.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      dragon.actionHolder().start(scene.row("baby_dragon_evo_wind_action", dragon), null);
      scene.step(1);
      AreaEffectEntity wind = scene.areaEffects().get(0);
      AliveTimer ages =
          new AliveTimer(
              ActionRow.named("ages"),
              List.of(100, 200),
              List.of(new InertAction(ActionRow.named("a")), new InertAction(ActionRow.named("b"))),
              repeat);
      AliveTimer unmatched =
          new AliveTimer(
              ActionRow.named("unmatched"),
              List.of(100),
              List.of(new InertAction(ActionRow.named("c")), new InertAction(ActionRow.named("d"))),
              true);
      wind.actionHolder().start(ages);
      wind.actionHolder().start(unmatched);
      scene.log.clear();

      scene.step(6);
      wind.restartLife();
      scene.step(4);

      List<String> fired = scene.log.stream().filter(line -> line.startsWith("age")).toList();
      if (repeat) {
        assertThat(fired).as("with repeats").containsExactly("age a", "age b", "age a");
      } else {
        assertThat(fired).as("without repeats").containsExactly("age a", "age b");
      }
      assertThatThrownBy(() -> dragon.actionHolder().start(ages))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("other than an area effect");
    }
  }

  @Test
  @DisplayName(
      "the push point of a target as near one princess tower of its side as the other is taken"
          + " from the first: it is pushed toward the left tower")
  void aTieKeepsTheFirstTower() {
    Scene scene = new Scene();
    CharacterEntity mk = scene.unit(0, "MegaKnight_EV1", 9000, 20800, "mk");
    CharacterEntity knight = scene.unit(1, "Knight", 9000, 22000, "k");
    scene.step(22);
    mk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    mk.getUnit().targeting().setReference(knight.getTargetView());
    List<int[]> points = new ArrayList<>();
    scene
        .world()
        .addObserver(
            new WorldObserver() {
              @Override
              public void uppercutStepped(
                  int tick,
                  CharacterEntity unit,
                  int delay,
                  boolean finished,
                  String outcome,
                  int[] pushPoint) {
                if (pushPoint != null) {
                  points.add(pushPoint);
                }
              }
            });
    int y = knight.getView().getY();

    mk.actionHolder().start(scene.row("MegaKnight_EV1_uppercut", mk), knight.actionHolder());
    scene.step(1);

    // PrincessTower_1_1 at (3500, 25500) and PrincessTower_1_2 at (14500, 25500) are equally far.
    assertThat(points).hasSize(1);
    assertThat(List.of(points.get(0)[2], points.get(0)[3])).containsExactly(5500, y - 25500);
  }

  @Test
  @DisplayName(
      "a knock lifts its unit by the arc's height, its floor letting the live height above its"
          + " base, and lands it with its route reset")
  void aKnockLiftsAndLands() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.unit(1, "Knight", 3500, 22000, "k");
    scene.step(30);
    knight.actionHolder().start(scene.row("MegaKnight_EV1_uppercut_send_flying", knight), null);

    scene.step(2);
    assertThat(knight.getView().getHeightOffset()).isEqualTo(1900);
    assertThat(knight.getView().isAir()).isTrue();
    scene.step(19);
    assertThat(knight.getUnit().movement().getRoute().isEmpty())
        .as("the route at landing")
        .isTrue();
  }

  @Test
  @DisplayName(
      "the wind's rectangle reaches 4500 along the length and 4000 across, a unit's circle meeting"
          + " it")
  void theWindsRectangle() {
    Scene scene = new Scene();
    CharacterEntity dragon = scene.unit(0, "BabyDragon_EV1", 9000, 8000, "bd");
    scene.step(30);
    dragon.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    int x = dragon.getView().getX();
    int y = dragon.getView().getY() + 1500;
    // Each Knight's circle of 500 reaches 4400 and 4100 from the wind's point.
    CharacterEntity ahead =
        scene.match.deploy(31, GameData.unit("Knight"), LEVEL, 0, x, y + 4900, "ahead");
    CharacterEntity aside =
        scene.match.deploy(31, GameData.unit("Knight"), LEVEL, 0, x + 4600, y, "aside");
    scene.step(1);
    ahead.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    aside.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    scene.log.clear();

    dragon.actionHolder().start(scene.row("baby_dragon_evo_wind_action", dragon), null);
    scene.step(2);

    assertThat(scene.log.stream().filter(line -> line.startsWith("listed")))
        .isNotEmpty()
        .containsOnly("listed [bd, ahead]");
  }
}
