/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.BattleAction;
import org.crforge.core.battle.action.Clone;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.combat.HitPoints;
import org.crforge.core.pathfinding.combat.PackedLevel;
import org.crforge.core.pathfinding.move.ContactRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Clone where the reference runs leave it: whom its hit reaches, the clone each unit gets - its
 * level, its 1 hit point and its shield, its buffs - and the move that sets the two apart, a clone
 * never cloned again and its death spawns clones too; and the clones the battle refuses. Then the
 * area effects with a buff over clones where the runs do not take them, written in the hit-switch
 * form: a Rage and a Heal Spirit's area beside a Minion's clone, a Zap, a Tornado's pull, and an
 * Earthquake's air test after the buff test.
 *
 * <p>Each scene writes the circles, timings and buff columns it counts on into the rows: the
 * Clone's circle of 3000 for 1000 ms and its clone buff of 500 ms, and those of the area effects
 * below.
 */
class BattleCloneTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** A tick after every unit placed on the first one has deployed. */
  private static final int CAST_TICK = 25;

  /** The Clone's point, on the bottom side's left, at a cell's centre and away from every tower. */
  private static final int X = 3250;

  private static final int Y = 11250;

  @TempDir static Path tablesFolder;

  /** The configured tables with the Clone's columns written. */
  private static GameTables cloneTables;

  @BeforeAll
  static void writeTheClone() throws IOException {
    cloneTables = withClone(tablesFolder.resolve("clone"), rows -> {});
  }

  /**
   * The configured tables with the Clone written as the scenes count on it - a circle of 3000 for
   * 1000 ms, and the clone buff its hit spawns for 500 ms - and the area effects' rows then edited.
   *
   * @param folder the folder the tables are copied into
   * @param areas what is done to the area effects' rows once the Clone's are written
   */
  private static GameTables withClone(Path folder, Consumer<ObjectNode> areas) throws IOException {
    Files.createDirectories(folder);
    GameData.altered(
        folder,
        "area_effect_objects",
        rows -> {
          GameData.columns(rows, "Clone").put("Radius", 3000).put("LifeDuration", 1000);
          areas.accept(rows);
        });
    GameData.alterLoaded(
        folder,
        "actions",
        rows -> ((ObjectNode) rows.get("SpawnCloneBufAction").get("fields")).put("SpawnTime", 500));
    return GameTables.load(folder);
  }

  /** A battle with the towers passive that logs what every Clone does. */
  private static final class Scene {
    final Standard1v1Battle match;
    final List<String> scheduled = new ArrayList<>();
    final List<CharacterEntity> clones = new ArrayList<>();
    final List<String> refused = new ArrayList<>();
    final List<String> moves = new ArrayList<>();
    final List<String> copied = new ArrayList<>();
    final List<String> gate = new ArrayList<>();
    final List<String> hooks = new ArrayList<>();
    final List<String> chargeResets = new ArrayList<>();
    final List<String> deathProjectiles = new ArrayList<>();
    final List<CharacterEntity> impactSpawns = new ArrayList<>();
    final List<CharacterEntity> areaSpawns = new ArrayList<>();
    final GameTables tables;
    AreaEffectEntity area;
    final List<AreaEffectEntity> areas = new ArrayList<>();
    int tick;

    Scene() {
      this(cloneTables);
    }

    Scene(GameTables tables) {
      this.tables = tables;
      match = new Standard1v1Battle(tables, LEVEL, false);
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void areaEffectCreated(
                    int t, AreaEffectEntity a, String how, String source) {
                  area = a;
                  areas.add(a);
                }

                @Override
                public void onHitActionScheduled(
                    int t, AreaEffectEntity a, WorldEntity target, BattleAction action) {
                  scheduled.add(target.name());
                }

                @Override
                public void cloned(
                    int t,
                    CharacterEntity original,
                    CharacterEntity clone,
                    SpawnHost instigator,
                    List<Integer> registrationVisits) {
                  clones.add(clone);
                }

                @Override
                public void cloneRefused(
                    int t, WorldEntity original, String reason, SpawnHost instigator) {
                  refused.add(original.name() + " " + reason);
                }

                @Override
                public void cloneMoveStarted(int t, CharacterEntity unit, int x, int y) {
                  moves.add(unit.name() + " to " + x + " " + y);
                }

                @Override
                public void buffCopied(
                    int t, WorldEntity original, WorldEntity clone, BuffInstance copy) {
                  copied.add(
                      clone.name() + " " + copy.getBuff().name() + " " + copy.getRemaining());
                }

                @Override
                public void buffHookScheduled(
                    int t, WorldEntity carrier, BuffInstance buff, String action, boolean start) {
                  hooks.add(
                      "%s %s %s %s"
                          .formatted(
                              carrier.name(),
                              buff.getBuff().name(),
                              action,
                              start ? "start" : "other"));
                }

                @Override
                public void deathProjectileLaunched(
                    int t, WorldEntity dying, ProjectileEntity projectile) {
                  deathProjectiles.add(dying.name() + " " + projectile.getData().name());
                }

                @Override
                public void characterSpawned(
                    int t, SpawnHost source, CharacterEntity child, int createdX, int createdY) {
                  if (source instanceof ProjectileEntity) {
                    impactSpawns.add(child);
                  }
                  if (source instanceof AreaEffectEntity) {
                    areaSpawns.add(child);
                  }
                }

                @Override
                public void buffChargeReset(
                    int t, CharacterEntity unit, BuffInstance instance, int before, int after) {
                  chargeResets.add(unit.name() + " " + instance.getBuff().name());
                }

                @Override
                public void cloneBuffGateAsked(
                    int t,
                    AreaEffectEntity a,
                    String buff,
                    CharacterEntity clone,
                    String path,
                    int query,
                    boolean refused) {
                  gate.add(
                      "%d %s %s %s %d %b".formatted(t, clone.name(), buff, path, query, refused));
                }
              });
    }

    /** A unit placed at a tick that never moves on its own, under a name of its own. */
    CharacterEntity still(int at, int side, String row, int x, int y, String name) {
      return still(at, side, row, LEVEL, x, y, name);
    }

    CharacterEntity still(int at, int side, String row, int level, int x, int y, String name) {
      CharacterEntity unit = match.deploy(at, GameData.unit(row), level, side, x, y, name);
      unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      return unit;
    }

    /** As {@link #still(int, int, String, int, int, String)}, of the scene's own tables' row. */
    CharacterEntity stillOfTables(int at, int side, String row, int x, int y, String name) {
      CharacterEntity unit =
          match.deploy(at, new BattleRecords(tables).unit(row), LEVEL, side, x, y, name);
      unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      return unit;
    }

    /** Casts a Clone for side 0 at the point on a tick and steps through that tick. */
    void clone(int at) {
      match.placeAreaEffect(at, "Clone", LEVEL, 0, X, Y, "C");
      step(at + 1 - tick);
    }

    void step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        match.getBattle().step();
        tick++;
      }
    }

    CharacterEntity named(String name) {
      for (BattleEntity entity : match.getBattle().getHolder().entities()) {
        if (entity instanceof CharacterEntity c && c.name().equals(name)) {
          return c;
        }
      }
      throw new IllegalArgumentException(name);
    }

    List<String> cloneNames() {
      return clones.stream().map(CharacterEntity::name).toList();
    }
  }

  @Test
  @DisplayName(
      "its hit clones its own troops in its circle, air ones too, but no building, no enemy and no"
          + " unit that a Clone passes by")
  void whoIsCloned() {
    Scene scene = new Scene();
    scene.still(0, 0, "Knight", X + 1000, Y, "knight");
    scene.still(0, 0, "Minion", X, Y + 1000, "minion");
    scene.still(0, 0, "Cannon", X - 1500, Y - 1000, "cannon");
    scene.still(0, 1, "Knight", X - 1000, Y, "enemy");
    scene.still(0, 0, "Recruit_Chess", X, Y - 1000, "chess");
    scene.still(0, 0, "Knight", X + 4000, Y, "far");
    scene.clone(CAST_TICK);

    // The Clone's filter lists its own side's characters but buildings and clones; its hit action
    // is scheduled on each, and its perform passes by the unit a Clone ignores.
    assertThat(scene.scheduled).containsExactlyInAnyOrder("knight", "minion", "chess");
    assertThat(scene.cloneNames()).containsExactlyInAnyOrder("knight_clone0", "minion_clone0");
    assertThat(scene.refused).containsExactly("chess ignore clone");
  }

  @Test
  @DisplayName(
      "a clone is made on its unit at the Clone's level with 1 hit point of 1, set up as a clone"
          + " with its targeting off, and carries a copy of the Clone's buff its unit took")
  void theClone() {
    Scene scene = new Scene();
    // A lower level than the Clone's, which the clone does not keep.
    CharacterEntity knight = scene.still(0, 0, "Knight", 8, X, Y, "knight");
    scene.clone(CAST_TICK);

    CharacterEntity clone = scene.named("knight_clone0");
    assertThat(clone.isClone()).isTrue();
    assertThat(knight.isClone()).isFalse();
    assertThat(clone.getData().name()).isEqualTo("Knight");
    assertThat(clone.side()).isZero();
    assertThat(PackedLevel.level(clone.getPackedLevel())).isEqualTo(LEVEL);
    assertThat(clone.getHitPoints().getHitPoints()).isEqualTo(1);
    assertThat(clone.getHitPoints().getMaximum()).isEqualTo(1);
    assertThat(clone.getView().getState()).isEqualTo(GridEntityState.CLONE_SETUP);
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.CLONE_SETUP);
    assertThat(clone.isActive(CharacterEntity.TARGETING_SLOT)).isFalse();
    assertThat(knight.getBuffs().carries("Clone")).isTrue();
    assertThat(scene.copied).containsExactly("knight_clone0 Clone 500");
  }

  @Test
  @DisplayName(
      "a clone's shield is 1 of 1 while its unit's is up, and none of 1 once its unit's is broken")
  void theShield() {
    Scene scene = new Scene();
    scene.still(0, 0, "Recruit", X + 1000, Y, "up");
    CharacterEntity broken = scene.still(0, 0, "Recruit", X - 1000, Y, "broken");
    scene.step(CAST_TICK);
    broken.getHitPoints().setShield(0);
    scene.clone(CAST_TICK);

    CharacterEntity up = scene.named("up_clone0");
    assertThat(up.getHitPoints().getShield()).isEqualTo(1);
    assertThat(up.getHitPoints().getShieldMaximum()).isEqualTo(1);
    CharacterEntity none = scene.named("broken_clone0");
    assertThat(none.getHitPoints().getShield()).isZero();
    assertThat(none.getHitPoints().getShieldMaximum()).isEqualTo(1);
    assertThat(none.getHitPoints().getHitPoints()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "the clone moves back toward its own side and its unit forward, 125 a visit for ten visits,"
          + " and both are resumed as the move ends")
  void theMoveApart() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.still(0, 0, "Knight", X, Y, "knight");
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, true);
    scene.clone(CAST_TICK);
    CharacterEntity clone = scene.named("knight_clone0");
    assertThat(scene.moves).containsExactly("knight_clone0 to 3250 0", "knight to 3250 31999");

    // Both start where the Knight stood as the Clone reached it.
    int fromY = clone.getView().getY();
    assertThat(knight.getView().getY()).isEqualTo(fromY);
    scene.step(9);
    assertThat(clone.getView().getY()).isEqualTo(fromY - 9 * 125);
    assertThat(knight.getView().getY()).isEqualTo(fromY + 9 * 125);
    assertThat(clone.getView().getState()).isEqualTo(GridEntityState.CLONE_SETUP);
    scene.step(1);
    assertThat(clone.getView().getY()).isEqualTo(fromY - 10 * 125);
    assertThat(knight.getView().getY()).isEqualTo(fromY + 10 * 125);
    assertThat(clone.getView().getX()).isEqualTo(X);
    assertThat(clone.getView().getState()).isNotEqualTo(GridEntityState.CLONE_SETUP);
    assertThat(knight.getView().getState()).isNotEqualTo(GridEntityState.CLONE_SETUP);
    assertThat(clone.getUnit().movement().getExplicitX()).isEqualTo(-1);
  }

  /**
   * A still Knight whose enemy dies while its hit is under way, so that its target-lost timer runs,
   * stepped one visit further: the scene the two tests below clone it in.
   */
  private static CharacterEntity knightThatLostItsTarget(Scene scene) {
    CharacterEntity knight = scene.still(0, 0, "Knight", X, Y, "knight");
    CharacterEntity enemy = scene.still(0, 1, "Knight", X, Y + 1000, "enemy");
    while (knight.getView().getState() != GridEntityState.ATTACKING
        || knight.getTargeting().getAttackTimerMs() < 1) {
      scene.step(1);
    }
    scene.match.getWorld().kill(enemy, null);
    scene.step(2);
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.ATTACKING);
    assertThat(knight.getTargeting().getTargetLostTimerMs()).isGreaterThanOrEqualTo(1);
    return knight;
  }

  @Test
  @DisplayName(
      "a unit cloned in the attacking state with its targeting component on has its target-lost"
          + " timer cleared as it leaves that state for the clone's setup")
  void theSetupClearsTheLostTimerOfATargetingUnit() {
    Scene scene = new Scene();
    CharacterEntity knight = knightThatLostItsTarget(scene);
    scene.clone(scene.tick);

    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.CLONE_SETUP);
    assertThat(knight.getTargeting().getTargetLostTimerMs()).isZero();
  }

  @Test
  @DisplayName(
      "a unit cloned in the attacking state with its targeting component switched off, as a stun"
          + " or a hold leaves it, keeps its target-lost timer through the clone's setup: the"
          + " setter reaches the component through its switch, and one switched off is not there")
  void theSetupKeepsTheLostTimerOfAUnitSwitchedOff() {
    Scene scene = new Scene();
    CharacterEntity knight = knightThatLostItsTarget(scene);
    // Stunned by the enemy: the combat gate switches the targeting component off, and the
    // targeting visit, which runs the timer on, runs no more.
    knight.getBuffs().apply(scene.match.getWorld().getRecords().buff("Stun"), 1000, LEVEL, null, 1);
    scene.step(1);
    assertThat(knight.isActive(CharacterEntity.TARGETING_SLOT)).isFalse();
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.ATTACKING);
    int lost = knight.getTargeting().getTargetLostTimerMs();
    assertThat(lost).isGreaterThanOrEqualTo(1);
    scene.clone(scene.tick);

    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.CLONE_SETUP);
    assertThat(knight.getTargeting().getTargetLostTimerMs()).isEqualTo(lost);
  }

  @Test
  @DisplayName(
      "a second Clone clones the units again and passes their clones by, its area's filter dropping"
          + " them; a clone the action reaches directly, which the game's perform would clone again,"
          + " is refused as not modelled")
  void noCloneOfAClone() {
    Scene scene = new Scene();
    scene.still(0, 0, "Knight", X, Y, "knight");
    scene.clone(CAST_TICK);
    CharacterEntity clone = scene.named("knight_clone0");
    scene.step(15);
    scene.scheduled.clear();
    scene.clone(scene.tick);

    assertThat(scene.scheduled).containsExactly("knight");
    assertThat(scene.cloneNames()).containsExactly("knight_clone0", "knight_clone1");

    BattleAction row =
        GameData.actions().build("CloneAction", scene.match.getWorld().binding(clone));
    assertThatThrownBy(
            () ->
                clone
                    .actionHolder()
                    .schedule(row, ActionHolder.OWN_DELAY, true, scene.area.actionHolder()))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("knight_clone0 is a clone a Clone's action reaches");
    assertThat(scene.refused).isEmpty();
  }

  @Test
  @DisplayName("a clone's death spawns are clones of 1 hit point")
  void theDeathSpawnsOfAClone() {
    Scene scene = new Scene();
    scene.still(0, 0, "Golem", X, Y, "golem");
    scene.step(CAST_TICK + 60);
    scene.clone(scene.tick);
    CharacterEntity clone = scene.named("golem_clone0");
    scene.match.getWorld().kill(clone, null);
    scene.step(1);

    for (String child : List.of("golem_clone0_0", "golem_clone0_1")) {
      CharacterEntity golemite = scene.named(child);
      assertThat(golemite.isClone()).as(child).isTrue();
      assertThat(golemite.getHitPoints().getMaximum()).as(child).isEqualTo(1);
    }
  }

  @Test
  @DisplayName(
      "a clone's death spawn with a deploy time is a clone made deploying: 1 hit point of 1, its"
          + " countdown run as any unit's, out of collision until it ends, then in it again")
  void aCloneDeathSpawnThatDeploys(@TempDir Path folder) throws IOException {
    withClone(folder, rows -> {});
    // Two children on the one in-front point, so that they overlap, each deploying for 700 ms.
    GameData.alterLoaded(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "Knight")
                .put("DeathSpawnCharacter", "Skeleton")
                .put("DeathSpawnCount", 2)
                .put("DeathSpawnDeployTime", 700));
    GameTables tables = GameTables.load(folder);
    Scene scene = new Scene(tables);
    CharacterEntity knight =
        scene.match.deploy(0, new BattleRecords(tables).unit("Knight"), LEVEL, 0, X, Y, "knight");
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    scene.clone(CAST_TICK);
    CharacterEntity clone = scene.named("knight_clone0");
    // Past the move apart, the clone resumed.
    scene.step(15);
    scene.match.getWorld().kill(clone, null);
    // Taken into the holder at the step's end, before any visit of its own.
    scene.step(1);

    List<CharacterEntity> children =
        List.of(scene.named("knight_clone0_0"), scene.named("knight_clone0_1"));
    List<int[]> made = new ArrayList<>();
    for (CharacterEntity child : children) {
      assertThat(child.isClone()).as(child.name()).isTrue();
      assertThat(child.getHitPoints().getHitPoints()).as(child.name()).isEqualTo(1);
      assertThat(child.getHitPoints().getMaximum()).as(child.name()).isEqualTo(1);
      assertThat(child.getView().getState()).isEqualTo(GridEntityState.DEPLOYING);
      assertThat(child.getView().getDeployCountdown()).isEqualTo(700);
      made.add(new int[] {child.getView().getX(), child.getView().getY()});
    }
    int apart =
        Math.abs(made.get(0)[0] - made.get(1)[0]) + Math.abs(made.get(0)[1] - made.get(1)[1]);
    assertThat(apart).isLessThan(2 * children.get(0).getData().collisionRadius());

    // 50 ms a visit: deploying for 14 visits, neither pushed nor pushing while it does.
    for (int visit = 1; visit <= 13; visit++) {
      scene.step(1);
      for (int i = 0; i < 2; i++) {
        CharacterEntity child = children.get(i);
        assertThat(child.getView().getState())
            .as("visit %d child %d", visit, i)
            .isEqualTo(GridEntityState.DEPLOYING);
        assertThat(child.getView().getDeployCountdown()).isEqualTo(700 - 50 * visit);
        assertThat(ContactRule.collides(child.getView())).isZero();
        assertThat(child.getView().getX()).isEqualTo(made.get(i)[0]);
        assertThat(child.getView().getY()).isEqualTo(made.get(i)[1]);
      }
    }
    scene.step(1);
    for (CharacterEntity child : children) {
      assertThat(child.getView().getState()).isNotEqualTo(GridEntityState.DEPLOYING);
      assertThat(ContactRule.collides(child.getView())).isEqualTo(1);
    }
  }

  @Test
  @DisplayName(
      "a unit that flies direct paths heads for its route's node as it moves apart, its targeting"
          + " off: not for the reference it keeps")
  void aDirectFlyerMovesApartToItsNode(@TempDir Path folder) throws IOException {
    withClone(folder, rows -> {});
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> GameData.columns(rows, "Balloon").put("FlyDirectPaths", true));
    GameTables tables = GameTables.load(folder);
    Scene scene = new Scene(tables);
    // Off the centre of its cell along the width, toward the enemy princess tower ahead of it: the
    // node lies to the left, the reference's ring to the right.
    int fromX = X + 249;
    CharacterEntity balloon =
        scene.match.deploy(
            0, new BattleRecords(tables).unit("Balloon"), LEVEL, 0, fromX, Y, "balloon");
    scene.clone(CAST_TICK);
    CharacterEntity clone = scene.named("balloon_clone0");
    assertThat(balloon.getView().getState()).isEqualTo(GridEntityState.CLONE_SETUP);
    assertThat(balloon.getTargeting().getReference()).isNotNull();
    assertThat(balloon.isActive(CharacterEntity.TARGETING_SLOT)).isFalse();
    int startX = balloon.getView().getX();
    int startY = balloon.getView().getY();
    int nodeX = startX / 500 * 500 + 250;
    assertThat(nodeX).isLessThan(startX);

    scene.step(9);
    // Both fly along the length and drift toward the centre of their node's cell.
    assertThat(balloon.getView().getY()).isGreaterThan(startY);
    assertThat(balloon.getView().getX()).isLessThan(startX).isGreaterThanOrEqualTo(nodeX);
    assertThat(clone.getView().getY()).isLessThan(startY);
    assertThat(clone.getView().getX()).isLessThan(startX).isGreaterThanOrEqualTo(nodeX);
  }

  @Test
  @DisplayName(
      "a Skeleton Balloon, whose hit destroys it and launches nothing, is cloned; its clone's"
          + " barrel, a building without hit points, is a clone, and so are the Skeletons its death"
          + " action spawns, of 1 hit point of 1")
  void aSkeletonBalloonsClone() {
    Scene scene = new Scene();
    CharacterEntity balloon = scene.still(0, 0, "SkeletonBalloon", X, Y, "balloon");
    balloon.setActive(CharacterEntity.MOVEMENT_SLOT, true);
    scene.clone(CAST_TICK);
    CharacterEntity clone = scene.named("balloon_clone0");
    assertThat(clone.isClone()).isTrue();
    assertThat(clone.getHitPoints().getMaximum()).isEqualTo(1);
    scene.step(15);
    scene.match.getWorld().kill(clone, null);
    scene.step(1);

    CharacterEntity barrel = scene.named("balloon_clone0_0");
    assertThat(barrel.getData().building()).isTrue();
    assertThat(barrel.getHitPoints()).isNull();
    assertThat(barrel.isClone()).isTrue();
    // Its deploy ends, it leaves, and its death action spawns the Skeletons around it.
    scene.step(barrel.getData().deployTimeMs() / 50 + 2);
    assertThat(present(scene, "balloon_clone0_0")).isFalse();
    List<CharacterEntity> skeletons =
        scene.match.getBattle().getHolder().entities().stream()
            .filter(e -> e instanceof CharacterEntity c && c.name().startsWith("balloon_clone0_0_"))
            .map(e -> (CharacterEntity) e)
            .toList();
    assertThat(skeletons).isNotEmpty();
    assertThat(skeletons)
        .allSatisfy(
            skeleton -> {
              assertThat(skeleton.isClone()).as(skeleton.name()).isTrue();
              assertThat(skeleton.getHitPoints().getHitPoints()).isEqualTo(1);
              assertThat(skeleton.getHitPoints().getMaximum()).isEqualTo(1);
            });
  }

  @Test
  @DisplayName(
      "a clone whose hit destroys it launches its projectile as the original does: the target"
          + " takes the same damage from each")
  void aSpiritsCloneLaunchesItsProjectile() {
    // The target, which cannot reach the spirits, takes the original's hit alone without a Clone.
    int alone = spiritHits("FireSpirits", false);
    assertThat(alone).isPositive();
    assertThat(spiritHits("FireSpirits", true)).isEqualTo(2 * alone);
  }

  @Test
  @DisplayName(
      "a clone whose hit destroys it and launches a projectile that applies a buff, hops on or"
          + " makes an area effect launches it as the original does: the target takes the same"
          + " damage from each, and the clone's projectile lands as the original's")
  void aSpiritsCloneFreezesHopsAndHeals() {
    for (String row : List.of("IceSpirits", "ElectroSpirit", "HealSpirit")) {
      UnitData spirit = GameData.unit(row);
      assertThat(spirit.projectile().dealsOnlyDamage()).as(row).isFalse();
      int alone = spiritHits(row, false);
      assertThat(alone).as(row).isPositive();
      assertThat(spiritHits(row, true)).as(row).isEqualTo(2 * alone);
    }
  }

  /**
   * Places a spirit of side 0 and, once the Clone (if cast) has landed, an enemy Knight held still
   * at the spirit's range, and steps until every spirit has hit and left.
   *
   * @param row the spirit's row, one whose hit destroys it and launches a projectile
   * @return the hit points the Knight lost
   */
  private static int spiritHits(String row, boolean cloned) {
    Scene scene = new Scene();
    UnitData spirits = GameData.unit(row);
    assertThat(spirits.kamikaze()).isTrue();
    assertThat(spirits.hasProjectile()).isTrue();
    scene.still(0, 0, row, X, Y, "spirit");
    if (cloned) {
      scene.clone(CAST_TICK);
      CharacterEntity clone = scene.named("spirit_clone0");
      assertThat(clone.isClone()).isTrue();
      assertThat(clone.getHitPoints().getMaximum()).isEqualTo(1);
    } else {
      scene.step(CAST_TICK + 1);
    }
    CharacterEntity knight = scene.still(scene.tick, 1, "Knight", X, Y + spirits.range(), "knight");
    int before = knight.getHitPoints().getMaximum();
    for (int i = 0; i < 400 && (present(scene, "spirit") || present(scene, "spirit_clone0")); i++) {
      scene.step(1);
    }
    assertThat(present(scene, "spirit")).isFalse();
    assertThat(present(scene, "spirit_clone0")).isFalse();
    scene.step(40);
    assertThat(scene.refused).isEmpty();
    return before - knight.getHitPoints().getHitPoints();
  }

  @Test
  @DisplayName(
      "a clone launches its death projectile as any unit when the projectile only deals damage;"
          + " one that hops on is refused")
  void aClonesDeathProjectile(@TempDir Path folder) throws IOException {
    String projectile = GameData.unit("FireSpirits").projectile().name();
    withClone(folder, rows -> {});
    GameData.alterLoaded(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "Knight")
                .put("DeathSpawnProjectile", projectile)
                .put("DeathSpawnCount", 1));
    Scene scene = new Scene(GameTables.load(folder));
    scene.stillOfTables(0, 0, "Knight", X, Y, "knight");
    scene.clone(CAST_TICK);
    CharacterEntity clone = scene.named("knight_clone0");
    assertThat(clone.isClone()).isTrue();
    scene.step(15);
    scene.match.getWorld().kill(clone, null);
    scene.step(2);
    assertThat(scene.deathProjectiles).containsExactly("knight_clone0 " + projectile);

    // The same projectile written to hop on to a second target.
    Path hops = folder.resolve("hops");
    withClone(hops, rows -> {});
    GameData.alterLoaded(
        hops,
        "characters",
        rows ->
            GameData.columns(rows, "Knight")
                .put("DeathSpawnProjectile", projectile)
                .put("DeathSpawnCount", 1));
    GameData.alterLoaded(
        hops, "projectiles", rows -> GameData.columns(rows, projectile).put("ChainedHitCount", 2));
    Scene hopping = new Scene(GameTables.load(hops));
    hopping.stillOfTables(0, 0, "Knight", X, Y, "knight");
    hopping.clone(CAST_TICK);
    CharacterEntity hoppingClone = hopping.named("knight_clone0");
    hopping.step(15);
    hopping.match.getWorld().kill(hoppingClone, null);
    assertThatThrownBy(() -> hopping.step(2))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("is a clone, whose projectiles carry it to what they spawn");
  }

  @Test
  @DisplayName(
      "a clone's death projectile that spawns characters makes each a clone of 1 hit point of 1,"
          + " where the original's makes them whole")
  void aClonesDeathProjectileSpawnsClones(@TempDir Path folder) throws IOException {
    // A Knight that launches the Fire Spirits' projectile as it dies, written to spawn a Skeleton
    // where it lands, as the Phoenix's fireball makes its egg.
    String projectile = GameData.unit("FireSpirits").projectile().name();
    withClone(folder, rows -> {});
    GameData.alterLoaded(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "Knight")
                .put("DeathSpawnProjectile", projectile)
                .put("DeathSpawnCount", 1));
    GameData.alterLoaded(
        folder,
        "projectiles",
        rows -> GameData.columns(rows, projectile).put("SpawnCharacter", "Skeleton"));
    Scene scene = new Scene(GameTables.load(folder));
    CharacterEntity knight = scene.stillOfTables(0, 0, "Knight", X, Y, "knight");
    scene.clone(CAST_TICK);
    CharacterEntity clone = scene.named("knight_clone0");
    assertThat(clone.isClone()).isTrue();
    scene.step(15);

    // The clone dies: its projectile lands and makes a clone Skeleton.
    scene.match.getWorld().kill(clone, null);
    for (int i = 0; i < 100 && scene.impactSpawns.isEmpty(); i++) {
      scene.step(1);
    }
    assertThat(scene.deathProjectiles).containsExactly("knight_clone0 " + projectile);
    assertThat(scene.impactSpawns).hasSize(1);
    CharacterEntity cloneChild = scene.impactSpawns.get(0);
    assertThat(cloneChild.isClone()).isTrue();
    assertThat(cloneChild.getHitPoints().getHitPoints()).isEqualTo(1);
    assertThat(cloneChild.getHitPoints().getMaximum()).isEqualTo(1);

    // The original dies the same way: its Skeleton is no clone and keeps its hit points.
    scene.match.getWorld().kill(knight, null);
    for (int i = 0; i < 100 && scene.impactSpawns.size() < 2; i++) {
      scene.step(1);
    }
    assertThat(scene.impactSpawns).hasSize(2);
    CharacterEntity child = scene.impactSpawns.get(1);
    assertThat(child.isClone()).isFalse();
    assertThat(child.getHitPoints().getMaximum()).isGreaterThan(1);
    assertThat(child.getHitPoints().getHitPoints()).isEqualTo(child.getHitPoints().getMaximum());
    assertThat(scene.refused).isEmpty();
  }

  @Test
  @DisplayName(
      "a unit whose hit destroys it and launches a projectile that spawns a projectile, and a"
          + " building with hit points a clone spawns, are refused as clones")
  void theCloneRefusals(@TempDir Path folder) throws IOException {
    // A Fire Spirit whose projectile is written to spawn another one where it lands.
    Path spawns = folder.resolve("spawns");
    withClone(spawns, rows -> {});
    String projectile = GameData.unit("FireSpirits").projectile().name();
    GameData.alterLoaded(
        spawns,
        "projectiles",
        rows -> GameData.columns(rows, projectile).put("SpawnProjectile", projectile));
    Scene spirits = new Scene(GameTables.load(spawns));
    spirits.stillOfTables(0, 0, "FireSpirits", X, Y, "spirit");
    assertThatThrownBy(
            () -> {
              spirits.clone(CAST_TICK);
              spirits.step(3);
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(
            "a unit whose hit destroys it and launches a projectile that spawns a character or a"
                + " projectile");

    // The Skeleton Balloon's barrel spawning a Cannon as it dies: a clone barrel's Cannon would be
    // a clone with hit points.
    withClone(folder, rows -> {});
    GameData.alterLoaded(
        folder,
        "actions",
        rows -> GameData.fields(rows, "SkeletonBalloonDeathSpawn").put("SpawnData", "Cannon"));
    Scene scene = new Scene(GameTables.load(folder));
    CharacterEntity balloon = scene.still(0, 0, "SkeletonBalloon", X, Y, "balloon");
    balloon.setActive(CharacterEntity.MOVEMENT_SLOT, true);
    scene.clone(CAST_TICK);
    CharacterEntity clone = scene.named("balloon_clone0");
    scene.step(15);
    scene.match.getWorld().kill(clone, null);
    assertThatThrownBy(() -> scene.step(20))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("a building with hit points");
  }

  @Test
  @DisplayName(
      "a unit still deploying is cloned with its countdown: both are set up as clones, the"
          + " countdowns run down in the setup without a resume, and each move apart past its"
          + " duration holds its unit still with NO_MOVE until the countdown has ended, then"
          + " resumes it")
  void aDeployingUnitIsClonedWithItsCountdown(@TempDir Path folder) throws IOException {
    withClone(folder, rows -> {});
    // A deploy time well past the move apart's duration, so that the move apart waits.
    GameData.alterLoaded(
        folder, "characters", rows -> GameData.columns(rows, "Knight").put("DeployTime", 1500));
    GameTables tables = GameTables.load(folder);
    Scene scene = new Scene(tables);
    CharacterEntity knight =
        scene.match.deploy(
            CAST_TICK, new BattleRecords(tables).unit("Knight"), LEVEL, 0, X, Y, "late");
    scene.clone(CAST_TICK + 2);

    CharacterEntity clone = scene.named("late_clone0");
    List<CharacterEntity> pair = List.of(knight, clone);
    int countdown = knight.getView().getDeployCountdown();
    assertThat(countdown).isPositive();
    assertThat(clone.getView().getDeployCountdown()).isEqualTo(countdown);
    int durationMs = cloneDuration(clone);
    assertThat(countdown).isGreaterThan(durationMs + 100);
    for (CharacterEntity unit : pair) {
      assertThat(unit.getView().getState()).as(unit.name()).isEqualTo(GridEntityState.CLONE_SETUP);
    }
    assertThat(clone.isClone()).isTrue();
    assertThat(clone.getHitPoints().getHitPoints()).isEqualTo(1);

    // 50 ms a visit, in the setup all along: the move apart reaches its duration first and then
    // holds each unit still, NO_MOVE set, until the countdown has ended.
    long noMove = clone.getView().getFlagBits().noMove();
    int[] heldAt = null;
    int visit = 0;
    while (clone.getView().getDeployCountdown() > 0) {
      scene.step(1);
      visit++;
      int left = Math.max(countdown - 50 * visit, 0);
      for (CharacterEntity unit : pair) {
        assertThat(unit.getView().getDeployCountdown()).as(unit.name()).isEqualTo(left);
        if (left > 0) {
          assertThat(unit.getView().getState())
              .as("%s visit %d", unit.name(), visit)
              .isEqualTo(GridEntityState.CLONE_SETUP);
        }
      }
      if (visit * 50 > durationMs) {
        assertThat(clone.actionHolder().tags() & noMove).as("visit %d", visit).isEqualTo(noMove);
        assertThat(knight.actionHolder().tags() & noMove).as("visit %d", visit).isEqualTo(noMove);
        int[] at = {clone.getView().getX(), clone.getView().getY()};
        if (heldAt != null) {
          assertThat(at).as("visit %d", visit).isEqualTo(heldAt);
        }
        heldAt = at;
      }
    }
    assertThat(heldAt).isNotNull();
    // The countdown's end resumes neither: the move apart does, at its next step.
    scene.step(2);
    for (CharacterEntity unit : pair) {
      assertThat(unit.getView().getState())
          .as(unit.name())
          .isNotIn(GridEntityState.CLONE_SETUP, GridEntityState.DEPLOYING);
      assertThat(unit.isClone()).isEqualTo(unit == clone);
    }
  }

  /** The duration of the move apart the Clone gave a unit, from its listed run. */
  private static int cloneDuration(CharacterEntity unit) {
    for (ActionInstance run : unit.actionHolder().running()) {
      if (run.getAction() instanceof Clone clone) {
        return clone.getCloneDurationMs();
      }
    }
    throw new IllegalStateException(unit.name() + " has no move apart");
  }

  @Test
  @DisplayName(
      "a carrier still deploying as the Clone reaches it is cloned with its riders: the carrier's"
          + " clone is set up before its countdown, so it never enters the deploying state and"
          + " makes no riders of its own, and each rider's clone takes its rider's countdown")
  void aDeployingCarrierIsClonedWithItsRiders(@TempDir Path folder) throws IOException {
    GameTables tables = carrierTables(folder);
    Scene scene = new Scene(tables);
    scene.match.play(
        CAST_TICK, new BattleRecords(tables).card("GoblinGiant"), LEVEL, 0, X, Y, "GoblinGiant");
    scene.clone(CAST_TICK + 2);

    CharacterEntity giant = scene.match.getPlays().get(0).units().get(0);
    List<CharacterEntity> riders = List.copyOf(giant.riders());
    assertThat(riders).hasSize(2);
    int countdown = giant.getView().getDeployCountdown();
    assertThat(countdown).isPositive();
    assertThat(scene.cloneNames())
        .containsExactly(
            giant.name() + "_clone0",
            riders.get(0).name() + "_clone0",
            riders.get(1).name() + "_clone0");
    CharacterEntity clone = scene.clones.get(0);
    assertThat(clone.riders()).containsExactlyElementsOf(scene.clones.subList(1, 3));
    assertThat(clone.getView().getState()).isEqualTo(GridEntityState.CLONE_SETUP);
    assertThat(clone.getView().getDeployCountdown()).isEqualTo(countdown);
    for (int i = 0; i < 2; i++) {
      CharacterEntity rider = riders.get(i);
      CharacterEntity riderClone = scene.clones.get(1 + i);
      assertThat(rider.getView().getDeployCountdown()).isPositive();
      assertThat(riderClone.getView().getState()).isEqualTo(GridEntityState.CLONE_SETUP);
      assertThat(riderClone.getView().getDeployCountdown())
          .isEqualTo(rider.getView().getDeployCountdown());
      assertThat(riderClone.getParent()).isSameAs(clone);
    }

    // Through the countdowns and the move apart: no rider beyond the two riders and their clones.
    scene.step(40);
    long riderRows =
        scene.match.getBattle().getHolder().entities().stream()
            .filter(e -> e instanceof CharacterEntity c && c.getData() == riders.get(0).getData())
            .count();
    assertThat(riderRows).isEqualTo(4);
  }

  /**
   * The configured tables with the Clone written, and a Knight written to deploy for 1500 ms and to
   * push the enemies around it as it enters the deploying state: SpawnPushback 1000 and
   * SpawnPushbackRadius 1000.
   */
  private static GameTables pusherTables(Path folder) throws IOException {
    withClone(folder, rows -> {});
    GameData.alterLoaded(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "Knight")
                .put("DeployTime", 1500)
                .put("SpawnPushback", 1000)
                .put("SpawnPushbackRadius", 1000));
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "a unit still deploying whose row pushes as it enters the deploying state is cloned with its"
          + " countdown, and its clone's entry into that state pushes nobody: the clone mark is set"
          + " before the countdown")
  void aDeployingPushersClonePushesNobody(@TempDir Path folder) throws IOException {
    GameTables tables = pusherTables(folder);
    Scene scene = new Scene(tables);
    List<String> pushes = new ArrayList<>();
    scene
        .match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void deployPushed(
                  int t,
                  CharacterEntity unit,
                  int radius,
                  int distance,
                  List<WorldEntity> found,
                  List<WorldEntity> pushed) {
                pushes.add(t + " " + unit.name());
              }
            });
    CharacterEntity pusher =
        scene.match.deploy(
            CAST_TICK, new BattleRecords(tables).unit("Knight"), LEVEL, 0, X, Y, "pusher");
    // An enemy beside it, walking, which a push from the clone's point would reach.
    CharacterEntity enemy =
        scene.match.deploy(
            CAST_TICK + 1, new BattleRecords(tables).unit("Giant"), LEVEL, 1, X + 600, Y, "enemy");
    scene.clone(CAST_TICK + 2);

    CharacterEntity clone = scene.named("pusher_clone0");
    assertThat(pusher.getView().getDeployCountdown()).isPositive();
    assertThat(clone.getView().getDeployCountdown())
        .isEqualTo(pusher.getView().getDeployCountdown());
    assertThat(clone.getView().getState()).isEqualTo(GridEntityState.CLONE_SETUP);
    assertThat(enemy.getView().isAlive()).isTrue();
    // The clone's entry into the deploying state makes no push, its query not even made.
    assertThat(pushes).noneMatch(push -> push.endsWith(" pusher_clone0"));
  }

  @Test
  @DisplayName(
      "a unit whose lifetime drain takes 1 hit point or more a visit is cloned with the drain step"
          + " of its full maximum, so its clone of 1 hit point dies in its registration visit; the"
          + " clone's move apart starts no move, and the unit moves apart alone")
  void aCloneItsRegistrationVisitEnds(@TempDir Path folder) throws IOException {
    withClone(folder, rows -> {});
    GameData.alterLoaded(
        folder, "characters", rows -> GameData.columns(rows, "Knight").put("LifeTime", 20000));
    GameTables tables = GameTables.load(folder);
    Scene scene = new Scene(tables);
    CharacterEntity unit =
        scene.match.deploy(0, new BattleRecords(tables).unit("Knight"), LEVEL, 0, X, Y, "short");
    scene.step(1);
    int maximum = unit.getHitPoints().getMaximum();
    // The drain's step, in hundredths a visit, takes at least 1 hit point a visit.
    assertThat(unit.getHitPoints().getDecayStep()).isEqualTo(HitPoints.decayStep(maximum, 20000));
    assertThat(unit.getHitPoints().getDecayStep()).isGreaterThanOrEqualTo(100);
    scene.clone(CAST_TICK);

    assertThat(scene.cloneNames()).containsExactly("short_clone0");
    CharacterEntity clone = scene.clones.get(0);
    assertThat(clone.getHitPoints().getDecayStep()).isEqualTo(HitPoints.decayStep(maximum, 20000));
    assertThat(HitPoints.alive(clone.getHitPoints())).isFalse();
    assertThat(scene.moves).singleElement().asString().startsWith("short to ");
    assertThat(unit.getView().getState()).isEqualTo(GridEntityState.CLONE_SETUP);
    scene.step(15);
    assertThat(scene.match.getWorld().present()).contains(unit).doesNotContain(clone);
  }

  /** The written action that spawns the written landing area. */
  private static final String LANDING = "Test_CloneLanding";

  /** The written area effect the landing spawns: damage in a circle, on its first update only. */
  private static final String LANDING_AREA = "Test_CloneLandingArea";

  /** The written area effect whose starting action spawns the written landing area. */
  private static final String OUTER_AREA = "Test_CloneOuterArea";

  /** The written area effect whose starting action spawns a Knight. */
  private static final String BREEDER_AREA = "Test_CloneBreederArea";

  /** The written area effect that chains the landing area, which only hits. */
  private static final String CHAIN_AREA = "Test_CloneChainArea";

  /** The written area effect that chains the outer area, which runs an action. */
  private static final String CHAIN_OUTER_AREA = "Test_CloneChainOuterArea";

  /** The landing area's damage at the first level, written. */
  private static final int LANDING_DAMAGE = 100;

  /**
   * The configured tables with the Clone written and the written spawns: {@link #LANDING} spawns
   * the landing area, a circle of 2000 that deals {@link #LANDING_DAMAGE} once to what the common
   * area damage filter takes; the outer area's starting action is the landing, and the breeder
   * area's a spawn of one Knight; the chain area chains the landing area and the chain-outer area
   * the outer one. Each written area effect spawn has its own action row, named after its area with
   * "_Spawn" added.
   */
  private static GameTables cloneSpawnAreaTables(Path folder) throws IOException {
    withClone(
        folder,
        rows -> {
          ObjectNode landing = writtenArea(rows, LANDING_AREA);
          landing.putObject("Damage").put("BaseDamage", LANDING_DAMAGE);
          landing.put("Filter", "CommonAreaDamageFilter");
          landing.put("HitSpeed", 50).put("LifeDuration", 0).put("Radius", 2000);
          writtenArea(rows, OUTER_AREA).put("LifeDuration", 1000).put("OnStartingAction", LANDING);
          writtenArea(rows, BREEDER_AREA)
              .put("LifeDuration", 1000)
              .put("OnStartingAction", "Test_CloneBreederSpawn");
          writtenArea(rows, CHAIN_AREA)
              .put("LifeDuration", 1000)
              .put("SpawnAreaEffectObject", LANDING_AREA);
          writtenArea(rows, CHAIN_OUTER_AREA)
              .put("LifeDuration", 1000)
              .put("SpawnAreaEffectObject", OUTER_AREA);
        });
    GameData.alterLoaded(
        folder,
        "actions",
        rows -> {
          writtenSpawn(rows, LANDING, "AreaEffectType", LANDING_AREA);
          writtenSpawn(rows, OUTER_AREA + "_Spawn", "AreaEffectType", OUTER_AREA);
          writtenSpawn(rows, BREEDER_AREA + "_Spawn", "AreaEffectType", BREEDER_AREA);
          writtenSpawn(rows, CHAIN_AREA + "_Spawn", "AreaEffectType", CHAIN_AREA);
          writtenSpawn(rows, CHAIN_OUTER_AREA + "_Spawn", "AreaEffectType", CHAIN_OUTER_AREA);
          writtenSpawn(rows, "Test_CloneBreederSpawn", "CharacterType", "Knight");
        });
    return GameTables.load(folder);
  }

  /** Writes a common area effect row and returns its columns. */
  private static ObjectNode writtenArea(ObjectNode rows, String name) {
    ObjectNode row = rows.putObject(name);
    row.put("index", rows.size());
    row.put("class", "LogicAreaEffectObjectData");
    ObjectNode columns = row.putObject("columns");
    columns.put("Rarity", "Common");
    return columns;
  }

  /** Writes an action row of the spawn class that spawns one row of a type. */
  private static void writtenSpawn(ObjectNode rows, String name, String type, String data) {
    ObjectNode row = rows.putObject(name);
    row.put("class", "LogicActionSpawnData");
    row.put("ClassType", "ActionSpawn");
    ObjectNode fields = row.putObject("fields");
    fields.put("ClassType", "ActionSpawn");
    fields.put("SpawnData", data);
    fields.put("SpawnType", type);
  }

  /** The area effects of a row the scene has created, in order. */
  private static List<AreaEffectEntity> areasOf(Scene scene, String row) {
    return scene.areas.stream().filter(area -> area.getData().name().equals(row)).toList();
  }

  /** Starts a written action on a unit, the unit its cause, as its starting action would run. */
  private static void run(Scene scene, CharacterEntity unit, String action) {
    BattleWorld world = scene.match.getWorld();
    unit.actionHolder()
        .start(world.getActions().build(action, world.binding(unit)), unit.actionHolder());
  }

  @Test
  @DisplayName(
      "an area effect a clone's action spawns is a clone's, and deals its damage as the"
          + " original's does")
  void anAreaEffectSpawnedFromAClone(@TempDir Path folder) throws IOException {
    GameTables tables = cloneSpawnAreaTables(folder);
    // The clone's: a Knight cloned, its landing right after, an enemy 1000 below that never fights.
    Scene cloned = new Scene(tables);
    cloned.stillOfTables(0, 0, "Knight", X, Y, "knight");
    CharacterEntity enemy = cloned.stillOfTables(0, 1, "Knight", X, Y - 1000, "enemy");
    enemy.setActive(CharacterEntity.TARGETING_SLOT, false);
    cloned.clone(CAST_TICK);
    CharacterEntity clone = cloned.named("knight_clone0");
    int full = enemy.getHitPoints().getHitPoints();
    run(cloned, clone, LANDING);
    AreaEffectEntity fromClone = areasOf(cloned, LANDING_AREA).get(0);
    assertThat(fromClone.isClone()).isTrue();
    assertThat(fromClone.getParent()).isSameAs(clone);
    cloned.step(2);
    int dealt = full - enemy.getHitPoints().getHitPoints();
    assertThat(dealt).isPositive();

    // The original's, at the same level and with the same enemy below it: no clone's, the same hit.
    Scene plain = new Scene(tables);
    CharacterEntity knight = plain.stillOfTables(0, 0, "Knight", X, Y, "knight");
    CharacterEntity other = plain.stillOfTables(0, 1, "Knight", X, Y - 1000, "enemy");
    other.setActive(CharacterEntity.TARGETING_SLOT, false);
    knight.setActive(CharacterEntity.TARGETING_SLOT, false);
    plain.step(CAST_TICK + 1);
    assertThat(knight.getPackedLevel()).isEqualTo(clone.getPackedLevel());
    run(plain, knight, LANDING);
    AreaEffectEntity fromKnight = areasOf(plain, LANDING_AREA).get(0);
    assertThat(fromKnight.isClone()).isFalse();
    plain.step(2);
    assertThat(full - other.getHitPoints().getHitPoints()).isEqualTo(dealt);
  }

  @Test
  @DisplayName(
      "an area effect a clone's area effect spawns is a clone's too, and so is a character it"
          + " spawns, of 1 hit point")
  void aCloneAreasSpawns(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(cloneSpawnAreaTables(folder));
    scene.stillOfTables(0, 0, "Knight", X, Y, "knight");
    scene.clone(CAST_TICK);
    CharacterEntity clone = scene.named("knight_clone0");

    // The outer area's starting action runs as it is admitted, at the tick's closing cleanup.
    run(scene, clone, OUTER_AREA + "_Spawn");
    assertThat(areasOf(scene, OUTER_AREA)).singleElement().matches(AreaEffectEntity::isClone);
    scene.step(1);
    assertThat(areasOf(scene, LANDING_AREA)).singleElement().matches(AreaEffectEntity::isClone);

    run(scene, clone, BREEDER_AREA + "_Spawn");
    AreaEffectEntity breeder = areasOf(scene, BREEDER_AREA).get(0);
    assertThat(breeder.isClone()).isTrue();
    scene.step(1);
    CharacterEntity bred = scene.areaSpawns.isEmpty() ? null : scene.areaSpawns.get(0);
    assertThat(bred).isNotNull();
    assertThat(bred.isClone()).isTrue();
    assertThat(bred.getHitPoints().getHitPoints()).isEqualTo(1);
    assertThat(bred.getHitPoints().getMaximum()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "a clone's area effect chains an area effect that only hits, unmarked, and one that runs an"
          + " action is refused")
  void aCloneAreasChain(@TempDir Path folder) throws IOException {
    Scene scene = new Scene(cloneSpawnAreaTables(folder));
    scene.stillOfTables(0, 0, "Knight", X, Y, "knight");
    scene.clone(CAST_TICK);
    CharacterEntity clone = scene.named("knight_clone0");

    // The chain is made on the area effect's first update, the tick after it is admitted.
    run(scene, clone, CHAIN_AREA + "_Spawn");
    assertThat(areasOf(scene, CHAIN_AREA)).singleElement().matches(AreaEffectEntity::isClone);
    scene.step(2);
    assertThat(areasOf(scene, LANDING_AREA)).singleElement().matches(area -> !area.isClone());

    run(scene, clone, CHAIN_OUTER_AREA + "_Spawn");
    assertThatThrownBy(() -> scene.step(2))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("of a clone chains " + OUTER_AREA);
  }

  @Test
  @DisplayName(
      "a clone's death area is a clone's, and the character its starting action spawns is a clone"
          + " of 1 hit point; the original's spawns a plain one")
  void aClonesDeathAreaSpawnsClones(@TempDir Path folder) throws IOException {
    cloneSpawnAreaTables(folder);
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> GameData.columns(rows, "Knight").put("DeathAreaEffect", BREEDER_AREA));
    Scene scene = new Scene(GameTables.load(folder));
    CharacterEntity knight = scene.stillOfTables(0, 0, "Knight", X, Y, "knight");
    scene.clone(CAST_TICK);
    CharacterEntity clone = scene.named("knight_clone0");
    BattleWorld world = scene.match.getWorld();

    world.kill(clone, null);
    scene.step(2);
    AreaEffectEntity cloneDeath = areasOf(scene, BREEDER_AREA).get(0);
    assertThat(cloneDeath.isClone()).isTrue();
    assertThat(scene.areaSpawns).hasSize(1);
    CharacterEntity bred = scene.areaSpawns.get(0);
    assertThat(bred.isClone()).isTrue();
    assertThat(bred.getHitPoints().getHitPoints()).isEqualTo(1);

    world.kill(knight, null);
    scene.step(2);
    AreaEffectEntity knightDeath = areasOf(scene, BREEDER_AREA).get(1);
    assertThat(knightDeath.isClone()).isFalse();
    assertThat(scene.areaSpawns).hasSize(2);
    assertThat(scene.areaSpawns.get(1).isClone()).isFalse();
    assertThat(scene.areaSpawns.get(1).getHitPoints().getHitPoints()).isGreaterThan(1);
  }

  /**
   * The configured tables with the Clone written, and a Knight whose row names a Wizard as its
   * cloned version: the Knight common, running the starting action given, and the Wizard rare, its
   * starting action a group that puts Invisibility on it for 999999 ms after 50.
   *
   * @param knightStarting the Knight's starting action
   */
  private static GameTables clonedVersionTables(Path folder, String knightStarting)
      throws IOException {
    withClone(folder, rows -> {});
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "Knight")
              .put("ClonedVersion", "Wizard")
              .put("Rarity", "Common")
              .put("OnStartingAction", knightStarting);
          GameData.columns(rows, "Wizard")
              .put("Rarity", "Rare")
              .put("OnStartingAction", "Ghost_EV1_Clone_Apply_Invis_Group");
        });
    GameData.alterLoaded(
        folder,
        "actions",
        rows -> {
          ObjectNode group = GameData.fields(rows, "Ghost_EV1_Clone_Apply_Invis_Group");
          group.putArray("SubActionsDelay").add(50);
          GameData.fields(rows, "Ghost_EV1_Clone_Apply_Invis")
              .put("SpawnData", "Invisibility")
              .put("SpawnTime", 999999);
          GameData.fields(rows, "Vines_Air_To_Ground").put("TotalDuration", 5000);
        });
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "a unit whose row names a cloned version is cloned as that row, at the Clone's level re-based"
          + " on that row's rarity; the clone starts that row's starting action as the holder takes"
          + " it in at the tick's end, and the runs its unit has listed stay with the unit")
  void aClonedVersion(@TempDir Path folder) throws IOException {
    GameTables tables = clonedVersionTables(folder, "skeleton_balloon_pop_balloons");
    BattleRecords records = new BattleRecords(tables);
    Scene scene = new Scene(tables);
    CharacterEntity knight =
        scene.match.deploy(0, records.unit("Knight"), LEVEL, 0, X, Y, "knight");
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    scene.step(CAST_TICK - 1);
    List<ActionInstance> runs = List.copyOf(knight.actionHolder().running());
    assertThat(runs).isNotEmpty();
    scene.clone(CAST_TICK);

    CharacterEntity clone = scene.named("knight_clone0");
    assertThat(clone.isClone()).isTrue();
    assertThat(clone.getData().name()).isEqualTo("Wizard");
    assertThat(knight.getData().name()).isEqualTo("Knight");
    // The Clone's level re-based on the cloned version's rarity.
    assertThat(PackedLevel.level(clone.getPackedLevel()))
        .isEqualTo(
            PackedLevel.level(
                PackedLevel.pack(scene.area.packedLevel(), records.unit("Wizard").rarity())));
    assertThat(clone.getHitPoints().getHitPoints()).isEqualTo(1);
    assertThat(clone.getHitPoints().getMaximum()).isEqualTo(1);
    // The unit's runs go on beside its move apart; the clone takes none of them.
    assertThat(knight.actionHolder().running()).containsAll(runs);
    assertThat(clone.actionHolder().running()).doesNotContainAnyElementsOf(runs);

    // The clone's own row's starting action: started at the tick's closing cleanup, so not run in
    // the tick of the Clone's hit; its group runs in the next tick, its buff put on after 50 ms.
    assertThat(clone.getBuffs().carries("Invisibility")).isFalse();
    scene.step(1);
    assertThat(clone.getBuffs().carries("Invisibility")).isTrue();
    assertThat(knight.getBuffs().carries("Invisibility")).isFalse();
  }

  @Test
  @DisplayName(
      "a clone whose row puts its buff while not attacking on as it is made lists the copies of its"
          + " unit's buffs alone: the copy writes over the buff it carried")
  void theCopyWritesOverTheClonesOwnBuff(@TempDir Path folder) throws IOException {
    GameTables tables = clonedVersionTables(folder, "skeleton_balloon_pop_balloons");
    GameData.alterLoaded(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "Wizard")
                .put("BuffWhenNotAttacking", "Invisibility")
                .put("StartWithBuffWhenNotAttacking", true)
                .remove("OnStartingAction"));
    tables = GameTables.load(folder);
    Scene scene = new Scene(tables);
    CharacterEntity knight =
        scene.match.deploy(0, new BattleRecords(tables).unit("Knight"), LEVEL, 0, X, Y, "knight");
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    scene.clone(CAST_TICK);

    CharacterEntity clone = scene.named("knight_clone0");
    assertThat(clone.getBuffs().items())
        .extracting(instance -> instance.getBuff().name())
        .containsExactly("Clone");
    assertThat(clone.getBuffs().invisibleCount()).isZero();
  }

  @Test
  @DisplayName(
      "the copies of a unit's buffs run no start action and reset no charge; then each copy of a"
          + " row that is not cloned comes off the clone, its remove action run, the walk going on"
          + " at the next index; a copy that runs out on the clone runs its remove action")
  void theCopiesRunNoStartActionAndANotClonedCopyComesOff(@TempDir Path folder) throws IOException {
    withClone(folder, rows -> {});
    // An effect row as every hook's action: it changes nothing a step reads.
    String effect = "ChefTower_levelup_effect";
    GameData.alterLoaded(
        folder,
        "character_buffs",
        rows -> {
          GameData.columns(rows, "Rage")
              .put("OnStartAction", effect)
              .put("OnStackedAction", effect)
              .put("OnRemoveAction", effect);
          GameData.columns(rows, "Invisibility")
              .put("NotCloned", true)
              .put("OnStartAction", effect)
              .put("OnRemoveAction", effect);
          GameData.columns(rows, "Freeze").put("NotCloned", true).put("OverrideChargeRange", 250);
        });
    GameTables tables = GameTables.load(folder);
    Scene scene = new Scene(tables);
    CharacterEntity knight =
        scene.match.deploy(0, new BattleRecords(tables).unit("Knight"), LEVEL, 0, X, Y, "knight");
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    scene.step(CAST_TICK - 1);
    BattleWorld world = scene.match.getWorld();
    BuffComponent buffs = knight.getBuffs();
    buffs.apply(world.buffData("Rage"), 2000, knight.getPackedLevel(), null, 0);
    buffs.apply(world.buffData("Invisibility"), 60000, knight.getPackedLevel(), null, 0);
    buffs.apply(world.buffData("Freeze"), 60000, knight.getPackedLevel(), null, 0);
    assertThat(scene.hooks)
        .containsExactly(
            "knight Rage %s start".formatted(effect),
            "knight Invisibility %s start".formatted(effect));
    assertThat(scene.chargeResets).containsExactly("knight Freeze");
    scene.clone(CAST_TICK);

    // The copies are listed with no start action and no charge reset; the walk takes the
    // Invisibility copy off (its remove action scheduled on the clone), and the Freeze copy,
    // moved down onto the index just walked, stays.
    CharacterEntity clone = scene.named("knight_clone0");
    assertThat(clone.getBuffs().items())
        .extracting(instance -> instance.getBuff().name())
        .containsExactly("Rage", "Freeze", "Clone");
    assertThat(clone.getBuffs().invisibleCount()).isZero();
    assertThat(scene.hooks)
        .containsExactly(
            "knight Rage %s start".formatted(effect),
            "knight Invisibility %s start".formatted(effect),
            "knight_clone0 Invisibility %s other".formatted(effect));
    assertThat(scene.chargeResets).containsExactly("knight Freeze");
    // The unit keeps its own.
    assertThat(buffs.items())
        .extracting(instance -> instance.getBuff().name())
        .containsExactly("Rage", "Invisibility", "Freeze", "Clone");

    // The Rage copy runs out on the clone with the unit's own, in the same visit, each running its
    // remove action.
    // Its 2000 ms are 40 visits from the apply, two of them in the Clone's.
    scene.hooks.clear();
    scene.step(37);
    assertThat(scene.hooks).isEmpty();
    scene.step(1);
    assertThat(scene.hooks)
        .containsExactly(
            "knight Rage %s other".formatted(effect),
            "knight_clone0 Rage %s other".formatted(effect));
  }

  /** The unit's listed run of the air-to-ground row. */
  private static AirToGroundRun airToGroundRun(CharacterEntity unit, String row) {
    return unit.actionHolder().running().stream()
        .filter(run -> run.getAction().name().equals(row))
        .map(AirToGroundRun.class::cast)
        .findFirst()
        .orElseThrow(() -> new AssertionError(unit.name() + " lists no run of " + row));
  }

  @Test
  @DisplayName(
      "a unit running an air-to-ground action has it started again on its clone, its unit the"
          + " cause: the clone's run takes the phase and the counter of its unit's run, so the two"
          + " holds end together")
  void anAirToGroundRunStartsAgainOnTheClone(@TempDir Path folder) throws IOException {
    GameTables tables = clonedVersionTables(folder, "Vines_Air_To_Ground");
    Scene scene = new Scene(tables);
    CharacterEntity knight =
        scene.match.deploy(0, new BattleRecords(tables).unit("Knight"), LEVEL, 0, X, Y, "knight");
    knight.setActive(CharacterEntity.MOVEMENT_SLOT, false);
    scene.clone(CAST_TICK);

    CharacterEntity clone = scene.named("knight_clone0");
    AirToGroundRun original = airToGroundRun(knight, "Vines_Air_To_Ground");
    AirToGroundRun copy = airToGroundRun(clone, "Vines_Air_To_Ground");
    assertThat(copy).isNotSameAs(original);
    // A ground unit's hold, part way through: the copy is not a run started over.
    assertThat(original.phase()).isEqualTo(AirToGroundRun.GROUND);
    assertThat(original.counter()).isLessThan(5000);
    scene.step(1);
    assertThat(copy.phase()).isEqualTo(original.phase());
    assertThat(copy.counter()).isEqualTo(original.counter());
    int left = original.counter() / 50;
    scene.step(left);
    assertThat(original.isFinished()).isFalse();
    assertThat(copy.isFinished()).isFalse();
    scene.step(1);
    assertThat(original.isFinished()).isTrue();
    assertThat(copy.isFinished()).isTrue();
  }

  /**
   * The configured tables with the Clone written, and a Minion that starts the air-to-ground row as
   * it starts, whose clone is a Baby Dragon, which starts no such row of its own.
   */
  private static GameTables flyingHoldTables(Path folder, String row) throws IOException {
    withClone(folder, rows -> {});
    GameData.alterLoaded(
        folder,
        "characters",
        rows ->
            GameData.columns(rows, "Minion")
                .put("OnStartingAction", row)
                .put("ClonedVersion", "BabyDragon"));
    GameData.alterLoaded(
        folder,
        "actions",
        rows -> {
          GameData.fields(rows, "Vines_Air_To_Ground").put("TotalDuration", 5000);
          // The fall of the evolved Royal Hog made long enough for a Clone to land in it, and its
          // action once on the ground a snare on the unit.
          GameData.fields(rows, "RoyalHog_EV1_To_Ground")
              .put("TransitionDuration", 2000)
              .putObject("ActionOnGround")
              .put("action", "Vines_Apply_Snare_Small");
        });
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "the clone of an air unit held on the ground is held too, at its own row's flying height, and"
          + " climbs back with its unit")
  void anAirUnitsCloneIsHeldWithIt(@TempDir Path folder) throws IOException {
    GameTables tables = flyingHoldTables(folder, "Vines_Air_To_Ground");
    Scene scene = new Scene(tables);
    CharacterEntity minion = scene.stillOfTables(0, 0, "Minion", X, Y, "minion");
    scene.clone(CAST_TICK);

    CharacterEntity clone = scene.named("minion_clone0");
    AirToGroundRun original = airToGroundRun(minion, "Vines_Air_To_Ground");
    AirToGroundRun copy = airToGroundRun(clone, "Vines_Air_To_Ground");
    assertThat(original.phase()).isEqualTo(AirToGroundRun.HELD);
    // The height the clone's own row flies at.
    assertThat(copy.height())
        .isEqualTo(new BattleRecords(tables).unit("BabyDragon").flyingHeight())
        .isNotEqualTo(original.height());
    scene.step(2);
    assertThat(copy.phase()).isEqualTo(AirToGroundRun.HELD);
    assertThat(copy.counter()).isEqualTo(original.counter());
    // Held on the ground: the ground layer, as its unit.
    assertThat(minion.getView().isAir()).isFalse();
    assertThat(clone.getView().isAir()).isFalse();
    while (!original.isFinished()) {
      scene.step(1);
    }
    assertThat(copy.isFinished()).isTrue();
    scene.step(1);
    assertThat(clone.getView().isAir()).isTrue();
  }

  @Test
  @DisplayName(
      "a clone that ends its unit's descent schedules no action once on the ground when the row"
          + " does not let a clone trigger its landing actions; its unit lands and takes it")
  void aClonesDescentEndsWithoutTheLandingActions(@TempDir Path folder) throws IOException {
    GameTables tables = flyingHoldTables(folder, "RoyalHog_EV1_To_Ground");
    Scene scene = new Scene(tables);
    CharacterEntity minion = scene.stillOfTables(0, 0, "Minion", X, Y, "minion");
    scene.clone(CAST_TICK);

    CharacterEntity clone = scene.named("minion_clone0");
    AirToGroundRun original = airToGroundRun(minion, "RoyalHog_EV1_To_Ground");
    AirToGroundRun copy = airToGroundRun(clone, "RoyalHog_EV1_To_Ground");
    assertThat(original.phase()).isEqualTo(AirToGroundRun.DESCENDING);
    while (original.phase() == AirToGroundRun.DESCENDING) {
      scene.step(1);
    }
    scene.step(2);
    assertThat(copy.phase()).isEqualTo(AirToGroundRun.HELD);
    assertThat(minion.getBuffs().carries("Vines_Trap_Snare_Small")).isTrue();
    assertThat(clone.getBuffs().carries("Vines_Trap_Snare_Small")).isFalse();
  }

  @Test
  @DisplayName(
      "a clone that would end its descent with its landing actions, which its row lets a clone"
          + " trigger, is refused: no reference holds one")
  void aClonesLandingActionsAreRefused(@TempDir Path folder) throws IOException {
    GameTables tables = flyingHoldTables(folder, "RoyalHog_EV1_To_Ground");
    GameData.alterLoaded(
        folder,
        "actions",
        rows ->
            GameData.fields(rows, "RoyalHog_EV1_To_Ground")
                .put("CloneTriggersLandingActions", true));
    GameTables triggering = GameTables.load(folder);
    Scene scene = new Scene(triggering);
    scene.stillOfTables(0, 0, "Minion", X, Y, "minion");
    scene.clone(CAST_TICK);

    assertThatThrownBy(() -> scene.step(60))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("minion_clone0")
        .hasMessageContaining("CloneTriggersLandingActions");
  }

  /**
   * The configured tables with the Clone written, and a Goblin Giant that carries riders: two Spear
   * Goblins on a ring of 900, each turned by -22 within an arc of 90, riding at 4000; the Giant
   * deploys for 1000 ms and its card plays one Giant.
   */
  private static GameTables carrierTables(Path folder) throws IOException {
    withClone(folder, rows -> {});
    GameData.alterLoaded(
        folder,
        "characters",
        rows -> {
          GameData.columns(rows, "GoblinGiant")
              .put("SpawnCharacter", "SpearGoblinGiant")
              .put("SpawnNumber", 2)
              .put("SpawnRadius", 900)
              .put("DeployTime", 1000);
          GameData.columns(rows, "SpearGoblinGiant")
              .put("SpawnAngleShift", -22)
              .put("SpawnMaxAngle", 90)
              .put("FlyingHeight", 4000);
        });
    GameData.alterLoaded(
        folder,
        "spells_characters",
        rows -> GameData.columns(rows, "GoblinGiant").put("SummonNumber", 1));
    return GameTables.load(folder);
  }

  @Test
  @DisplayName(
      "a carrier's clone takes a clone of each of its riders in their order, each made on its rider"
          + " as any clone is and then attached to the carrier's clone on its rider's angle; the"
          + " riders move apart with the carriers and are resumed with them")
  void aCarrierIsClonedWithItsRiders(@TempDir Path folder) throws IOException {
    GameTables tables = carrierTables(folder);
    Scene scene = new Scene(tables);
    scene.match.play(
        0, new BattleRecords(tables).card("GoblinGiant"), LEVEL, 0, X, Y, "GoblinGiant");
    scene.step(1);
    CharacterEntity giant = scene.match.getPlays().get(0).units().get(0);
    List<CharacterEntity> riders = List.copyOf(giant.riders());
    assertThat(riders).hasSize(2);
    scene.clone(CAST_TICK);

    String name = giant.name();
    assertThat(scene.cloneNames())
        .containsExactly(
            name + "_clone0", riders.get(0).name() + "_clone0", riders.get(1).name() + "_clone0");
    CharacterEntity clone = scene.clones.get(0);
    List<CharacterEntity> riderClones = scene.clones.subList(1, 3);
    // The clone makes no riders of its own: it never enters the deploying state.
    assertThat(clone.riders()).containsExactlyElementsOf(riderClones);
    long spearGoblins =
        scene.match.getBattle().getHolder().entities().stream()
            .filter(e -> e instanceof CharacterEntity c && c.getData() == riders.get(0).getData())
            .count();
    assertThat(spearGoblins).isEqualTo(4);
    for (int i = 0; i < 2; i++) {
      CharacterEntity rider = riders.get(i);
      CharacterEntity riderClone = riderClones.get(i);
      assertThat(riderClone.getParent()).isSameAs(clone);
      assertThat(riderClone.getId()).isGreaterThan(clone.getId());
      assertThat(riderClone.isClone()).isTrue();
      assertThat(riderClone.getHitPoints().getHitPoints()).isEqualTo(1);
      assertThat(riderClone.getHitPoints().getMaximum()).isEqualTo(1);
      assertThat(riderClone.getView().getState()).isEqualTo(GridEntityState.CLONE_SETUP);
      assertThat(rider.getView().getState()).isEqualTo(GridEntityState.CLONE_SETUP);
      assertThat(rider.getParent()).isSameAs(giant);
      assertThat(rider.isClone()).isFalse();
    }
    assertThat(giant.riders()).containsExactlyElementsOf(riders);

    // Visited after the carrier's clone, each rider clone rides on its ring around it, on its
    // rider's angle; each rider, visited before its carrier, rides around the carrier's point as
    // it stood before the visit. The two carriers move apart.
    for (int step = 1; step <= 9; step++) {
      int giantX = giant.getView().getX();
      int giantY = giant.getView().getY();
      int cloneY = clone.getView().getY();
      scene.step(1);
      assertThat(giant.getView().getY()).isGreaterThan(giantY);
      assertThat(clone.getView().getY()).isLessThan(cloneY);
      for (int i = 0; i < 2; i++) {
        CharacterEntity rider = riders.get(i);
        CharacterEntity riderClone = riderClones.get(i);
        assertThat(riderClone.getView().getX() - clone.getView().getX())
            .as("step %d rider %d", step, i)
            .isEqualTo(rider.getView().getX() - giantX);
        assertThat(riderClone.getView().getY() - clone.getView().getY())
            .as("step %d rider %d", step, i)
            .isEqualTo(rider.getView().getY() - giantY);
      }
    }
    scene.step(1);
    for (CharacterEntity unit : List.of(riders.get(0), riders.get(1), clone, giant)) {
      assertThat(unit.getView().getState()).isNotEqualTo(GridEntityState.CLONE_SETUP);
    }
    for (CharacterEntity riderClone : riderClones) {
      assertThat(riderClone.getView().getState()).isNotEqualTo(GridEntityState.CLONE_SETUP);
    }
  }

  /**
   * The configured tables with the five area effects below written in the hit-switch form, as an
   * area effect without a filter writes them: its hit switches and its own-side or enemy switch in
   * place of its filter, a number damage and a crown tower percent in place of the Zap's damage
   * table, one buff a hit, the Earthquake reaching hidden units and starting no area of its own,
   * and the Tornado hitting from its first update. Every 16.402.18 area effect is in the filter
   * form, whose hit pass lists what it reaches through its filter and never asks the clone test;
   * these hold the hit-switch walk's test, which the battle keeps. Their circles and timings, and
   * their buffs' heal, damage and hit frequency, are written as the scenes count on them.
   */
  private static GameTables hitSwitches(Path folder) throws IOException {
    withClone(
        folder,
        rows -> {
          for (String row : List.of("Rage", "HealSpirit", "Zap", "Tornado", "Earthquake")) {
            ObjectNode columns = GameData.columns(rows, row);
            columns.remove("Filter");
            columns.put("HitsGround", true);
            if (!row.equals("Earthquake")) {
              columns.put("HitsAir", true);
            }
            if (row.equals("Rage") || row.equals("HealSpirit")) {
              columns.put("OnlyOwnTroops", true);
            } else {
              columns.put("OnlyEnemies", true);
            }
            if (!row.equals("Zap") && !row.equals("Tornado")) {
              columns.put("BuffNumber", 1);
            }
          }
          GameData.columns(rows, "HealSpirit").put("IgnoreBuildings", true);
          GameData.columns(rows, "Zap").put("Damage", 75).put("CrownTowerDamagePercent", -70);
          GameData.columns(rows, "Tornado").remove("HitSpeedOffset");
          ObjectNode earthquake = GameData.columns(rows, "Earthquake");
          earthquake.put("AffectsHidden", true);
          earthquake.remove("OnStartingAction");
          GameData.columns(rows, "Rage")
              .put("Radius", 3000)
              .put("HitSpeed", 300)
              .put("LifeDuration", 4500)
              .put("BuffTime", 1000);
          GameData.columns(rows, "HealSpirit")
              .put("Radius", 2500)
              .put("LifeDuration", 1000)
              .put("BuffTime", 1000);
          GameData.columns(rows, "Zap").put("Radius", 2500).put("LifeDuration", 1);
          GameData.columns(rows, "Tornado")
              .put("Radius", 5500)
              .put("HitSpeed", 50)
              .put("LifeDuration", 1050)
              .put("BuffTime", 500);
          earthquake
              .put("Radius", 3500)
              .put("HitSpeed", 100)
              .put("LifeDuration", 3000)
              .put("BuffTime", 1000);
        });
    GameData.alterLoaded(
        folder,
        "character_buffs",
        rows -> {
          GameData.columns(rows, "HealSpiritBuff")
              .put("HealPerSecond", 157)
              .put("HitFrequency", 250);
          GameData.columns(rows, "Tornado").put("DamagePerSecond", 60).put("HitFrequency", 550);
          GameData.columns(rows, "Earthquake").put("DamagePerSecond", 32).put("HitFrequency", 1000);
        });
    return GameTables.load(folder);
  }

  /**
   * A Knight and a Minion beside it cloned, and an area effect placed over them on the next tick:
   * for side 0 when it buffs its own troops, for side 1 otherwise.
   */
  private static Scene clonesUnder(Path folder, String row, int side) throws IOException {
    Scene scene = new Scene(hitSwitches(folder));
    scene.still(0, 0, "Knight", X, Y, "knight");
    scene.still(0, 0, "Minion", X + 600, Y, "minion");
    scene.clone(CAST_TICK);
    scene.match.placeAreaEffect(scene.tick, row, LEVEL, side, X, Y, "A");
    return scene;
  }

  private static boolean present(Scene scene, String name) {
    return scene.match.getBattle().getHolder().entities().stream()
        .anyMatch(e -> e instanceof CharacterEntity c && c.name().equals(name));
  }

  @Test
  @DisplayName(
      "a Rage, whose buff heals nothing, buffs the clones as it buffs any unit: its test is asked of"
          + " each clone twice a hit, by the walk and again by the apply, and passes")
  void aRageBuffsTheClones(@TempDir Path folder) throws IOException {
    Scene scene = clonesUnder(folder, "Rage", 0);
    scene.step(6);

    assertThat(scene.gate)
        .containsExactly(
            "31 knight_clone0 Rage area_buff 0 false",
            "31 knight_clone0 Rage area_buff 0 false",
            "31 minion_clone0 Rage area_buff 0 false",
            "31 minion_clone0 Rage area_buff 0 false");
    assertThat(scene.named("knight_clone0").getBuffs().carries("Rage")).isTrue();
    assertThat(scene.named("minion_clone0").getBuffs().carries("Rage")).isTrue();
  }

  @Test
  @DisplayName(
      "a Heal Spirit's area, whose buff heals 157 a second, refuses the clones in its walk and buffs"
          + " their originals")
  void aHealingAreaRefusesTheClones(@TempDir Path folder) throws IOException {
    Scene scene = clonesUnder(folder, "HealSpirit", 0);
    scene.step(2);

    assertThat(scene.gate)
        .containsExactly(
            "26 knight_clone0 HealSpiritBuff area_buff 157 true",
            "26 minion_clone0 HealSpiritBuff area_buff 157 true");
    assertThat(scene.named("knight").getBuffs().carries("HealSpiritBuff")).isTrue();
    assertThat(scene.named("knight_clone0").getBuffs().carries("HealSpiritBuff")).isFalse();
    assertThat(scene.named("minion_clone0").getBuffs().carries("HealSpiritBuff")).isFalse();
  }

  @Test
  @DisplayName(
      "a Zap kills the clones with its damage, whose validator asks the test, and so does not stun"
          + " them: the buff's walk tests alive before it asks")
  void aZapKillsTheClonesBeforeItsBuff(@TempDir Path folder) throws IOException {
    Scene scene = clonesUnder(folder, "Zap", 1);
    scene.step(1);

    assertThat(scene.gate)
        .containsExactly(
            "26 knight_clone0 ZapFreeze area_damage 0 false",
            "26 minion_clone0 ZapFreeze area_damage 0 false");
    // Both left in the tick's closing cleanup, neither carrying the stun.
    assertThat(scene.clones)
        .allSatisfy(
            clone -> {
              assertThat(present(scene, clone.name())).isFalse();
              assertThat(clone.getBuffs().carries("ZapFreeze")).isFalse();
            });
  }

  @Test
  @DisplayName(
      "a Tornado pulls the clones before it buffs them, its pull asking the test first, and its"
          + " damage kills them")
  void aTornadoPullsTheClones(@TempDir Path folder) throws IOException {
    Scene scene = clonesUnder(folder, "Tornado", 1);
    scene.step(1);

    assertThat(scene.gate)
        .containsExactly(
            "26 knight_clone0 Tornado pull 0 false",
            "26 minion_clone0 Tornado pull 0 false",
            "26 knight_clone0 Tornado area_buff 0 false",
            "26 knight_clone0 Tornado area_buff 0 false",
            "26 minion_clone0 Tornado area_buff 0 false",
            "26 minion_clone0 Tornado area_buff 0 false");
    assertThat(scene.named("knight_clone0").getBuffs().carries("Tornado")).isTrue();
    scene.step(12);
    assertThat(present(scene, "knight_clone0")).isFalse();
    assertThat(present(scene, "minion_clone0")).isFalse();
  }

  @Test
  @DisplayName(
      "an Earthquake passes a ground clone and buffs it until its damage kills it; an air clone"
          + " passes the buff test and is refused by the air test after it, so it is asked once a"
          + " hit and never buffed")
  void anEarthquakeOnAGroundAndAnAirClone(@TempDir Path folder) throws IOException {
    Scene scene = clonesUnder(folder, "Earthquake", 1);
    scene.step(2);

    assertThat(scene.gate)
        .containsExactly(
            "27 knight_clone0 Earthquake area_buff 0 false",
            "27 knight_clone0 Earthquake area_buff 0 false",
            "27 minion_clone0 Earthquake area_buff 0 false");
    assertThat(scene.named("knight_clone0").getBuffs().carries("Earthquake")).isTrue();
    assertThat(scene.named("minion_clone0").getBuffs().carries("Earthquake")).isFalse();
    scene.step(30);
    assertThat(present(scene, "knight_clone0")).isFalse();
    assertThat(present(scene, "minion_clone0")).isTrue();
  }
}
