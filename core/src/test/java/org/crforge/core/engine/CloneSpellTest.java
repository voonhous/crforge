package org.crforge.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.DeathSpawnEntry;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.testing.BuildingTemplate;
import org.crforge.core.testing.SimHarness;
import org.crforge.core.testing.SimSystems;
import org.crforge.core.testing.TroopTemplate;
import org.junit.jupiter.api.Test;

/** Tests for the Clone spell's area effect mechanic. */
class CloneSpellTest {

  private static final float CLONE_RADIUS = 3.0f;

  /** Builds a Clone spell area effect stats. */
  private AreaEffectStats cloneStats() {
    return AreaEffectStats.builder()
        .name("Clone")
        .radius(CLONE_RADIUS)
        .lifeDuration(1.0f)
        .hitsGround(true)
        .hitsAir(true)
        .clone(true)
        .onlyOwnTroops(true)
        .ignoreBuildings(true)
        .build();
  }

  /** Creates a Clone area effect entity at the given position for the given team. */
  private AreaEffect createCloneEffect(Team team, float x, float y) {
    AreaEffectStats stats = cloneStats();
    return AreaEffect.builder()
        .name("Clone")
        .team(team)
        .position(new Position(x, y))
        .stats(stats)
        .remainingLifetime(stats.getLifeDuration())
        .build();
  }

  /**
   * Casts clone at position and flushes pending spawns. Clone entities are added to pendingSpawns
   * during applyClone(), so we need processPending() after the tick to make them visible.
   */
  private void castCloneAndFlush(SimHarness sim, Team team, float x, float y) {
    sim.gameState().spawnEntity(createCloneEffect(team, x, y));
    sim.gameState().processPending();
    sim.tick();
    sim.gameState().processPending();
  }

  @Test
  void clone_createsDuplicateWith1Hp() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.AREA_EFFECT)
            .spawn(TroopTemplate.melee("Knight", Team.BLUE).hp(1000).at(10, 10))
            .deployed()
            .build();

    castCloneAndFlush(sim, Team.BLUE, 10, 10);

    List<Troop> knights =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop && e.getName().equals("Knight"))
            .map(e -> (Troop) e)
            .toList();

    assertThat(knights).hasSize(2);

    Troop clone = knights.stream().filter(Troop::isClone).findFirst().orElse(null);
    assertThat(clone).isNotNull();
    assertThat(clone.getHealth().getMax()).isEqualTo(1);
    assertThat(clone.getHealth().getCurrent()).isEqualTo(1);
    assertThat(clone.isClone()).isTrue();

    Troop original = knights.stream().filter(t -> !t.isClone()).findFirst().orElse(null);
    assertThat(original).isNotNull();
    assertThat(original.getHealth().getCurrent()).isEqualTo(1000);
  }

  @Test
  void clone_ignoresBuildings() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.AREA_EFFECT)
            .spawn(BuildingTemplate.defense("Cannon", Team.BLUE).at(10, 10).hp(500))
            .deployed()
            .build();

    castCloneAndFlush(sim, Team.BLUE, 10, 10);

    long troopCount =
        sim.gameState().getEntities().stream().filter(e -> e instanceof Troop).count();
    assertThat(troopCount).isZero();
  }

  @Test
  void clone_ignoresEnemyTroops() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.AREA_EFFECT)
            .spawn(TroopTemplate.melee("EnemyKnight", Team.RED).hp(500).at(10, 10))
            .deployed()
            .build();

    castCloneAndFlush(sim, Team.BLUE, 10, 10);

    List<Troop> troops =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop)
            .map(e -> (Troop) e)
            .toList();
    assertThat(troops).hasSize(1);
    assertThat(troops.get(0).isClone()).isFalse();
  }

  @Test
  void clone_ignoresExistingClones() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.AREA_EFFECT)
            .spawn(TroopTemplate.melee("Knight", Team.BLUE).hp(1000).at(10, 10))
            .deployed()
            .build();

    // First Clone spell
    castCloneAndFlush(sim, Team.BLUE, 10, 10);

    List<Troop> knightsAfterFirst =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop && e.getName().equals("Knight"))
            .map(e -> (Troop) e)
            .toList();
    assertThat(knightsAfterFirst).hasSize(2);

    // Second Clone spell -- should clone the original again, but NOT the existing clone
    castCloneAndFlush(sim, Team.BLUE, 10, 10);

    List<Troop> knightsAfterSecond =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop && e.getName().equals("Knight"))
            .map(e -> (Troop) e)
            .toList();

    // 1 original + 1 clone from first cast + 1 clone from second cast = 3
    assertThat(knightsAfterSecond).hasSize(3);
    long cloneCount = knightsAfterSecond.stream().filter(Troop::isClone).count();
    assertThat(cloneCount).isEqualTo(2);
  }

  @Test
  void clone_preservesShieldAt1Hp() {
    Troop shieldTroop =
        Troop.builder()
            .name("DarkPrince")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .health(new Health(800, 200))
            .movement(new Movement(1.0f, 4f, 0.5f, 0.5f, MovementType.GROUND))
            .combat(Combat.builder().damage(100).range(1.5f).build())
            .deployTime(0f)
            .deployTimer(0f)
            .build();

    SimHarness sim =
        SimHarness.create().withSystems(SimSystems.AREA_EFFECT).spawn(shieldTroop).build();

    // Fast-forward deploy
    sim.gameState()
        .getAliveEntities()
        .forEach(
            e -> {
              if (e instanceof Troop t) t.setDeployTimer(0);
              else if (e instanceof Building b) b.setDeployTimer(0);
            });

    castCloneAndFlush(sim, Team.BLUE, 10, 10);

    Troop clone =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop t && t.isClone())
            .map(e -> (Troop) e)
            .findFirst()
            .orElse(null);

    assertThat(clone).isNotNull();
    assertThat(clone.getHealth().getMax()).isEqualTo(1);
    assertThat(clone.getHealth().getCurrent()).isEqualTo(1);
    assertThat(clone.getHealth().getShield()).isEqualTo(1);
  }

  @Test
  void clone_displacesOriginalForward() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.AREA_EFFECT)
            .spawn(TroopTemplate.melee("Knight", Team.BLUE).hp(500).at(10, 10))
            .deployed()
            .build();

    castCloneAndFlush(sim, Team.BLUE, 10, 10);

    // Original should have been displaced forward (+y for BLUE)
    Troop original =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop t && e.getName().equals("Knight") && !t.isClone())
            .map(e -> (Troop) e)
            .findFirst()
            .orElse(null);

    assertThat(original).isNotNull();
    assertThat(original.getPosition().getY()).isGreaterThan(10f);

    // Clone should be at the original's pre-displacement position
    Troop clone =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop t && t.isClone())
            .map(e -> (Troop) e)
            .findFirst()
            .orElse(null);

    assertThat(clone).isNotNull();
    assertThat(clone.getPosition().getY()).isEqualTo(10f);
  }

  @Test
  void clone_doesNotInheritBuffs() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.AREA_EFFECT, SimSystems.STATUS_EFFECTS)
            .spawn(TroopTemplate.melee("Knight", Team.BLUE).hp(500).at(10, 10))
            .deployed()
            .build();

    // Apply Rage buff to the original troop
    Troop original = sim.troop("Knight");
    original.addEffect(new AppliedEffect(StatusEffectType.RAGE, 5.0f, "Rage"));
    assertThat(original.getAppliedEffects()).isNotEmpty();

    castCloneAndFlush(sim, Team.BLUE, 10, 10);

    Troop clone =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop t && t.isClone())
            .map(e -> (Troop) e)
            .findFirst()
            .orElse(null);

    assertThat(clone).isNotNull();
    assertThat(clone.getAppliedEffects()).isEmpty();
  }

  @Test
  void clone_deathSpawnsProduceClonedOffspring() {
    TroopStats golemiteStats =
        TroopStats.builder()
            .name("Golemite")
            .health(500)
            .damage(50)
            .speed(0.5f)
            .mass(6f)
            .collisionRadius(0.4f)
            .visualRadius(0.4f)
            .build();

    DeathSpawnEntry deathSpawn = new DeathSpawnEntry(golemiteStats, 2, 0.5f, 0f, 0f, null, null);

    SpawnerComponent spawner = SpawnerComponent.builder().deathSpawns(List.of(deathSpawn)).build();

    Troop golem =
        Troop.builder()
            .name("Golem")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .health(new Health(2000))
            .movement(new Movement(0.5f, 10f, 0.8f, 0.8f, MovementType.GROUND))
            .combat(Combat.builder().damage(200).range(1.5f).build())
            .deployTime(0f)
            .deployTimer(0f)
            .spawner(spawner)
            .build();

    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.AREA_EFFECT, SimSystems.SPAWNER)
            .spawn(golem)
            .build();

    sim.gameState()
        .getAliveEntities()
        .forEach(
            e -> {
              if (e instanceof Troop t) t.setDeployTimer(0);
              else if (e instanceof Building b) b.setDeployTimer(0);
            });

    // Clone the Golem
    castCloneAndFlush(sim, Team.BLUE, 10, 10);

    Troop clonedGolem =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop t && t.isClone() && e.getName().equals("Golem"))
            .map(e -> (Troop) e)
            .findFirst()
            .orElse(null);

    assertThat(clonedGolem).isNotNull();
    assertThat(clonedGolem.getHealth().getMax()).isEqualTo(1);

    // Kill the cloned Golem to trigger death spawns
    clonedGolem.getHealth().takeDamage(1);
    sim.gameState().processDeaths();
    sim.gameState().processPending();

    List<Troop> golemites =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop && e.getName().equals("Golemite"))
            .map(e -> (Troop) e)
            .toList();

    assertThat(golemites).hasSize(2);
    for (Troop golemite : golemites) {
      assertThat(golemite.isClone()).isTrue();
      assertThat(golemite.getHealth().getMax()).isEqualTo(1);
      assertThat(golemite.getHealth().getCurrent()).isEqualTo(1);
    }
  }

  @Test
  void clone_liveSpawnsProduceClonedOffspring() {
    TroopStats skeletonStats =
        TroopStats.builder()
            .name("Skeleton")
            .health(100)
            .damage(30)
            .speed(1.0f)
            .mass(1f)
            .collisionRadius(0.3f)
            .visualRadius(0.3f)
            .build();

    SpawnerComponent spawner =
        SpawnerComponent.builder()
            .spawnPauseTime(0.5f)
            .spawnInterval(0.1f)
            .unitsPerWave(1)
            .spawnStartTime(0.1f)
            .currentTimer(0.1f)
            .spawnStats(skeletonStats)
            .build();

    Troop witch =
        Troop.builder()
            .name("Witch")
            .team(Team.BLUE)
            .position(new Position(10, 10))
            .health(new Health(800))
            .movement(new Movement(1.0f, 4f, 0.5f, 0.5f, MovementType.GROUND))
            .combat(Combat.builder().damage(100).range(5.0f).build())
            .deployTime(0f)
            .deployTimer(0f)
            .spawner(spawner)
            .build();

    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.AREA_EFFECT, SimSystems.SPAWNER)
            .spawn(witch)
            .build();

    sim.gameState()
        .getAliveEntities()
        .forEach(
            e -> {
              if (e instanceof Troop t) t.setDeployTimer(0);
              else if (e instanceof Building b) b.setDeployTimer(0);
            });

    // Clone the Witch
    castCloneAndFlush(sim, Team.BLUE, 10, 10);

    Troop clonedWitch =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop t && t.isClone() && e.getName().equals("Witch"))
            .map(e -> (Troop) e)
            .findFirst()
            .orElse(null);

    assertThat(clonedWitch).isNotNull();
    assertThat(clonedWitch.getSpawner()).isNotNull();
    assertThat(clonedWitch.getSpawner().hasLiveSpawn()).isTrue();

    // Tick until the cloned Witch spawns a Skeleton
    for (int i = 0; i < 10; i++) {
      sim.tick();
    }

    // Both the original Witch and the cloned Witch may spawn Skeletons.
    // Only the cloned Witch's children should be clones with 1 HP.
    List<Troop> allSkeletons =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop && e.getName().equals("Skeleton"))
            .map(e -> (Troop) e)
            .toList();

    assertThat(allSkeletons).isNotEmpty();

    List<Troop> cloneSkeletons = allSkeletons.stream().filter(Troop::isClone).toList();
    List<Troop> normalSkeletons = allSkeletons.stream().filter(t -> !t.isClone()).toList();

    // There should be clone Skeletons from the cloned Witch
    assertThat(cloneSkeletons).isNotEmpty();
    for (Troop skeleton : cloneSkeletons) {
      assertThat(skeleton.getHealth().getMax()).isEqualTo(1);
    }

    // And normal Skeletons from the original Witch
    assertThat(normalSkeletons).isNotEmpty();
    for (Troop skeleton : normalSkeletons) {
      assertThat(skeleton.getHealth().getMax()).isGreaterThan(1);
    }
  }

  @Test
  void clone_combatStatsPreserved() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.AREA_EFFECT)
            .spawn(
                TroopTemplate.melee("Knight", Team.BLUE)
                    .hp(1000)
                    .damage(200)
                    .range(1.5f)
                    .cooldown(1.2f)
                    .at(10, 10))
            .deployed()
            .build();

    castCloneAndFlush(sim, Team.BLUE, 10, 10);

    Troop clone =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop t && t.isClone())
            .map(e -> (Troop) e)
            .findFirst()
            .orElse(null);

    assertThat(clone).isNotNull();
    assertThat(clone.getCombat()).isNotNull();
    assertThat(clone.getCombat().getDamage()).isEqualTo(200);
    assertThat(clone.getCombat().getRange()).isEqualTo(1.5f);
    assertThat(clone.getCombat().getAttackCooldown()).isEqualTo(1.2f);
  }

  @Test
  void clone_instantDeploy() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.AREA_EFFECT)
            .spawn(TroopTemplate.melee("Knight", Team.BLUE).hp(500).at(10, 10))
            .deployed()
            .build();

    castCloneAndFlush(sim, Team.BLUE, 10, 10);

    Troop clone =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop t && t.isClone())
            .map(e -> (Troop) e)
            .findFirst()
            .orElse(null);

    assertThat(clone).isNotNull();
    assertThat(clone.getDeployTime()).isEqualTo(0f);
    assertThat(clone.getDeployTimer()).isEqualTo(0f);
  }

  @Test
  void clone_multipleClonesFromOneSpell() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.AREA_EFFECT)
            .spawn(TroopTemplate.melee("Musketeer1", Team.BLUE).hp(400).at(9, 10))
            .spawn(TroopTemplate.melee("Musketeer2", Team.BLUE).hp(400).at(10, 10))
            .spawn(TroopTemplate.melee("Musketeer3", Team.BLUE).hp(400).at(11, 10))
            .deployed()
            .build();

    castCloneAndFlush(sim, Team.BLUE, 10, 10);

    long troopCount =
        sim.gameState().getEntities().stream().filter(e -> e instanceof Troop).count();
    assertThat(troopCount).isEqualTo(6);

    long cloneCount =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop t && t.isClone())
            .count();
    assertThat(cloneCount).isEqualTo(3);
  }

  @Test
  void clone_outsideRadiusNotCloned() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.AREA_EFFECT)
            .spawn(TroopTemplate.melee("Near", Team.BLUE).hp(500).at(10, 10))
            .spawn(TroopTemplate.melee("Far", Team.BLUE).hp(500).at(20, 20))
            .deployed()
            .build();

    castCloneAndFlush(sim, Team.BLUE, 10, 10);

    long cloneCount =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop t && t.isClone())
            .count();
    assertThat(cloneCount).isEqualTo(1);

    Troop clone =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop t && t.isClone())
            .map(e -> (Troop) e)
            .findFirst()
            .orElse(null);

    assertThat(clone).isNotNull();
    assertThat(clone.getName()).isEqualTo("Near");
  }

  @Test
  void clone_redTeamDisplacesDownward() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.AREA_EFFECT)
            .spawn(TroopTemplate.melee("Knight", Team.RED).hp(500).at(10, 20))
            .deployed()
            .build();

    castCloneAndFlush(sim, Team.RED, 10, 20);

    Troop original =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop t && e.getName().equals("Knight") && !t.isClone())
            .map(e -> (Troop) e)
            .findFirst()
            .orElse(null);

    assertThat(original).isNotNull();
    assertThat(original.getPosition().getY()).isLessThan(20f);
  }

  @Test
  void clone_airTroopsCanBeCloned() {
    SimHarness sim =
        SimHarness.create()
            .withSystems(SimSystems.AREA_EFFECT)
            .spawn(TroopTemplate.air("BabyDragon", Team.BLUE).hp(800).at(10, 10))
            .deployed()
            .build();

    castCloneAndFlush(sim, Team.BLUE, 10, 10);

    long cloneCount =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop t && t.isClone())
            .count();
    assertThat(cloneCount).isEqualTo(1);

    Troop clone =
        sim.gameState().getEntities().stream()
            .filter(e -> e instanceof Troop t && t.isClone())
            .map(e -> (Troop) e)
            .findFirst()
            .orElse(null);

    assertThat(clone).isNotNull();
    assertThat(clone.getMovement().getType()).isEqualTo(MovementType.AIR);
  }
}
