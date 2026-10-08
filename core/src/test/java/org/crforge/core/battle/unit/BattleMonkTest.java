package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Monk's ability where its runs do not take it: the tags it carries in its follow-up state, its
 * buff against damage over time, a push and the pending damage's lethal test, what a deflection
 * leaves on the shooter and the shot, and a projectile whose deflection is not modelled.
 */
class BattleMonkTest {

  /** Which bit of a tag word each flag is, as the configured tables number the game tags. */
  private static final EntityFlags BITS = EntityFlags.of(GameData.tables());

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** Long enough for a placed Monk to deploy, cast and see its follow-up state out. */
  private static final int TICKS = 300;

  /** The two tags the Monk carries in its follow-up state. */
  private static final long TAGS = BITS.avoidanceAsObstacle() | BITS.noMoveAllowAttract();

  /** The Monk's ability row. */
  private static final GameRow ABILITY =
      Shipped.row("character_abilities", Shipped.text(Shipped.unitRow("Monk"), "Ability"));

  /** The percent of damage the Monk's ability buff lets through: what its reduction leaves. */
  private static final int LETS_THROUGH =
      100
          - Shipped.number(
              Shipped.row(
                  "character_buffs",
                  Shipped.text(
                      Shipped.actionNames(Shipped.text(ABILITY, "OnActivationAction"), "SubActions")
                          .get(1),
                      "SpawnData")),
              "DamageReduction");

  /** A Monk placed for the bottom side, standing where it deploys, with what its ability did. */
  private static final class Scene {
    final Standard1v1Battle match;
    final CharacterEntity monk;
    final List<ProjectileEntity> deflected = new ArrayList<>();
    final List<int[]> deflection = new ArrayList<>();
    int tick;

    Scene() {
      match = new Standard1v1Battle(GameData.tables(), LEVEL, false);
      monk = match.deploy(0, GameData.unit("Monk"), LEVEL, 0, 3500, 10000, "Monk");
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void projectileDeflected(
                    int t,
                    AreaEffectEntity deflector,
                    ProjectileEntity projectile,
                    WorldEntity parent,
                    WorldEntity source) {
                  deflected.add(projectile);
                  deflection.add(
                      new int[] {
                        projectile.side(),
                        parent.getView().getPendingDamageAmount(),
                        source.getView().getPendingDamageAmount(),
                        projectile.damage()
                      });
                }
              });
    }

    void step() {
      match.getBattle().step();
      tick++;
    }

    /** Steps until the Monk has deployed, holds it where it stands and requests its ability. */
    void request() {
      while (monk.getView().getState() == GridEntityState.DEPLOYING
          || monk.getView().getState() == GridEntityState.WAITING_TO_DEPLOY
          || monk.getId() == 0) {
        step();
        assertThat(tick).as("the Monk deploys").isLessThan(TICKS);
      }
      monk.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      monk.requestAbility();
    }

    /** Steps until the Monk is in its ability's follow-up state. */
    void followUp() {
      while (monk.getView().getState() != GridEntityState.ABILITY_FOLLOW_UP) {
        step();
        assertThat(tick).as("the Monk's ability fires").isLessThan(TICKS);
      }
    }
  }

  @Test
  @DisplayName(
      "the Monk carries its two tags from the tick after it enters its follow-up state to the tick"
          + " it leaves it, and stands")
  void theTagsLastTheFollowUp() {
    Scene scene = new Scene();
    scene.request();
    scene.followUp();
    int entered = scene.tick;
    int x = scene.monk.getView().getX();
    int y = scene.monk.getView().getY();
    assertThat(scene.monk.getView().getFlags() & TAGS).isZero();

    List<Integer> tagged = new ArrayList<>();
    while (scene.monk.getView().getState() == GridEntityState.ABILITY_FOLLOW_UP) {
      scene.step();
      if ((scene.monk.getView().getFlags() & TAGS) == TAGS) {
        tagged.add(scene.tick);
      }
      assertThat(scene.monk.getView().getX()).isEqualTo(x);
      assertThat(scene.monk.getView().getY()).isEqualTo(y);
    }
    int left = scene.tick;
    // The state lasts its ability's state duration, less the step that enters it.
    int ticks = Shipped.ticks(Shipped.number(ABILITY, "AbilityStateDuration")) - 1;
    assertThat(left - entered).as("ticks in the state").isEqualTo(ticks);
    assertThat(tagged).hasSize(ticks);
    assertThat(tagged.get(0)).isEqualTo(entered + 1);
    assertThat(tagged.get(tagged.size() - 1)).isEqualTo(left);
    scene.step();
    assertThat(scene.monk.getView().getFlags() & TAGS).isZero();
  }

  @Test
  @DisplayName(
      "a Poison on the Monk under its ability deals what its buff's reduction leaves of what it"
          + " deals beside it")
  void damageOverTimeIsReduced() {
    Scene scene = new Scene();
    CharacterEntity plain = scene.match.deploy(0, GameData.unit("Monk"), LEVEL, 0, 4500, 10000);
    List<int[]> hits = new ArrayList<>();
    scene
        .match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void buffDamaged(
                  int t,
                  WorldEntity target,
                  BuffInstance buff,
                  int damage,
                  int hitPointsBefore,
                  DamageResult result) {
                if (target.getBuffs().carries("ShieldBoostMonk")) {
                  hits.add(new int[] {t, 0, damage});
                } else if (target == plain) {
                  hits.add(new int[] {t, 1, damage});
                }
              }
            });
    scene.request();
    plain.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    scene.followUp();
    scene.match.play(scene.tick, GameData.card("Poison"), LEVEL, 1, 4000, 10000, "P");
    for (int i = 0; i < 60; i++) {
      scene.step();
    }

    List<int[]> shielded = hits.stream().filter(h -> h[1] == 0).toList();
    List<int[]> beside = hits.stream().filter(h -> h[1] == 1).toList();
    assertThat(shielded).isNotEmpty().hasSameSizeAs(beside);
    for (int i = 0; i < shielded.size(); i++) {
      assertThat(shielded.get(i)[0]).isEqualTo(beside.get(i)[0]);
      assertThat(shielded.get(i)[2]).isEqualTo(beside.get(i)[2] * LETS_THROUGH / 100);
    }
  }

  @Test
  @DisplayName("an area's push leaves the Monk under its ability where it stands")
  void aPushIsRefused() {
    Scene scene = new Scene();
    CharacterEntity plain = scene.match.deploy(0, GameData.unit("Monk"), LEVEL, 0, 5000, 10000);
    List<WorldEntity> pushed = new ArrayList<>();
    scene
        .match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void pushbackRequested(
                  int t,
                  WorldEntity unit,
                  boolean started,
                  int fromX,
                  int fromY,
                  MovementState pushback) {
                if (started) {
                  pushed.add(unit);
                }
              }
            });
    scene.request();
    scene.monk.setActive(CharacterEntity.MOVEMENT_SLOT, true);
    scene.followUp();
    int x = scene.monk.getView().getX();
    int y = scene.monk.getView().getY();
    int hitPoints = scene.monk.getHitPoints().getHitPoints();
    // An area that hits and pushes 1000: the thrown bomb's explosion.
    scene.match.placeAreaEffect(scene.tick, "ThrownBombExplosion", LEVEL, 1, 4250, 11000, "Bomb");
    for (int i = 0; i < 20; i++) {
      scene.step();
    }

    assertThat(pushed).as("the Monk beside it, pushed").containsExactly(plain);
    assertThat(scene.monk.getView().getX()).isEqualTo(x);
    assertThat(scene.monk.getView().getY()).isEqualTo(y);
    assertThat(scene.monk.getHitPoints().getHitPoints()).as("hit").isLessThan(hitPoints);
  }

  @Test
  @DisplayName(
      "damage on its way is lethal to the Monk under its ability only at what its buff's reduction"
          + " leaves")
  void theLethalTestIsReduced() {
    Scene scene = new Scene();
    scene.request();
    int hitPoints = scene.monk.getHitPoints().getHitPoints();
    BattleValidatorQueries queries = new BattleValidatorQueries(scene.match.getWorld());
    assertThat(queries.pendingDamageAccepted(scene.monk.getTargetView(), hitPoints)).isTrue();

    scene.followUp();
    assertThat(queries.pendingDamageAccepted(scene.monk.getTargetView(), hitPoints)).isFalse();
    int lethal = (hitPoints * 100 + LETS_THROUGH - 1) / LETS_THROUGH;
    assertThat(queries.pendingDamageAccepted(scene.monk.getTargetView(), lethal)).isTrue();
    assertThat(queries.pendingDamageAccepted(scene.monk.getTargetView(), lethal - 1)).isFalse();
  }

  @Test
  @DisplayName(
      "a deflected shot hands its damage on the Monk back, flies for the Monk's side and puts its"
          + " damage on the shooter, which it hits")
  void aDeflectedShotTurnsOnItsShooter() {
    Scene scene = new Scene();
    CharacterEntity musketeer =
        scene.match.deploy(0, GameData.unit("Musketeer"), LEVEL, 1, 3500, 15500);
    scene.request();
    musketeer.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    scene.followUp();
    int full = musketeer.getHitPoints().getHitPoints();
    while (scene.deflected.isEmpty()) {
      scene.step();
      assertThat(scene.tick).as("a shot is deflected").isLessThan(TICKS);
    }

    ProjectileEntity shot = scene.deflected.get(0);
    int[] at = scene.deflection.get(0);
    assertThat(at[0]).as("its side").isEqualTo(scene.monk.side());
    assertThat(at[1]).as("pending on the Monk").isZero();
    assertThat(at[2]).as("pending on the shooter").isEqualTo(at[3]);
    assertThat(shot.getTarget()).isSameAs(musketeer);
    assertThat(shot.getOwner()).isSameAs(scene.monk);
    assertThat(shot.getDeflections()).isEqualTo(1);
    while (!shot.isReleased()) {
      scene.step();
    }
    assertThat(musketeer.getHitPoints().getHitPoints()).isEqualTo(full - at[3]);
  }

  @Test
  @DisplayName(
      "a shot with a deflection radius of its own, the Princess's, is refused when the Deflect"
          + " touches it")
  void aDeflectionRadiusIsRefused() {
    Scene scene = new Scene();
    CharacterEntity princess =
        scene.match.deploy(0, GameData.unit("Princess"), LEVEL, 1, 3500, 18000);
    scene.request();
    princess.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    scene.followUp();

    assertThatThrownBy(
            () -> {
              for (int i = 0; i < 80; i++) {
                scene.step();
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("flies within")
        .hasMessageContaining("whose deflection of it is not modelled");
  }
}
