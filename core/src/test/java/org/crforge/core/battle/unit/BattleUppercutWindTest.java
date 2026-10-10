/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.action.ActionRow;
import org.crforge.core.battle.action.AliveTimer;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.InertAction;
import org.crforge.core.battle.action.MegaKnightUppercut;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evolved Mega Knight's uppercut, the knock it gives, and the evolved Baby Dragon's wind where
 * the reference runs do not take them: the ends of the uppercut, its refusals, the knock's arc, its
 * postponing of an ability and a cast or follow-up it leaves going, the wind on the other side, its
 * re-trigger and a new wind after one has ended, a choice by team without a cause, and the actions
 * run at an area effect's ages.
 */
class BattleUppercutWindTest {

  /** Which bit of a tag word each flag is, as the configured tables number the game tags. */
  private static final EntityFlags BITS = EntityFlags.of(GameData.tables());

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The knock the uppercut gives. */
  private static final String KNOCK = "MegaKnight_EV1_uppercut_send_flying";

  /** The knock's duration, its row's Duration. */
  private static final int KNOCK_MS = Shipped.number(KNOCK, "Duration");

  /** The knock's updates: one a step until its duration is used up, and the landing one. */
  private static final int KNOCK_UPDATES = (KNOCK_MS + 49) / 50 + 1;

  /** The evolved Baby Dragon's wind action. */
  private static final String WIND = "baby_dragon_evo_wind_action";

  /** How far ahead of its dragon the wind stands, toward the enemy side: the action's OffsetY. */
  private static final int WIND_OFFSET = Shipped.number(WIND, "OffsetY");

  /** The wind's area effect row. */
  private static final GameRow WIND_AREA =
      Shipped.row("area_effect_objects", Shipped.text(WIND, "Aeo"));

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
    // The uppercut runs only while the attack counter its attacks keep is even; the Mega Knight
    // has attacked the tower by now, so it is set back to 0.
    mk.setVariable(scene.world().variableKey("MegaKnight_EV1_Do_Uppercut_Counter"), 0);
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

  /**
   * The uppercut's first update on a Knight standing in front of a held Mega Knight, the Knight's
   * tag word carrying NO_PUSHBACK or not, as a counter's parry of the hit before leaves it: the
   * scene after that update and one more step.
   *
   * @param noPushback true to give the Knight NO_PUSHBACK for the step of the push
   * @param uppercut a variant of the uppercut row built for the Mega Knight, or null for the row as
   *     the tables build it
   */
  private static Uppercut uppercutOnAKnight(
      boolean noPushback, Function<Uppercut, BattleAction> uppercut) {
    Scene scene = new Scene();
    CharacterEntity mk = scene.unit(0, "MegaKnight_EV1", 3500, 14000, "mk");
    CharacterEntity knight = scene.unit(1, "Knight", 3500, 15600, "k");
    // Just deployed, before its first hit; held where it stands.
    scene.step(22);
    mk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    mk.getUnit().targeting().setReference(knight.getTargetView());
    scene.log.clear();
    Uppercut run = new Uppercut(scene, mk, knight, actionsOn(knight));

    mk.actionHolder()
        .start(uppercut == null ? run.shippedRow() : uppercut.apply(run), knight.actionHolder());
    if (noPushback) {
      // Folded into the Knight's tag word by its pre-hook on the next step, before the push.
      knight
          .getView()
          .setPendingFlags(
              knight.getView().getPendingFlags() | knight.getView().getFlagBits().noPushback());
    }
    scene.step(2);
    return run;
  }

  /**
   * A scene of an uppercut by the Mega Knight on the Knight in front of it, and the actions the
   * Knight ran or had queued as the uppercut started.
   */
  private record Uppercut(
      Scene scene, CharacterEntity mk, CharacterEntity knight, List<String> knightBefore) {

    /** The uppercut row as the tables build it for the Mega Knight. */
    MegaKnightUppercut shippedRow() {
      return (MegaKnightUppercut) scene.row("MegaKnight_EV1_uppercut", mk);
    }

    /** The update lines of the uppercut's log. */
    List<String> updates() {
      return scene.log.stream().filter(line -> line.startsWith("update")).toList();
    }
  }

  /** The names of the actions running or queued on an entity. */
  private static List<String> actionsOn(WorldEntity entity) {
    List<String> names = new ArrayList<>();
    entity.actionHolder().running().forEach(run -> names.add(run.getAction().name()));
    entity.actionHolder().queued().forEach(queued -> names.add(queued.action().name()));
    return names;
  }

  @Test
  @DisplayName(
      "the uppercut's push on a target carrying NO_PUSHBACK is refused: the target is not pushed,"
          + " nothing is scheduled on it, and the run ends without holding the unit")
  void aPushOnANoPushbackTargetIsRefused() {
    Uppercut pushed = uppercutOnAKnight(false, null);
    String hold =
        "tags mk " + Long.toHexString(BITS.noAttack() | BITS.noMove() | BITS.lockTarget());
    // Without the tag, as the reference battles hold: pushed, the action on the Knight, the hold.
    assertThat(pushed.updates()).containsExactly("update mk pushed 1000", "update mk waiting 950");
    assertThat(pushed.knight().getUnit().movement().getAttackPushback()).isEqualTo(1);
    assertThat(actionsOn(pushed.knight()))
        .as("the knock the row's action on the target starts")
        .contains(KNOCK);
    assertThat(pushed.scene().log).contains(hold);

    Uppercut refused = uppercutOnAKnight(true, null);

    assertThat(refused.updates()).containsExactly("update mk push refused 0");
    MovementState movement = refused.knight().getUnit().movement();
    assertThat(movement.getAttackPushback()).as("an attack's push").isZero();
    assertThat(movement.getPushbackInFlight()).as("a pushback").isZero();
    assertThat(actionsOn(refused.knight()))
        .as("nothing scheduled on the target")
        .isEqualTo(refused.knightBefore());
    assertThat(refused.scene().log).doesNotContain(hold);
  }

  @Test
  @DisplayName(
      "with OnlyRunActionOnPushback off, a push refused on a NO_PUSHBACK target still schedules"
          + " the row's action on it; the run ends without the hold all the same")
  void aRefusedPushRunsTheActionWhenTheRowSaysSo() {
    Uppercut refused =
        uppercutOnAKnight(
            true,
            run -> {
              MegaKnightUppercut shipped = run.shippedRow();
              return new MegaKnightUppercut(
                  shipped.getRow(),
                  shipped.getPushBackStrength(),
                  shipped.getPushRadiusDirectionalOffset(),
                  shipped.isDistanceProportionalPush(),
                  shipped.isResetPushbackIfStronger(),
                  shipped.getDashFollowUpDelayMs(),
                  shipped.isResetAvoidanceAtPushback(),
                  false,
                  shipped.getActionOnTargets());
            });

    assertThat(refused.updates()).containsExactly("update mk push refused 0");
    assertThat(refused.knight().getUnit().movement().getAttackPushback()).isZero();
    assertThat(actionsOn(refused.knight()))
        .as("the knock the row's action on the target starts")
        .contains(KNOCK);
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
  @DisplayName(
      "a knock postpones the ability of the unit it throws: a request in the air is left pending"
          + " and cast on the visit after the knock's run has left")
  void aKnockPostponesTheAbility() {
    Scene scene = new Scene();
    // A Giant Buffer alone: its collector finds no friend, so only this request casts.
    CharacterEntity buffer = scene.unit(0, "GiantBuffer", 3500, 9500, "gb");
    scene.step(40);
    assertThat(buffer.getView().getState()).isNotEqualTo(GridEntityState.DEPLOYING);

    buffer.actionHolder().start(scene.row("MegaKnight_EV1_uppercut_send_flying", buffer), null);
    scene.step(3);
    assertThat(buffer.getView().getFlags() & BITS.abilityPostponed())
        .as("postponed in the air")
        .isNotZero();

    buffer.requestAbility();
    assertThat(buffer.abilityPending()).as("left pending").isTrue();
    // The knock's updates, three of them before the request, the last one landing the unit; the
    // finished run stays listed, its tag with it, one step more, and the next visit casts.
    int steps = 0;
    while (buffer.getView().getState() != GridEntityState.CASTING) {
      assertThat(buffer.getView().getFlags() & BITS.abilityPostponed())
          .as("still postponed after %d steps", steps)
          .isNotZero();
      scene.step(1);
      steps++;
      assertThat(steps).as("cast at last").isLessThan(40);
    }
    assertThat(steps).isEqualTo(KNOCK_UPDATES - 3 + 2);
    assertThat(buffer.getView().getFlags() & BITS.abilityPostponed()).isZero();
    assertThat(buffer.abilityPending()).isFalse();
  }

  @Test
  @DisplayName(
      "a knock on a unit casting its ability leaves the cast alone: the unit is lifted, and its"
          + " cast fires and ends on the same steps as without the knock")
  void aKnockLeavesACastGoing() {
    // A Giant Buffer alone: its collector finds no friend, so only this request casts.
    List<String> plain = castStates("GiantBuffer", false, 0);
    List<String> knocked = castStates("GiantBuffer", true, 0);

    assertThat(plain).as("the cast without a knock").contains("10 ground", "0 ground");
    assertThat(knocked).as("lifted while casting").contains("10 air");
    assertThat(states(knocked)).isEqualTo(states(plain));
  }

  @Test
  @DisplayName(
      "a knock on a unit holding its ability's follow-up state leaves that state alone: it ends on"
          + " the same step as without the knock")
  void aKnockLeavesAFollowUpGoing() {
    // The Monk's deflect: a cast, then the follow-up state for the ability's state duration.
    List<String> plain = castStates("Monk", false, -1);
    List<String> knocked = castStates("Monk", true, -1);

    assertThat(plain).as("the follow-up without a knock").contains("16 ground");
    assertThat(knocked).as("lifted in the follow-up").contains("16 air");
    assertThat(states(knocked)).isEqualTo(states(plain));
  }

  /**
   * The state and layer of a lone unit of the row, a step at a time, from its ability request until
   * it has left both the cast and the follow-up, with the knock started on it, when asked, the
   * given steps after the request, or on the first step of the follow-up for a negative delay.
   */
  private static List<String> castStates(String rowName, boolean knock, int knockAfter) {
    Scene scene = new Scene();
    CharacterEntity unit = scene.unit(0, rowName, 3500, 9500, "u");
    scene.step(40);
    unit.requestAbility();
    assertThat(unit.getView().getState()).isEqualTo(GridEntityState.CASTING);
    List<String> out = new ArrayList<>();
    boolean knocked = false;
    for (int steps = 0; steps < 400; steps++) {
      int state = unit.getView().getState();
      boolean due =
          knockAfter >= 0 ? steps == knockAfter : state == GridEntityState.ABILITY_FOLLOW_UP;
      if (knock && !knocked && due) {
        unit.actionHolder().start(scene.row(KNOCK, unit), null);
        knocked = true;
      }
      scene.step(1);
      state = unit.getView().getState();
      out.add(state + (unit.getView().isAir() ? " air" : " ground"));
      if (state != GridEntityState.CASTING && state != GridEntityState.ABILITY_FOLLOW_UP) {
        return out;
      }
    }
    throw new AssertionError(rowName + " never left its cast");
  }

  /** The states alone of a list of state and layer lines. */
  private static List<String> states(List<String> lines) {
    return lines.stream().map(line -> line.substring(0, line.indexOf(' '))).toList();
  }

  @Test
  @DisplayName(
      "a knock resets the charge of the unit it throws on each of its updates: the progress back"
          + " to 0 and the charged strike dropped, so the unit lands with no charge built")
  void aKnockResetsTheCharge() {
    Scene scene = new Scene();
    // A Prince walking up its lane, alone, until its charge is complete.
    CharacterEntity prince = scene.unit(0, "Prince", 3500, 6000, "p");
    int steps = 0;
    while (prince.getUnit().movement().getChargeProgress() < MovementState.CHARGE_COMPLETE) {
      scene.step(1);
      steps++;
      assertThat(steps).as("charged at last").isLessThan(200);
    }
    scene.step(2);
    assertThat(prince.getUnit().targeting().isChargeStrike()).as("strike armed").isTrue();
    List<Integer> progress = new ArrayList<>();
    scene
        .world()
        .addObserver(
            new WorldObserver() {
              @Override
              public void knockbackStepped(
                  int tick,
                  CharacterEntity unit,
                  int before,
                  int after,
                  int height,
                  long tags,
                  boolean finished) {
                progress.add(unit.getUnit().movement().getChargeProgress());
                assertThat(unit.getUnit().targeting().isChargeStrike()).isFalse();
              }
            });

    prince.actionHolder().start(scene.row(KNOCK, prince), null);
    scene.step(KNOCK_UPDATES);

    // Each update, the landing one too, leaves the charge reset; the unit walks on in the air, and
    // once it has landed its charge builds again from 0.
    assertThat(progress).hasSize(KNOCK_UPDATES).containsOnly(0);
    assertThat(prince.getUnit().movement().getChargeProgress()).isZero();
    scene.step(1);
    assertThat(prince.getUnit().movement().getChargeProgress())
        .isPositive()
        .isLessThan(MovementState.CHARGE_COMPLETE);
  }

  @Test
  @DisplayName(
      "the top side's wind is made and follows its dragon at the action's offset below it,"
          + " toward the enemy side")
  void theTopSidesWindFacesDown() {
    Scene scene = new Scene();
    CharacterEntity dragon = scene.unit(1, "BabyDragon_EV1", 3500, 20000, "bd");
    scene.step(30);
    dragon.setActive(CharacterEntity.MOVEMENT_SLOT, false);

    dragon.actionHolder().start(scene.row("baby_dragon_evo_wind_action", dragon), null);
    scene.step(2);

    int x = dragon.getView().getX();
    int y = dragon.getView().getY();
    assertThat(scene.log)
        .contains("wind BabyDragon_EV1_wind_aeo_3000000 " + x + " " + (y - WIND_OFFSET));
    AreaEffectEntity wind = scene.areaEffects().get(0);
    assertThat(List.of(wind.getX(), wind.getY())).containsExactly(x, y - WIND_OFFSET);
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
    String point = dragon.getView().getX() + " " + (dragon.getView().getY() + WIND_OFFSET);
    // The wind's life, its area effect row's LifeDuration.
    int life = Shipped.number(WIND_AREA, "LifeDuration");

    dragon.actionHolder().start(row, null);
    scene.step(3);
    AreaEffectEntity first = scene.areaEffects().get(0);
    assertThat(first.getCountdown()).isEqualTo(life - 3 * 50);
    dragon.actionHolder().start(row, null);
    assertThat(first.getCountdown()).isEqualTo(life);

    first.cutLife(0);
    scene.step(2);
    dragon.actionHolder().start(row, null);

    String name = first.name();
    assertThat(
            scene.log.stream()
                .filter(line -> !line.startsWith("tags") && !line.startsWith("listed")))
        .containsExactly(
            "wind " + name + " " + point,
            "retrigger " + name + " " + life,
            // Cut to 0, the wind has one more update, its countdown going below 0, whose alive
            // time, its whole life, passes the end blow's age in the start group.
            "age baby_dragon_evo_wind_end_blow",
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
    // Halfway across between the top side's two princess towers.
    List<TowerEntity> towers = scene.world().princessTowers(1);
    assertThat(towers).hasSize(2);
    TowerEntity left = towers.get(0);
    assertThat(left.getView().getX()).isLessThan(towers.get(1).getView().getX());
    int x = (left.getView().getX() + towers.get(1).getView().getX()) / 2;
    CharacterEntity mk = scene.unit(0, "MegaKnight_EV1", x, 20800, "mk");
    CharacterEntity knight = scene.unit(1, "Knight", x, 22000, "k");
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

    // PrincessTower_1_1 and PrincessTower_1_2 are equally far: the push point is taken from the
    // first.
    assertThat(points).hasSize(1);
    assertThat(List.of(points.get(0)[2], points.get(0)[3]))
        .containsExactly(x - left.getView().getX(), y - left.getView().getY());
  }

  @Test
  @DisplayName(
      "a knock lifts its unit by the arc's height, its floor letting the live height above its"
          + " base, and lands it with its route reset")
  void aKnockLiftsAndLands() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.unit(1, "Knight", 3500, 22000, "k");
    scene.step(30);
    knight.actionHolder().start(scene.row(KNOCK, knight), null);

    scene.step(2);
    // The first update's rise: 190 hundredths of a share, the share the knock's height over the
    // whole steps of the half of its duration.
    int share = Shipped.number(KNOCK, "Height") / (KNOCK_MS / 2 / 50);
    assertThat(knight.getView().getHeightOffset()).isEqualTo(share * 190 / 100);
    assertThat(knight.getView().isAir()).isTrue();
    scene.step(KNOCK_UPDATES - 2);
    assertThat(knight.getUnit().movement().getRoute().isEmpty())
        .as("the route at landing")
        .isTrue();
  }

  @Test
  @DisplayName(
      "the wind's rectangle reaches half its shape's height along the length and half its width"
          + " across, a unit's circle meeting it")
  void theWindsRectangle() {
    Scene scene = new Scene();
    CharacterEntity dragon = scene.unit(0, "BabyDragon_EV1", 9000, 8000, "bd");
    scene.step(30);
    dragon.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    int x = dragon.getView().getX();
    int y = dragon.getView().getY() + WIND_OFFSET;
    // Half the shape's height along the length and half its width across; each Knight's circle
    // reaches 100 inside the first and stops 100 short of the second.
    GameRow shape = Shipped.row("shapes", Shipped.text(WIND_AREA, "Shape"));
    int knightRadius = Shipped.number(Shipped.unitRow("Knight"), "CollisionRadius");
    int alongEdge = Shipped.number(shape, "Height") / 2;
    int acrossEdge = Shipped.number(shape, "Width") / 2;
    CharacterEntity ahead =
        scene.match.deploy(
            31, GameData.unit("Knight"), LEVEL, 0, x, y + alongEdge + knightRadius - 100, "ahead");
    CharacterEntity aside =
        scene.match.deploy(
            31, GameData.unit("Knight"), LEVEL, 0, x + acrossEdge + knightRadius + 100, y, "aside");
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
