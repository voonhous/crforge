package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.Shipped;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Electro Giant's reflect where the reference runs leave it: the reach, strict and counted from
 * both radii; an area effect never struck back; nothing while the Giant is stunned; the damage once
 * per attack while every hit of it re-applies the buff; a dying Giant still striking back, but not
 * at a hit that finds it already down; a building that is no crown tower taking the ordinary
 * damage; an area hit struck back; nothing for its own side or a shot whose row ignores the
 * reflect; a death the reflected damage causes running at once; a rider's shot struck back at its
 * parent; and the hits the battle refuses.
 */
class BattleElectroGiantTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /** The Electro Giant's point, on the bottom side's left, away from every tower. */
  private static final int X = 3500;

  private static final int Y = 11000;

  /** The Electro Giant's row, whose reflect columns the expected values read. */
  private static final GameRow GIANT = Shipped.unitRow("ElectroGiant");

  /** The buff the reflect hands the attacker. */
  private static final String BUFF = Shipped.text(GIANT, "ReflectedAttackBuff");

  /** The reflect's radius plus the Electro Giant's radius and a Musketeer's (3250). */
  private static final int MUSKETEER_REACH =
      Shipped.number(GIANT, "ReflectedAttackRadius")
          + Shipped.number(GIANT, "CollisionRadius")
          + Shipped.number(Shipped.unitRow("Musketeer"), "CollisionRadius");

  /** A battle with the towers passive, one Electro Giant that never moves, and every reflect. */
  private static final class Scene {
    final Standard1v1Battle match;
    final List<Reflection> reflections = new ArrayList<>();
    final CharacterEntity giant;
    int tick;

    Scene() {
      this(GameData.tables());
    }

    Scene(GameTables tables) {
      match = new Standard1v1Battle(tables, LEVEL, false);
      match
          .getWorld()
          .addObserver(
              new WorldObserver() {
                @Override
                public void reflected(int t, Reflection reflection) {
                  reflections.add(reflection);
                }
              });
      giant = still(0, 0, "ElectroGiant", X, Y, "giant");
    }

    /** A unit placed at a tick that never moves, under a name of its own. */
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

    /** The reflects of hits whose source is the named unit. */
    List<Reflection> from(String name) {
      return reflections.stream()
          .filter(r -> r.source() != null && r.source().name().equals(name))
          .toList();
    }

    /** Whether the battle holds an entity of the row. */
    boolean holds(String row) {
      for (BattleEntity entity : match.getBattle().getHolder().entities()) {
        if (entity instanceof WorldEntity unit && unit.getData().name().equals(row)) {
          return true;
        }
      }
      return false;
    }
  }

  @Test
  @DisplayName(
      "a shot from strictly inside the reflect radius plus both radii is struck back, one from"
          + " exactly that far is not")
  void theReach() {
    Scene scene = new Scene();
    scene.still(0, 1, "Musketeer", X, Y + MUSKETEER_REACH - 1, "inside");
    scene.still(0, 1, "Musketeer", X + MUSKETEER_REACH, Y, "edge");
    scene.step(60);

    assertThat(scene.from("inside")).isNotEmpty();
    Reflection first = scene.from("inside").get(0);
    assertThat(first.buff()).isEqualTo(BUFF);
    assertThat(first.buffTimeMs()).isEqualTo(Shipped.number(GIANT, "ReflectedAttackBuffDuration"));
    assertThat(first.damage()).isEqualTo(reflectedDamage());
    assertThat(first.attacker()).isInstanceOf(ProjectileEntity.class);
    assertThat(scene.from("edge")).isNotEmpty().allSatisfy(r -> assertThat(r.buff()).isNull());
  }

  @Test
  @DisplayName(
      "a Zap written in the hit-switch form, an area effect, is not struck back, and while its stun"
          + " holds the Giant strikes back at nothing")
  void anAreaEffectAndAStun(@TempDir Path folder) throws IOException {
    // The configured Zap is in the filter form, whose damage is a typed hit (see
    // aFilterFormZapIsStruckBack); written with its hit switches it deals a plain area hit.
    GameTables tables =
        GameData.altered(
            folder,
            "area_effect_objects",
            rows -> {
              ObjectNode zap = GameData.columns(rows, "Zap");
              zap.remove("Filter");
              zap.put("HitsAir", true);
              zap.put("HitsGround", true);
              zap.put("OnlyEnemies", true);
              zap.put("Damage", 75);
              zap.put("CrownTowerDamagePercent", -70);
            });
    Scene scene = new Scene(tables);
    CharacterEntity knight = scene.still(0, 1, "Knight", X, Y + 1500, "knight");
    // Hold the Knight back until the Zap has landed.
    knight.setActive(CharacterEntity.TARGETING_SLOT, false);
    scene.step(25);
    scene.match.placeAreaEffect(scene.tick, "Zap", LEVEL, 1, X, Y, "Z");
    scene.step(1);

    assertThat(scene.reflections).hasSize(1);
    Reflection zap = scene.reflections.get(0);
    assertThat(zap.attacker()).isInstanceOf(AreaEffectEntity.class);
    assertThat(zap.source()).isNull();
    assertThat(zap.buff()).isNull();
    assertThat(knight.getHitPoints().getHitPoints()).isEqualTo(knight.getHitPoints().getMaximum());

    scene.step(30);
    List<Reflection> knights = scene.from("knight");
    assertThat(knights).isNotEmpty();
    // The Zap's stun lasts ten visits; a hit while it holds is reflected by nothing.
    assertThat(knights.stream().filter(r -> r.hitSpeed() < 1))
        .allSatisfy(r -> assertThat(r.buff()).isNull());
    assertThat(knights.stream().filter(r -> r.hitSpeed() >= 1))
        .allSatisfy(r -> assertThat(r.damage()).isEqualTo(reflectedDamage()));
  }

  @Test
  @DisplayName("the configured Zap, of the filter form, is not struck back")
  @Disabled(
      "the filter-form Zap's damage is a typed hit, whose reflect on the Electro Giant the battle"
          + " refuses; what the game does with it is not traced")
  void aFilterFormZapIsStruckBack() {
    Scene scene = new Scene();
    scene.step(25);
    scene.match.placeAreaEffect(scene.tick, "Zap", LEVEL, 1, X, Y, "Z");
    scene.step(1);

    assertThat(scene.reflections).hasSize(1);
    Reflection zap = scene.reflections.get(0);
    assertThat(zap.attacker()).isInstanceOf(AreaEffectEntity.class);
    assertThat(zap.buff()).isNull();
  }

  @Test
  @DisplayName(
      "every pellet of one Hunter attack re-applies the buff, and only the first deals the damage")
  void oncePerAttack() {
    Scene scene = new Scene();
    scene.still(0, 1, "Hunter", X, Y + 2000, "hunter");
    scene.step(60);

    List<Reflection> pellets = scene.from("hunter");
    assertThat(pellets.size()).isGreaterThan(1);
    assertThat(pellets).allSatisfy(r -> assertThat(r.buff()).isEqualTo(BUFF));
    assertThat(pellets.stream().filter(r -> r.damage() > 0)).hasSize(1);
  }

  @Test
  @DisplayName(
      "the hit that kills the Giant is still struck back, before its own death; a building that is"
          + " no crown tower takes the ordinary damage")
  void aDyingGiantAndABuilding() {
    Scene scene = new Scene();
    CharacterEntity cannon = scene.still(0, 1, "Cannon", X + 1500, Y + 1000, "cannon");
    scene.step(30);
    int before = cannon.getHitPoints().getHitPoints();
    scene.giant.getHitPoints().setHitPoints(1);
    while (scene.from("cannon").isEmpty() && scene.tick < 200) {
      scene.step(1);
    }

    Reflection last = scene.from("cannon").get(0);
    assertThat(last.damage()).isEqualTo(reflectedDamage());
    assertThat(last.hitPointsBefore()).isLessThanOrEqualTo(before);
    assertThat(scene.giant.getHitPoints().getHitPoints()).isZero();
  }

  @Test
  @DisplayName("a Valkyrie's area hit, which no shot carries, is struck back at the Valkyrie")
  void anAreaHit() {
    Scene scene = new Scene();
    CharacterEntity valkyrie = scene.still(0, 1, "Valkyrie", X, Y + 1500, "valkyrie");
    scene.step(60);

    List<Reflection> hits = scene.from("valkyrie");
    assertThat(hits).isNotEmpty();
    assertThat(hits.get(0).attacker()).isSameAs(valkyrie);
    assertThat(hits.get(0).buff()).isEqualTo(BUFF);
    assertThat(hits.get(0).damage()).isEqualTo(reflectedDamage());
  }

  @Test
  @DisplayName(
      "a second hit on the tick the Giant falls, which finds it with no hit points, is not struck"
          + " back")
  void aGiantAlreadyDown() {
    Scene scene = new Scene();
    CharacterEntity first = scene.still(0, 1, "Knight", X - 1300, Y, "first");
    CharacterEntity second = scene.still(0, 1, "Knight", X + 1300, Y, "second");
    scene.step(25);
    scene.giant.getHitPoints().setHitPoints(1);
    BattleWorld world = scene.match.getWorld();
    world.dealDamage(first, scene.giant.getTargetView(), 100, 0, 0);
    world.dealDamage(second, scene.giant.getTargetView(), 100, 0, 0);

    assertThat(scene.giant.getHitPoints().getHitPoints()).isZero();
    assertThat(scene.from("first")).hasSize(1);
    assertThat(scene.from("second")).isEmpty();
  }

  @Test
  @DisplayName("a hit from the Giant's own side is not struck back")
  void aHitFromItsOwnSide() {
    Scene scene = new Scene();
    CharacterEntity friend = scene.still(0, 0, "Knight", X, Y + 1500, "friend");
    scene.step(25);
    scene.match.getWorld().dealDamage(friend, scene.giant.getTargetView(), 100, 0, 0);

    assertThat(scene.from("friend")).hasSize(1);
    assertThat(scene.from("friend").get(0).buff()).isNull();
    assertThat(friend.getBuffs().carries(BUFF)).isFalse();
  }

  @Test
  @DisplayName("a Giant Skeleton killed by the reflected damage dies at once and drops its bomb")
  void aKillingReflect() {
    Scene scene = new Scene();
    CharacterEntity skeleton = scene.still(0, 1, "GiantSkeleton", X, Y + 1500, "skeleton");
    scene.step(25);
    skeleton.getHitPoints().setHitPoints(1);
    while (scene.from("skeleton").isEmpty() && scene.tick < 200) {
      scene.step(1);
    }

    assertThat(scene.from("skeleton").get(0).hitPointsAfter()).isZero();
    assertThat(scene.holds("GiantSkeletonBomb")).isTrue();
  }

  @Test
  @DisplayName(
      "a Spear Goblin's shot is struck back at the Goblin Giant it rides on, not at the rider, and"
          + " the Giant hands the buff to each of its riders")
  void aRidersShot() {
    Scene scene = new Scene();
    // Played for the top side, it walks down onto the Giant; its riders shoot from farther out.
    scene.match.play(0, GameData.card("GoblinGiant"), LEVEL, 1, X, Y + 2500, "goblin");

    // Until a shot is struck back with the buff: the first ones may be out of the reflect's reach.
    // The loop guard is a battle's whole length, which the scene's walk and first shots never near.
    int guard = Shipped.battleTicks();
    while (scene.reflections.stream()
            .noneMatch(r -> r.attacker() instanceof ProjectileEntity && r.buff() != null)
        && scene.tick < guard) {
      scene.step(1);
    }
    Reflection buffed =
        scene.reflections.stream()
            .filter(r -> r.attacker() instanceof ProjectileEntity && r.buff() != null)
            .findFirst()
            .orElseThrow();
    // The Giant struck back for its rider's shot, one of the play's units.
    assertThat(scene.match.getPlays().get(0).units()).contains((CharacterEntity) buffed.struck());
    CharacterEntity giant = (CharacterEntity) buffed.struck();
    assertThat(giant.getBuffs().carries(BUFF)).isTrue();
    // Every rider it carries, its row's SpawnNumber.
    assertThat(giant.riders())
        .hasSize(Shipped.number(Shipped.unitRow("GoblinGiant"), "SpawnNumber"))
        .allSatisfy(rider -> assertThat(rider.getBuffs().carries(BUFF)).isTrue());
    List<Reflection> shots =
        scene.reflections.stream().filter(r -> r.attacker() instanceof ProjectileEntity).toList();
    assertThat(shots)
        .isNotEmpty()
        .allSatisfy(
            r -> {
              assertThat(r.source().getData().name()).isEqualTo("SpearGoblinGiant");
              assertThat(r.struck().getData().name()).isEqualTo("GoblinGiant");
            });
  }

  @Test
  @DisplayName("a shot whose row ignores the reflect is not struck back")
  void aShotIgnoringTheReflect(@TempDir Path folder) throws IOException {
    Scene scene =
        new Scene(
            GameData.altered(
                folder,
                "projectiles",
                rows ->
                    GameData.columns(rows, "MusketeerProjectile")
                        .put("IgnoreReflectedAttack", true)));
    scene.still(0, 1, "Musketeer", X, Y + 2000, "musketeer");
    scene.step(60);

    assertThat(scene.from("musketeer")).isNotEmpty().allSatisfy(r -> assertThat(r.buff()).isNull());
  }

  @Test
  @DisplayName(
      "a fallen king's circle kills a reflecting unit and nothing is struck back: the kill has no"
          + " attacker")
  void theCircleKillsIt() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.still(0, 1, "Knight", X, Y + 1500, "knight");
    scene.step(25);
    int knightHitPoints = knight.getHitPoints().getHitPoints();

    scene.match.getWorld().circleKill(scene.giant, 1000);
    // The kill lands at the damage drain of the next step.
    scene.step(1);

    assertThat(scene.giant.getHitPoints().getHitPoints()).isZero();
    assertThat(scene.giant.isRemovable()).isTrue();
    assertThat(scene.reflections).isEmpty();
    assertThat(knight.getHitPoints().getHitPoints()).isEqualTo(knightHitPoints);
    assertThat(knight.getBuffs().carries(BUFF)).isFalse();
  }

  @Test
  @DisplayName("a kill with no killer on a reflecting unit kills it, striking nothing back")
  void aKillWithoutAKiller() {
    Scene scene = new Scene();
    scene.step(25);

    scene.match.getWorld().kill(scene.giant, null);
    // The kill lands at the damage drain of the next step.
    scene.step(1);

    assertThat(scene.giant.getHitPoints().getHitPoints()).isZero();
    assertThat(scene.reflections).isEmpty();
  }

  @Test
  @DisplayName(
      "a kill a killer deals to a reflecting unit, whose reflect no reference reaches, is refused")
  void aKillByAKillerIsRefused() {
    Scene scene = new Scene();
    CharacterEntity knight = scene.still(0, 1, "Knight", X, Y + 1500, "knight");
    scene.step(25);

    // The kill is queued, and refused as the next step's damage drain deals it.
    assertThatThrownBy(
            () -> {
              scene.match.getWorld().kill(scene.giant, knight);
              scene.step(1);
            })
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("reflects and takes a kill");
  }

  /** The reflect's damage at the Giant's level: the row's ReflectedAttackDamage (75 is 192). */
  private static int reflectedDamage() {
    return Shipped.scaled(Shipped.number(GIANT, "ReflectedAttackDamage"), GIANT, LEVEL);
  }
}
