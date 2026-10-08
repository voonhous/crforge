package org.crforge.core.battle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.core.pathfinding.math.FixedMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The columns an attack sequence entry of a newer data version sets beyond its damage and
 * projectile, each read while the index selects the entry: the launch's start height and distance,
 * the pace of the attack timer and the sight range in an order of two or more; the first projectile
 * of each hit in an order of two or more, from the entry at the index itself; and, at any length of
 * the order, the number of targets a hit reaches, whether the hit's targets are remembered, the
 * action the hit schedules in place of the row's attack action, and the delay before the first hit
 * of an attack. Each scene alters a row of the configured tables to carry such an entry.
 */
class BattleAttackSequenceEntryTest {

  private static final int LEVEL = Standard1v1Battle.DEFAULT_LEVEL;

  /**
   * One launch of a unit: the tick, the projectile row, where it started and how far that is from
   * the unit.
   */
  private record Launch(int tick, String projectile, int startZ, int distance) {}

  /** One direct hit dealt: the tick and the entity hit. */
  private record Hit(int tick, WorldEntity target) {}

  private static UnitData unit(Standard1v1Battle battle, String row) {
    return battle.getWorld().getRecords().unit(row);
  }

  /** Records every projectile the given unit launches. */
  private static List<Launch> launches(Standard1v1Battle battle, WorldEntity unit) {
    List<Launch> out = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void projectileLaunched(int tick, ProjectileEntity projectile) {
                if (projectile.getOwner() == unit) {
                  out.add(
                      new Launch(
                          tick,
                          projectile.getData().name(),
                          projectile.getStartZ(),
                          distance(unit, projectile)));
                }
              }
            });
    return out;
  }

  /** Records every direct hit dealt to the given entities. */
  private static List<Hit> hits(Standard1v1Battle battle, List<? extends WorldEntity> targets) {
    List<Hit> out = new ArrayList<>();
    battle
        .getWorld()
        .addObserver(
            new WorldObserver() {
              @Override
              public void damageDealt(
                  int tick, WorldEntity target, int damage, DamageResult result) {
                if (targets.contains(target)) {
                  out.add(new Hit(tick, target));
                }
              }
            });
    return out;
  }

  /**
   * The configured tables with the hero Elite Archer's second entry altered and its own attack
   * action removed, so an index set to 1 stays there. The columns the launches are measured against
   * are written: a hit speed of 1100, a start height of 500 and a start distance of 2000.
   */
  private static GameTables archer(Path folder, Consumer<ObjectNode> second) throws IOException {
    return GameData.altered(
        folder,
        "characters",
        rows -> {
          ObjectNode columns = GameData.columns(rows, "EliteArcherHero");
          columns.remove("OnAttackSelfAction");
          columns.put("HitSpeed", 1100).put("ProjectileStartZ", 500);
          columns.put("ProjectileStartRadius", 2000);
          ArrayNode list = (ArrayNode) columns.get("AttackSequenceList");
          second.accept((ObjectNode) list.get(1));
        });
  }

  /**
   * The configured tables with the Electro Wizard given an order of two entries of its own damage,
   * each altered as asked, and two targets a hit.
   */
  private static GameTables wizard(
      Path folder, Consumer<ObjectNode> first, Consumer<ObjectNode> second) throws IOException {
    return GameData.altered(
        folder,
        "characters",
        rows -> {
          ObjectNode columns = GameData.columns(rows, "ElectroWizard");
          columns.put("MultipleTargets", 2);
          columns.putArray("AttackSequence").add(0).add(1);
          columns.put("AttackSequenceMode", "None");
          ArrayNode list = columns.putArray("AttackSequenceList");
          ObjectNode entry0 = list.addObject();
          entry0.put("Damage", columns.get("Damage").asInt());
          first.accept(entry0);
          ObjectNode entry1 = list.addObject();
          entry1.put("Damage", columns.get("Damage").asInt());
          second.accept(entry1);
        });
  }

  /** Steps a battle through the given tick. */
  private static void stepThrough(Standard1v1Battle battle, int tick) {
    while (battle.getBattle().getTick() <= tick) {
      battle.getBattle().step();
    }
  }

  /**
   * The launches of the hero Elite Archer at a Golem, its index set to 1 after its third launch,
   * the second entry altered as asked.
   */
  private static List<Launch> archerLaunches(GameTables tables, List<CharacterEntity> archerOut) {
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, true);
    CharacterEntity archer =
        battle.deploy(0, unit(battle, "EliteArcherHero"), LEVEL, 0, 3500, 14000, "a");
    battle.deploy(0, unit(battle, "Golem"), LEVEL, 1, 3500, 22000, "g");
    archerOut.add(archer);
    List<Launch> launched = launches(battle, archer);
    boolean set = false;
    while (battle.getBattle().getTick() <= 400) {
      int tick = battle.getBattle().getTick();
      battle.getBattle().step();
      if (!set && launched.size() == 3 && launched.get(2).tick() == tick) {
        archer.setAttackSequenceIndex(1, true);
        set = true;
      }
    }
    assertThat(set).isTrue();
    return launched;
  }

  @Test
  @DisplayName(
      "an entry's start height, start distance and hit speed multiplier shape the launches while the"
          + " index selects it")
  void anEntryMovesTheLaunchStartAndThePace(@TempDir Path folder) throws IOException {
    List<CharacterEntity> archers = new ArrayList<>();
    List<Launch> control =
        archerLaunches(
            archer(
                Files.createDirectories(folder.resolve("control")),
                entry -> entry.put("Projectile", "ArcherArrow")),
            archers);
    List<Launch> launched =
        archerLaunches(
            archer(
                Files.createDirectories(folder.resolve("entry")),
                entry -> {
                  entry.put("Projectile", "ArcherArrow");
                  entry.put("CustomProjectileStartZ", 4000);
                  entry.put("CustomProjectileStartRadius", 150);
                  entry.put("HitSpeedMultiplier", 130);
                  entry.put("CustomSightRange", 11500);
                }),
            archers);

    List<Launch> plain = control.subList(3, control.size());
    List<Launch> entry = launched.subList(3, launched.size());
    assertThat(entry).hasSizeGreaterThan(3);
    assertThat(plain).extracting(Launch::projectile).containsOnly("ArcherArrow");
    assertThat(entry).extracting(Launch::projectile).containsOnly("ArcherArrow");
    // The row's ProjectileStartZ 500 and ProjectileStartRadius 2000, then the entry's 4000 and 150.
    assertThat(plain).extracting(Launch::startZ).containsOnly(500);
    assertThat(entry).extracting(Launch::startZ).containsOnly(4000);
    assertThat(plain).extracting(Launch::distance).allMatch(d -> d >= 1998 && d <= 2002);
    assertThat(entry).extracting(Launch::distance).allMatch(d -> d >= 148 && d <= 152);
    // HitSpeed 1100: 22 steps of 50 at the row's pace, 1100 / 65 rounded either way at 130.
    for (int i = 1; i < plain.size(); i++) {
      assertThat(plain.get(i).tick() - plain.get(i - 1).tick()).isEqualTo(22);
    }
    for (int i = 1; i < entry.size(); i++) {
      assertThat(entry.get(i).tick() - entry.get(i - 1).tick()).isBetween(16, 17);
    }
  }

  private static int distance(WorldEntity unit, ProjectileEntity projectile) {
    int dx = projectile.getStartX() - unit.getView().getX();
    int dy = projectile.getStartY() - unit.getView().getY();
    return FixedMath.isqrt(dx * dx + dy * dy);
  }

  @Test
  @DisplayName("an entry's custom first projectile is the first projectile of each hit it selects")
  void anEntryNamesTheFirstProjectile(@TempDir Path folder) throws IOException {
    GameTables tables =
        archer(
            folder,
            entry -> {
              entry.put("Projectile", "ArcherArrow");
              entry.put("CustomFirstProjectile", "Archer_EV1_ArrowDoubleDamage");
            });
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, true);
    CharacterEntity archer =
        battle.deploy(0, unit(battle, "EliteArcherHero"), LEVEL, 0, 3500, 14000, "a");
    battle.deploy(0, unit(battle, "Golem"), LEVEL, 1, 3500, 22000, "g");
    List<Launch> launched = launches(battle, archer);
    int setOn = -1;
    while (battle.getBattle().getTick() <= 300) {
      int tick = battle.getBattle().getTick();
      battle.getBattle().step();
      if (setOn < 0 && launched.size() == 2 && launched.get(1).tick() == tick) {
        archer.setAttackSequenceIndex(1, true);
        setOn = tick;
      }
    }

    assertThat(launched).hasSizeGreaterThan(4);
    assertThat(launched.subList(0, 2))
        .extracting(Launch::projectile)
        .containsOnly("EliteArcherHero_arrow_projectile");
    // One projectile a hit: the first of each, the entry's custom first one.
    assertThat(launched.subList(2, launched.size()))
        .extracting(Launch::projectile)
        .containsOnly("Archer_EV1_ArrowDoubleDamage");
  }

  @Test
  @DisplayName(
      "an entry without a projectile hits directly on a unit whose row fires, in an order of two or"
          + " more")
  void anEntryWithoutAProjectileHitsDirectly(@TempDir Path folder) throws IOException {
    GameTables tables =
        archer(
            folder,
            entry -> {
              entry.remove("Projectile");
              entry.put("Damage", 100);
            });
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, true);
    CharacterEntity archer =
        battle.deploy(0, unit(battle, "EliteArcherHero"), LEVEL, 0, 3500, 14000, "a");
    CharacterEntity golem = battle.deploy(0, unit(battle, "Golem"), LEVEL, 1, 3500, 22000, "g");
    List<Launch> launched = launches(battle, archer);
    List<Hit> hit = hits(battle, List.of(golem));
    int setOn = -1;
    while (battle.getBattle().getTick() <= 300) {
      int tick = battle.getBattle().getTick();
      battle.getBattle().step();
      if (setOn < 0 && launched.size() == 2 && launched.get(1).tick() == tick) {
        archer.setAttackSequenceIndex(1, true);
        setOn = tick;
      }
    }

    assertThat(setOn).isPositive();
    // The row fires, but the entry the index selects has no projectile: nothing more is launched.
    assertThat(launched).hasSize(2);
    // Each of its hits is a direct hit: once the two arrows in flight have landed, the Golem is
    // still hit at the archer's pace.
    int arrowsLanded = setOn + 40;
    assertThat(hit.stream().filter(h -> h.tick() > arrowsLanded)).hasSizeGreaterThanOrEqualTo(3);
  }

  /** The Electro Wizard facing two Knights abreast, both inside its range on its first hit. */
  private static List<CharacterEntity> knights(Standard1v1Battle battle) {
    List<CharacterEntity> out = new ArrayList<>();
    out.add(battle.deploy(0, unit(battle, "Knight"), LEVEL, 1, 3500, 17500, "k0"));
    out.add(battle.deploy(0, unit(battle, "Knight"), LEVEL, 1, 4800, 17600, "k1"));
    return out;
  }

  @Test
  @DisplayName(
      "an entry's custom multiple targets is the number of targets each hit it selects reaches")
  void anEntrySetsTheNumberOfTargets(@TempDir Path folder) throws IOException {
    GameTables tables = wizard(folder, entry -> {}, entry -> entry.put("CustomMultipleTargets", 1));
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, true);
    CharacterEntity wizard =
        battle.deploy(0, unit(battle, "ElectroWizard"), LEVEL, 0, 3500, 12000, "w");
    List<CharacterEntity> knights = knights(battle);
    List<Hit> hit = hits(battle, knights);
    int firstHit = -1;
    while (battle.getBattle().getTick() <= 300) {
      battle.getBattle().step();
      if (firstHit < 0 && !hit.isEmpty()) {
        firstHit = hit.get(0).tick();
        wizard.setAttackSequenceIndex(1, true);
      }
    }

    assertThat(firstHit).isPositive();
    int first = firstHit;
    // The row's two targets on the first hit, then the entry's one on every later hit.
    assertThat(hit.stream().filter(h -> h.tick() == first)).hasSize(2);
    List<Integer> later = hit.stream().map(Hit::tick).filter(t -> t > first).toList();
    assertThat(later).isNotEmpty().doesNotHaveDuplicates();
  }

  @Test
  @DisplayName(
      "an entry's custom attack action runs in place of the row's after each hit it selects, in the"
          + " pending pass of the hit's tick")
  void anEntryRunsItsOwnAttackAction(@TempDir Path folder) throws IOException {
    // A variable's write, which the hit schedules on the wizard itself.
    GameTables tables =
        wizard(
            folder,
            entry -> {},
            entry -> entry.put("CustomOnAttackAction", "MiniPekkaHero_set_ability_played"));
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, true);
    CharacterEntity wizard =
        battle.deploy(0, unit(battle, "ElectroWizard"), LEVEL, 0, 3500, 12000, "w");
    List<CharacterEntity> knights = knights(battle);
    List<Hit> hit = hits(battle, knights);
    int key = battle.getWorld().declaredVariable("MiniPekkaHero_ability_played");
    List<Integer> valueAfter = new ArrayList<>();
    int firstHit = -1;
    while (battle.getBattle().getTick() <= 300) {
      battle.getBattle().step();
      valueAfter.add(wizard.variable(key));
      if (firstHit < 0 && !hit.isEmpty()) {
        firstHit = hit.get(0).tick();
        wizard.setAttackSequenceIndex(1, true);
      }
    }

    assertThat(firstHit).isPositive();
    int first = firstHit;
    int second = hit.stream().mapToInt(Hit::tick).filter(t -> t > first).min().orElseThrow();
    // The first hit, at index 0, runs no action; the second, at index 1, the entry's.
    assertThat(valueAfter.subList(0, second)).containsOnly(0);
    assertThat(valueAfter.get(second)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "an entry's attack start delay holds the attack timer at 0 for its length, in steps of 50,"
          + " from the attack's start")
  void anEntryDelaysTheFirstHit(@TempDir Path folder) throws IOException {
    Path plainFolder = Files.createDirectories(folder.resolve("plain"));
    Path delayedFolder = Files.createDirectories(folder.resolve("delayed"));
    int plain = firstHitTick(wizard(plainFolder, entry -> {}, entry -> {}));
    int delayed =
        firstHitTick(wizard(delayedFolder, entry -> entry.put("AttackStartDelay", 500), e -> {}));

    assertThat(plain).isPositive();
    assertThat(delayed - plain).isEqualTo(10);
  }

  private static int firstHitTick(GameTables tables) {
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, true);
    battle.deploy(0, unit(battle, "ElectroWizard"), LEVEL, 0, 3500, 12000, "w");
    List<Hit> hit = hits(battle, knights(battle));
    while (hit.isEmpty() && battle.getBattle().getTick() <= 300) {
      battle.getBattle().step();
    }
    return hit.isEmpty() ? -1 : hit.get(0).tick();
  }

  @Test
  @DisplayName(
      "a hit whose entry remembers its targets keeps the ids of its reference and of each target it"
          + " reached, and a target that leaves the battle is dropped from them")
  void anEntryRemembersTheTargets(@TempDir Path folder) throws IOException {
    GameTables tables =
        wizard(folder, entry -> entry.put("CustomRememberMultipleTargets", 1), entry -> {});
    Standard1v1Battle battle = new Standard1v1Battle(tables, LEVEL, true);
    CharacterEntity wizard =
        battle.deploy(0, unit(battle, "ElectroWizard"), LEVEL, 0, 3500, 12000, "w");
    List<CharacterEntity> knights = knights(battle);
    List<Hit> hit = hits(battle, knights);
    while (hit.isEmpty() && battle.getBattle().getTick() <= 300) {
      battle.getBattle().step();
    }

    assertThat(hit).hasSize(2);
    WorldEntity reference = hit.get(0).target();
    WorldEntity other = hit.get(1).target();
    assertThat(wizard.getTargeting().getRememberedTargetIds())
        .containsExactly(reference.getId(), other.getId());

    other.killBy(null);
    stepThrough(battle, battle.getBattle().getTick() + 1);
    assertThat(battle.getWorld().present()).doesNotContain(other);
    assertThat(wizard.getTargeting().getRememberedTargetIds()).doesNotContain(other.getId());
  }
}
