package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.TargetLocks;
import org.crforge.core.battle.data.BattleRecords;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.spawn.SpawnHost;
import org.crforge.core.pathfinding.EntityFlags;
import org.crforge.core.pathfinding.GridEntity;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.core.pathfinding.grid.CellTests;
import org.crforge.core.pathfinding.move.MovementState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Fisherman's special attack where the reference runs leave it: the ring it loads in, both
 * bounds counted from its reference's radius; the load slowed by a buff; the hook on a building,
 * which drags him at the self-drag speed; the pull of a slow troop at no less than 60 of its speed;
 * a hook whose owner leaves, or is stunned, or whose target leaves or dies, before or during the
 * pull; a troop let go on the river; and the hooks and specials the battle refuses.
 */
class BattleFishermanTest {

  /** Which bit of a tag word each flag is, as the configured tables number the game tags. */
  private static final EntityFlags BITS = EntityFlags.of(GameData.tables());

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The Fisherman's point, on the bottom side's left, away from every tower. */
  private static final int X = 3500;

  private static final int Y = 11000;

  /** The Fisherman's row, whose special columns the expected values read. */
  private static final GameRow FISHERMAN = Shipped.unitRow("Fisherman");

  /** The hook's row: its drag speeds. */
  private static final GameRow HOOK =
      Shipped.row("projectiles", Shipped.text(FISHERMAN, "ProjectileSpecial"));

  /** A Knight's radius, from which both bounds of its ring are counted. */
  private static final int KNIGHT_RADIUS =
      Shipped.number(Shipped.unitRow("Knight"), "CollisionRadius");

  /** The near bound of a Knight's ring: its radius plus the special's minimum range. */
  private static final int RING_MIN = KNIGHT_RADIUS + Shipped.number(FISHERMAN, "SpecialMinRange");

  /** The far bound of a Knight's ring: its radius plus the special's range. */
  private static final int RING_MAX = KNIGHT_RADIUS + Shipped.number(FISHERMAN, "SpecialRange");

  /** The special's load, in milliseconds: the row's SpecialLoadTime. */
  private static final int LOAD = Shipped.number(FISHERMAN, "SpecialLoadTime");

  /** The floor of a pulled troop's speed, in percent of the drag-back speed. */
  private static final int PULL_FLOOR = 60;

  /** A distance from the Fisherman inside a Knight's ring, 1500 past its near bound. */
  private static final int IN_RING = RING_MIN + 1500;

  /** A battle with the towers passive, one Fisherman that never walks, and what his hooks do. */
  private static final class Scene {
    final Standard1v1Battle match;
    final List<String> armed = new ArrayList<>();
    final List<String> states = new ArrayList<>();
    final List<String> left = new ArrayList<>();
    final List<ProjectileEntity> hooks = new ArrayList<>();
    final List<Integer> launchTicks = new ArrayList<>();
    final List<String> buffs = new ArrayList<>();
    final List<String> relocations = new ArrayList<>();
    CharacterEntity fisherman;
    int tick;

    Scene() {
      this(GameData.tables());
    }

    Scene(GameTables tables) {
      this(tables, X, Y);
    }

    Scene(GameTables tables, int x, int y) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void specialArmed(
                    int t,
                    CharacterEntity unit,
                    WorldEntity reference,
                    long distanceSquared,
                    int ringMin,
                    int ringMax,
                    int loadMs,
                    int afterMs) {
                  armed.add(
                      t + " " + reference.name() + " " + ringMin + " " + ringMax + " " + afterMs);
                }

                @Override
                public void dragStateSet(
                    int t, ProjectileEntity projectile, WorldEntity unit, int oldState, int now) {
                  states.add(t + " " + unit.name() + " " + oldState + " " + now);
                }

                @Override
                public void holdLeft(int t, WorldEntity unit, ProjectileEntity projectile) {
                  left.add(t + " held " + unit.name());
                }

                @Override
                public void followLeft(int t, WorldEntity unit, ProjectileEntity projectile) {
                  left.add(t + " followed " + unit.name());
                }

                @Override
                public void projectileLaunched(int t, ProjectileEntity projectile) {
                  if (projectile.getData().dragBackSpeed() >= 1) {
                    hooks.add(projectile);
                    launchTicks.add(t);
                  }
                }

                @Override
                public void buffApplied(int t, WorldEntity target, BuffInstance buff) {
                  buffs.add(t + " applied " + target.name());
                }

                @Override
                public void buffRefreshed(
                    int t, WorldEntity target, BuffInstance buff, int before, SpawnHost source) {
                  buffs.add(t + " refreshed " + target.name());
                }

                @Override
                public void relocated(
                    int t, WorldEntity unit, int fromX, int fromY, int toX, int toY) {
                  relocations.add(unit.name() + " " + toX + " " + toY);
                }
              });
      fisherman = still(0, 0, "Fisherman", x, y, "F");
    }

    /** A unit placed at a tick that never walks, under a name of its own. */
    CharacterEntity still(int at, int side, String row, int x, int y, String name) {
      CharacterEntity unit =
          match.deploy(at, match.getWorld().getRecords().unit(row), LEVEL, side, x, y, name);
      unit.setActive(CharacterEntity.MOVEMENT_SLOT, false);
      return unit;
    }

    void step(int ticks) {
      for (int i = 0; i < ticks; i++) {
        match.getBattle().step();
        tick++;
      }
    }

    /** Steps until the first hook has hooked its target, and through that tick. */
    ProjectileEntity stepToTheHook() {
      while ((hooks.isEmpty() || !hooks.get(0).isHooked()) && tick < 200) {
        step(1);
      }
      assertThat(hooks).isNotEmpty();
      return hooks.get(0);
    }
  }

  @Test
  @DisplayName(
      "the load arms on a reference from its radius plus the minimum range out to its radius plus"
          + " the special range, both bounds inclusive")
  void theRing() {
    for (int distance : new int[] {RING_MIN - 1, RING_MIN, RING_MAX, RING_MAX + 1}) {
      Scene scene = new Scene();
      scene.still(0, 1, "Knight", X, Y + distance, "K");
      scene.step(30);
      boolean inRing = distance >= RING_MIN && distance <= RING_MAX;
      if (inRing) {
        assertThat(scene.armed).as("%d", distance).hasSize(1);
        // The load already less the arming visit's 50.
        assertThat(scene.armed.get(0))
            .as("%d", distance)
            .endsWith(" K " + RING_MIN + " " + RING_MAX + " " + (LOAD - 50));
      } else {
        assertThat(scene.armed).as("%d", distance).isEmpty();
      }
    }
  }

  @Test
  @DisplayName(
      "the load takes 50 a visit off the row's load time and fires on the visit that empties it;"
          + " slowed, the slow's share of 50 a visit")
  void theLoad() {
    Scene plain = new Scene();
    plain.still(0, 1, "Knight", X, Y + IN_RING, "K");
    plain.step(60);
    int armedAt = Integer.parseInt(plain.armed.get(0).split(" ")[0]);
    // The load loses 50 a visit and fires on the visit that empties it.
    assertThat(plain.launchTicks.get(0) - armedAt).isEqualTo(visitsToEmpty(LOAD, 50));

    Scene slowed = new Scene();
    slowed.still(0, 1, "Knight", X, Y + IN_RING, "K");
    slowed
        .fisherman
        .getBuffs()
        .apply(GameData.records().buff("IceWizardSlowDown"), 10000, LEVEL, null, 0);
    slowed.step(80);
    int slowedAt = Integer.parseInt(slowed.armed.get(0).split(" ")[0]);
    // Under the slow's hit speed multiplier the load loses that share of 50 a visit, the arming
    // visit's included.
    int slowedStep =
        50
            * (100
                + Shipped.number(
                    Shipped.row("character_buffs", "IceWizardSlowDown"), "HitSpeedMultiplier"))
            / 100;
    assertThat(slowed.armed.get(0)).endsWith(" " + (LOAD - slowedStep));
    assertThat(slowed.launchTicks.get(0) - slowedAt).isEqualTo(visitsToEmpty(LOAD, slowedStep));
  }

  @Test
  @DisplayName(
      "a hook on a building drags the Fisherman to it at the self-drag speed, until within both"
          + " radii, and lets him walk on")
  void aBuilding() {
    Scene scene = new Scene();
    scene.still(0, 1, "Cannon", X, Y + IN_RING, "C");
    scene.stepToTheHook();
    assertThat(scene.states).singleElement().asString().endsWith(" F 2 13");
    GridEntity f = scene.fisherman.getView();
    int before = f.getY();
    scene.step(1);
    // The hook row's DragSelfSpeed.
    assertThat(f.getY() - before).isEqualTo(Shipped.number(HOOK, "DragSelfSpeed"));
    assertThat(f.getX()).isEqualTo(X);
    while (f.getState() == GridEntityState.FOLLOWING_REMOVED_BUILDING && scene.tick < 200) {
      scene.step(1);
    }
    assertThat(scene.states.get(1)).endsWith(" F 13 1");
    // Within the Cannon's radius and his of the point it was hooked at.
    assertThat(scene.hooks.get(0).getY() - f.getY())
        .isLessThanOrEqualTo(
            Shipped.number(Shipped.unitRow("Cannon"), "CollisionRadius")
                + Shipped.number(FISHERMAN, "CollisionRadius"));
    assertThat(scene.fisherman.isActive(CharacterEntity.MOVEMENT_SLOT)).isTrue();
  }

  @Test
  @DisplayName(
      "a troop slower than 60 is pulled at 60 of the drag-back speed, and the pull does not read"
          + " its mass or its pushback")
  void aSlowTroop(@TempDir Path folder) throws IOException {
    // A Knight at 20, and a Golem at 60, which its walk and wait times raise as its row is loaded.
    int drag = Shipped.number(HOOK, "DragBackSpeed");
    GameTables altered =
        GameData.altered(
            folder,
            "characters",
            rows -> {
              GameData.columns(rows, "Knight").put("Speed", 20);
              GameData.columns(rows, "Golem").put("Speed", 60);
            });
    Scene scene = new Scene(altered);
    scene.still(0, 1, "Knight", X, Y + IN_RING, "K");
    ProjectileEntity hook = scene.stepToTheHook();
    int before = hook.getY();
    scene.step(1);
    assertThat(before - hook.getY()).isEqualTo(drag * PULL_FLOOR / 100);

    Scene golem = new Scene(altered);
    golem.still(0, 1, "Golem", X, Y + IN_RING, "G");
    ProjectileEntity pull = golem.stepToTheHook();
    int from = pull.getY();
    golem.step(1);
    // The pull reads the speed the Golem's row is loaded at: its 60, raised by its walk and wait
    // times, (wait + walk) * 1000 / walk * speed / 1000.
    GameRow golemRow = Shipped.unitRow("Golem");
    int walk = Shipped.number(golemRow, "StopMovementAfterMS");
    int wait = Shipped.number(golemRow, "WaitMS");
    int loaded = (wait + walk) * 1000 / walk * 60 / 1000;
    assertThat(from - pull.getY()).isEqualTo(drag * Math.max(PULL_FLOOR, loaded) / 100);
  }

  @Test
  @DisplayName(
      "a pulled troop drops a reference out of its attack range, and its targeting and movement are"
          + " off until it is let go")
  void aPulledTroop() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.still(0, 1, "Knight", X, Y + IN_RING, "K");
    scene.stepToTheHook();
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.FOLLOWING_REMOVED);
    assertThat(knight.getTargeting().getReference()).isNull();
    assertThat(knight.isActive(CharacterEntity.TARGETING_SLOT)).isFalse();
    assertThat(knight.getView().isMovementActive()).isFalse();
    assertThat(scene.fisherman.getView().getState()).isEqualTo(GridEntityState.COMPONENTS_DISABLED);
    // Held: his targeting is on again, his movement is not.
    assertThat(scene.fisherman.isActive(CharacterEntity.TARGETING_SLOT)).isTrue();
    assertThat(scene.fisherman.getView().isMovementActive()).isFalse();
    while (knight.getView().getState() == GridEntityState.FOLLOWING_REMOVED && scene.tick < 200) {
      scene.step(1);
    }
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.STANDING);
    assertThat(knight.isActive(CharacterEntity.TARGETING_SLOT)).isTrue();
    assertThat(knight.getView().isMovementActive()).isTrue();
    assertThat(scene.fisherman.getView().getState()).isEqualTo(GridEntityState.ATTACKING);
    assertThat(scene.fisherman.getView().isMovementActive()).isTrue();
  }

  @Test
  @DisplayName(
      "a Fisherman who leaves during the pull ends his hook where it is, with no second impact; the"
          + " troop stands where the pull left it")
  void theOwnerLeaves() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.still(0, 1, "Knight", X, Y + IN_RING, "K");
    ProjectileEntity hook = scene.stepToTheHook();
    scene.step(2);
    scene.match.getWorld().kill(scene.fisherman, null);
    // Removed at that tick's cleanup; the next tick the troop takes the hook's last place and the
    // hook ends without a step, so the tick after the troop is let go where it stands.
    while (knight.getView().getState() == GridEntityState.FOLLOWING_REMOVED && scene.tick < 200) {
      scene.step(1);
    }
    assertThat(hook.isReleased()).isTrue();
    assertThat(scene.buffs).noneMatch(b -> b.contains("refreshed"));
    assertThat(scene.left).noneMatch(l -> l.contains("held"));
    assertThat(knight.getView().getX()).isEqualTo(hook.getX());
    assertThat(knight.getView().getY()).isEqualTo(hook.getY());
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.STANDING);
  }

  @Test
  @DisplayName("a Fisherman stunned during the pull loses his hook as if he had left")
  void theOwnerIsStunned() {
    Scene scene = new Scene();
    scene.still(0, 1, "Knight", X, Y + IN_RING, "K");
    ProjectileEntity hook = scene.stepToTheHook();
    scene.step(1);
    scene.fisherman.getBuffs().apply(GameData.records().buff("ZapFreeze"), 500, LEVEL, null, 1);
    scene.step(3);
    assertThat(hook.isReleased()).isTrue();
    assertThat(scene.buffs).noneMatch(b -> b.contains("refreshed"));
  }

  @Test
  @DisplayName(
      "a troop that leaves during the pull lets the hook go at once, with an impact on nothing, and"
          + " the Fisherman goes on")
  void theTargetLeaves() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.still(0, 1, "Knight", X, Y + IN_RING, "K");
    ProjectileEntity hook = scene.stepToTheHook();
    scene.step(2);
    scene.match.getWorld().kill(knight, null);
    scene.step(2);
    assertThat(hook.isReleased()).isTrue();
    assertThat(scene.left).anyMatch(l -> l.endsWith("held F"));
    scene.step(1);
    assertThat(scene.fisherman.getView().getState())
        .isNotEqualTo(GridEntityState.COMPONENTS_DISABLED);
  }

  @Test
  @DisplayName(
      "a Bowler is pulled at its row's speed or 60 of the drag-back speed, whichever is larger")
  void aSlowTroopOn16() {
    Scene scene = new Scene();
    scene.still(0, 1, "Bowler", X, Y + IN_RING, "B");
    ProjectileEntity hook = scene.stepToTheHook();
    int before = hook.getY();
    scene.step(1);
    // The Bowler's speed, from its row, against the floor.
    int speed = Shipped.number(Shipped.unitRow("Bowler"), "Speed");
    assertThat(before - hook.getY())
        .isEqualTo(Shipped.number(HOOK, "DragBackSpeed") * Math.max(PULL_FLOOR, speed) / 100);
  }

  @Test
  @DisplayName(
      "a hook whose target leaves while it flies out ends on its next step, where it is, with no"
          + " hook")
  void theTargetLeavesInFlight() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.still(0, 1, "Knight", X, Y + IN_RING, "K");
    stepToTheFlight(scene);
    ProjectileEntity hook = scene.hooks.get(0);
    assertThat(hook.isHooked()).isFalse();
    scene.match.getWorld().kill(knight, null);
    // Removed at that tick's cleanup; the hook's next step ends it where it stands.
    scene.step(1);
    int x = hook.getX();
    int y = hook.getY();
    scene.step(1);
    assertThat(hook.isReleased()).isTrue();
    assertThat(hook.isHooked()).isFalse();
    assertThat(hook.getX()).isEqualTo(x);
    assertThat(hook.getY()).isEqualTo(y);
    assertThat(scene.states).isEmpty();
  }

  @Test
  @DisplayName(
      "a troop that dies during the pull ends the hook on its next step, where it stands, and the"
          + " waiting Fisherman resumes in that step and walks again")
  void theTargetDiesInThePullOn16() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.still(0, 1, "Knight", X, Y + IN_RING, "K");
    ProjectileEntity hook = scene.stepToTheHook();
    scene.step(2);
    // The kill lands at the tick's damage drain, after the hook's step, and the Knight leaves at
    // that tick's cleanup.
    scene.match.getWorld().kill(knight, null);
    scene.step(1);
    assertThat(hook.isReleased()).isFalse();
    int x = hook.getX();
    int y = hook.getY();
    scene.step(1);
    assertThat(hook.isReleased()).isTrue();
    assertThat(hook.getX()).isEqualTo(x);
    assertThat(hook.getY()).isEqualTo(y);
    // Resumed in the hook's step, not at the cleanup that removes the hook, and asked to move: his
    // movement, off while he waited, is on again.
    assertThat(scene.fisherman.getView().getState()).isEqualTo(GridEntityState.MOVING);
    assertThat(scene.fisherman.getView().isMovementActive()).isTrue();
  }

  @Test
  @DisplayName("a troop let go on the river is moved off it as it stands")
  void letGoOnTheRiver() {
    // Away from the bridges: the pull ends over the water.
    Scene scene = new Scene(GameData.tables(), 6500, 13500);
    CharacterEntity knight = scene.still(0, 1, "Knight", 6500, 13500 + IN_RING, "K");
    scene.stepToTheHook();
    while (knight.getView().getState() == GridEntityState.FOLLOWING_REMOVED && scene.tick < 200) {
      scene.step(1);
    }
    assertThat(scene.relocations).hasSize(1);
    GridEntity view = knight.getView();
    assertThat(CellTests.cellBlocked(scene.match.getWorld().getGrid(), view.getX(), view.getY()))
        .isZero();
  }

  @Test
  @DisplayName(
      "a troop still followed on the river cannot be let go, and one pulled cannot be cloned")
  void aPulledTroopAskedElsewhere() {
    Scene scene = new Scene(GameData.tables(), 6500, 13500);
    CharacterEntity knight = scene.still(0, 1, "Knight", 6500, 13500 + IN_RING, "K");
    scene.stepToTheHook();
    while (CellTests.cellBlocked(
                scene.match.getWorld().getGrid(), knight.getView().getX(), knight.getView().getY())
            == 0
        && scene.tick < 200) {
      scene.step(1);
    }
    assertThatThrownBy(() -> knight.requestState(GridEntityState.STANDING))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("still following");

    Scene other = new Scene();
    CharacterEntity pulled = other.still(0, 1, "Knight", X, Y + IN_RING, "K");
    other.stepToTheHook();
    assertThatThrownBy(() -> pulled.requestState(GridEntityState.CLONE_SETUP))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("clone's setup");
  }

  @Test
  @DisplayName("a hook flying at a dashing troop is refused")
  void refusedHooks() {
    Scene dashing = new Scene();
    CharacterEntity dasher = dashing.still(0, 1, "Knight", X, Y + IN_RING, "K");
    stepToTheFlight(dashing);
    GridEntity dasherView = dasher.getView();
    assertThatThrownBy(
            () -> {
              for (int i = 0; i < 20; i++) {
                dasherView.setPendingFlags(dasherView.getPendingFlags() | BITS.dashing());
                dashing.step(1);
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("dashing K");
  }

  @Test
  @DisplayName("a hook arriving at a troop in the air is refused")
  void aTroopInTheAir() {
    Scene jumping = new Scene();
    CharacterEntity jumper = jumping.still(0, 1, "Knight", X, Y + IN_RING, "K");
    stepToTheFlight(jumping);
    assertThatThrownBy(
            () -> {
              for (int i = 0; i < 20; i++) {
                jumper.getView().setState(GridEntityState.JUMPING);
                jumping.step(1);
              }
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("in the air");
  }

  @Test
  @DisplayName("a hook on a troop another holds a lock on is refused")
  void aLockedTroop() {
    Scene locked = new Scene();
    CharacterEntity held = locked.still(0, 1, "Knight", X, Y + IN_RING, "K");
    // Far off on the other half, out of everyone's sight: the lock's holder.
    CharacterEntity holder = locked.still(0, 0, "Knight", 15500, 3000, "holder");
    stepToTheFlight(locked);
    TargetLocks locks = locked.match.getWorld().locks();
    locks.request(holder.getId(), held.getId(), 0, 1, 0);
    locks.postPass();
    assertThatThrownBy(() -> locked.step(1))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("which another holds");
  }

  @Test
  @DisplayName(
      "a special that lists its targets, and a special range without a special projectile, are"
          + " refused with their row")
  void refusedRows(@TempDir Path folder) throws IOException {
    Files.createDirectories(folder.resolve("list"));
    Files.createDirectories(folder.resolve("direct"));
    BattleRecords listing =
        new Standard1v1Battle(
                GameData.altered(
                    folder.resolve("list"),
                    "characters",
                    rows ->
                        GameData.columns(rows, "Fisherman")
                            .put("SpecialAttacksToIgnoreList", true)),
                LEVEL,
                false)
            .getWorld()
            .getRecords();
    assertThat(listing.unit("Fisherman").unmodelledColumns())
        .containsExactly("SpecialAttacksToIgnoreList");
    BattleRecords direct =
        new Standard1v1Battle(
                GameData.altered(
                    folder.resolve("direct"),
                    "characters",
                    rows -> GameData.columns(rows, "Fisherman").put("ProjectileSpecial", "")),
                LEVEL,
                false)
            .getWorld()
            .getRecords();
    assertThat(direct.unit("Fisherman").unmodelledColumns()).containsExactly("SpecialRange");
  }

  @Test
  @DisplayName(
      "a hook at point blank keeps the troop's reference in range as it is pulled; as each is asked"
          + " into its state, the troop's targeting is off and the waiting Fisherman's back on, his"
          + " reference kept")
  void aPointBlankHook() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.still(0, 1, "Knight", X, Y + IN_RING, "K");
    List<String> seen = new ArrayList<>();
    scene
        .match
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void dragStateSet(
                  int t, ProjectileEntity projectile, WorldEntity unit, int oldState, int now) {
                CharacterEntity c = (CharacterEntity) unit;
                seen.add(
                    unit.name()
                        + " "
                        + now
                        + " targeting "
                        + c.isActive(CharacterEntity.TARGETING_SLOT)
                        + " reference "
                        + (c.getTargeting().getReference() == null
                            ? null
                            : c.getTargeting().getReference().name()));
              }
            });
    while (scene.armed.isEmpty() && scene.tick < 200) {
      scene.step(1);
    }
    // The load goes on with the Knight brought into melee reach; it is hooked where it stands.
    knight.getView().setY(Y + 1500);
    scene.stepToTheHook();
    assertThat(seen)
        .containsExactly("K 12 targeting false reference F", "F 14 targeting true reference K");
  }

  @Test
  @DisplayName("the hook is on the ground where it hooks, whatever height it arrived at")
  void theHookIsOnTheGround() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.still(0, 1, "Knight", X, Y + IN_RING, "K");
    // Raised off the ground, the Knight draws the hook's aim, and its arrival, up to it.
    knight.getView().setZ(500);
    ProjectileEntity hook = scene.stepToTheHook();
    assertThat(hook.getZ()).isZero();
  }

  @Test
  @DisplayName(
      "a troop let go has its charge, its pushback's bytes and the point it aimed at reset, and a"
          + " reference the pull took it out of range of dropped")
  void aTroopLetGo() {
    Scene scene = new Scene();
    CharacterEntity prince = scene.still(0, 1, "Prince", X, Y + IN_RING, "P");
    // A friend of the Fisherman's the Prince fights where it stands, out of the Fisherman's way.
    scene.still(0, 0, "Knight", X + 1500, Y + IN_RING, "bait");
    scene.stepToTheHook();
    assertThat(prince.getTargeting().getReference().name()).isEqualTo("bait");
    MovementState movement = prince.getUnit().movement();
    movement.setChargeProgress(5000);
    movement.setPushbackInFlight(1);
    movement.setAttackPushback(1);
    movement.setExplicitX(100);
    movement.setExplicitY(100);
    while (prince.getView().getState() == GridEntityState.FOLLOWING_REMOVED && scene.tick < 200) {
      scene.step(1);
    }
    assertThat(movement.getChargeProgress()).isZero();
    assertThat(movement.getPushbackInFlight()).isZero();
    assertThat(movement.getAttackPushback()).isZero();
    assertThat(movement.getExplicitX()).isEqualTo(-1);
    assertThat(movement.getExplicitY()).isEqualTo(-1);
    assertThat(prince.getTargeting().getReference()).isNull();
  }

  @Test
  @DisplayName("a unit that forgets the hook it followed is resumed")
  void forgettingResumes() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.still(0, 1, "Knight", X + 8000, Y + IN_RING, "K");
    scene.step(30);
    knight.requestState(GridEntityState.STANDING);
    ProjectileEntity hook =
        new ProjectileEntity(
            scene.match.getWorld(),
            scene.match.getWorld().getRecords().projectile("FishermanProjectile"),
            0);
    knight.follow(hook);
    knight.entityRemoved(hook);
    assertThat(scene.left).singleElement().asString().endsWith(" followed K");
    assertThat(knight.getView().getState()).isEqualTo(GridEntityState.MOVING);
  }

  @Test
  @DisplayName("a Fisherman whose row ignores buildings for his special never loads it at a Cannon")
  void ignoringBuildings(@TempDir Path folder) throws IOException {
    Scene scene =
        new Scene(
            GameData.altered(
                folder,
                "characters",
                rows -> GameData.columns(rows, "Fisherman").put("SpecialIgnoreBuildings", true)));
    scene.still(0, 1, "Cannon", X, Y + IN_RING, "C");
    scene.step(60);
    assertThat(scene.armed).isEmpty();
  }

  @Test
  @DisplayName("a hooking projectile that deals damage is refused with its row")
  void aDamagingHook(@TempDir Path folder) throws IOException {
    BattleRecords records =
        new Standard1v1Battle(
                GameData.altered(
                    folder,
                    "projectiles",
                    rows -> GameData.columns(rows, "FishermanProjectile").put("Damage", 100)),
                LEVEL,
                false)
            .getWorld()
            .getRecords();
    assertThat(records.projectile("FishermanProjectile").unmodelledColumns())
        .containsExactly("Damage");
  }

  /** Steps a scene until its first hook is in flight, not yet arrived. */
  private static void stepToTheFlight(Scene scene) {
    while (scene.hooks.isEmpty() && scene.tick < 200) {
      scene.step(1);
    }
    scene.step(1);
  }

  /** The visits a countdown takes to reach 0 or below at a given step. */
  private static int visitsToEmpty(int countdown, int step) {
    return (countdown + step - 1) / step;
  }
}
