package org.crforge.core.testing;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.crforge.core.ability.AbilitySystem;
import org.crforge.core.arena.Arena;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.combat.ProjectileSystem;
import org.crforge.core.combat.TargetingSystem;
import org.crforge.core.component.Combat;
import org.crforge.core.effect.StatusEffectSystem;
import org.crforge.core.engine.EntityTimerSystem;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.DeathHandlingSystem;
import org.crforge.core.entity.SpawnFactory;
import org.crforge.core.entity.SpawnerSystem;
import org.crforge.core.entity.base.AbstractEntity;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.effect.AreaEffectSystem;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.physics.PhysicsSystem;

/**
 * Fluent test harness for the simulation. Handles system wiring, entity creation, and tick
 * execution in the canonical GameEngine order. Eliminates boilerplate from test classes.
 *
 * <p>Usage:
 *
 * <pre>
 * SimHarness sim = SimHarness.create()
 *     .withAllSystems()
 *     .spawn(TroopTemplate.melee("Knight", Team.BLUE).at(5, 5))
 *     .spawn(TroopTemplate.target("Dummy", Team.RED).at(10, 5))
 *     .deployed()
 *     .build();
 *
 * sim.tick(60);
 * Troop knight = sim.troop("Knight");
 * </pre>
 */
public class SimHarness {

  private static final float DT = 1.0f / 30;

  private final GameState gameState;
  private final EnumSet<SimSystems> enabledSystems;

  // Systems (null if not enabled)
  private final AoeDamageService aoeDamageService;
  private final StatusEffectSystem statusEffectSystem;
  private final TargetingSystem targetingSystem;
  private final AbilitySystem abilitySystem;
  private final ProjectileSystem projectileSystem;
  private final CombatSystem combatSystem;
  private final PhysicsSystem physicsSystem;
  private final SpawnerSystem spawnerSystem;
  private final DeathHandlingSystem deathHandlingSystem;
  private final AreaEffectSystem areaEffectSystem;
  private final EntityTimerSystem entityTimerSystem;

  private SimHarness(Builder builder) {
    this.gameState = builder.gameState;
    this.enabledSystems = builder.enabledSystems;

    // AoeDamageService is always created (lightweight, no side effects)
    this.aoeDamageService = new AoeDamageService(gameState);

    // Initialize enabled systems
    this.statusEffectSystem =
        enabledSystems.contains(SimSystems.STATUS_EFFECTS) ? new StatusEffectSystem() : null;

    this.targetingSystem =
        enabledSystems.contains(SimSystems.TARGETING) ? new TargetingSystem() : null;

    this.projectileSystem =
        enabledSystems.contains(SimSystems.COMBAT)
            ? new ProjectileSystem(gameState, aoeDamageService)
            : null;

    this.combatSystem =
        enabledSystems.contains(SimSystems.COMBAT)
            ? new CombatSystem(gameState, aoeDamageService, projectileSystem)
            : null;

    this.abilitySystem =
        enabledSystems.contains(SimSystems.ABILITY) ? new AbilitySystem(gameState) : null;

    Arena arena = new Arena("Test Arena");
    this.physicsSystem =
        enabledSystems.contains(SimSystems.PHYSICS) ? new PhysicsSystem(arena) : null;

    SpawnFactory spawnFactory = new SpawnFactory(gameState);
    this.spawnerSystem =
        enabledSystems.contains(SimSystems.SPAWNER)
            ? new SpawnerSystem(gameState, spawnFactory)
            : null;

    this.deathHandlingSystem =
        enabledSystems.contains(SimSystems.SPAWNER)
            ? new DeathHandlingSystem(gameState, aoeDamageService, spawnFactory)
            : null;

    this.areaEffectSystem =
        enabledSystems.contains(SimSystems.AREA_EFFECT) ? new AreaEffectSystem(gameState) : null;

    this.entityTimerSystem = new EntityTimerSystem();

    // Wire callbacks if both systems exist
    if (projectileSystem != null && spawnerSystem != null) {
      projectileSystem.setUnitSpawner(spawnerSystem::spawnUnit);
    }
    if (areaEffectSystem != null && spawnerSystem != null) {
      areaEffectSystem.setUnitSpawner(spawnerSystem::spawnUnit);
    }
    if (deathHandlingSystem != null) {
      gameState.setDeathHandler(deathHandlingSystem::onDeath);
    }

    // Spawn entities and process pending
    for (Entity entity : builder.entities) {
      gameState.spawnEntity(entity);
    }
    gameState.processPending();

    // Fast-forward deploy timers if requested
    if (builder.deployed) {
      for (Entity entity : gameState.getEntities()) {
        if (entity instanceof Troop troop) {
          troop.setDeployTimer(0);
        } else if (entity instanceof Building building) {
          building.setDeployTimer(0);
        }
      }
    }
  }

  /** Creates a new builder. Resets ID counters automatically. */
  public static Builder create() {
    AbstractEntity.resetIdCounter();
    Projectile.resetIdCounter();
    return new Builder();
  }

  // -- Tick execution --

  /**
   * Runs a single tick in the canonical GameEngine order: StatusEffects -> entity.update ->
   * AreaEffects -> Targeting -> Ability -> Combat -> Physics -> Deaths
   */
  public void tick() {
    gameState.processPending();

    // 1. Status effects
    if (statusEffectSystem != null) {
      statusEffectSystem.update(gameState, DT);
    }

    // 2. Entity timers (deploy, lifetime, grounded) -- CES: logic in system
    entityTimerSystem.update(gameState.getAliveEntities(), DT);

    // 2.5 Combat timers (cooldown, windup, load time) -- always ticked regardless of
    // whether COMBAT system is enabled, matching old entity.update() behavior
    if (combatSystem == null) {
      for (Entity entity : gameState.getAliveEntities()) {
        Combat combat = entity.getCombat();
        if (combat != null && entity.isAlive()) {
          combat.update(DT, true);
        }
      }
    }

    // 3. Area effects
    if (areaEffectSystem != null) {
      areaEffectSystem.update(DT);
    }

    // 4. Targeting
    if (targetingSystem != null) {
      targetingSystem.updateTargets(gameState.getAliveEntities());
    }

    // 5. Spawner (live spawning only)
    if (spawnerSystem != null) {
      spawnerSystem.update(DT);
    }

    // 5.5 Process pending delayed death spawns
    if (deathHandlingSystem != null) {
      deathHandlingSystem.processDelayedSpawns(DT);
    }

    // 6. Abilities
    if (abilitySystem != null) {
      abilitySystem.update(DT);
    }

    // 7. Combat
    if (combatSystem != null) {
      combatSystem.update(DT);
    }

    // 8. Physics
    if (physicsSystem != null) {
      physicsSystem.update(gameState.getAliveEntities(), DT);
    }

    // 9. Deaths
    gameState.processDeaths();

    gameState.incrementFrame();
  }

  /** Run n ticks. */
  public void tick(int n) {
    for (int i = 0; i < n; i++) {
      tick();
    }
  }

  /** Run for the given number of seconds (converted to ticks at 30fps). */
  public void tickSeconds(float seconds) {
    tick((int) (seconds * 30));
  }

  // -- Entity retrieval --

  /** Find the first troop with the given name. */
  public Troop troop(String name) {
    return gameState.getEntities().stream()
        .filter(e -> e instanceof Troop && e.getName().equals(name))
        .map(e -> (Troop) e)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No troop named: " + name));
  }

  /** Find the first building with the given name. */
  public Building building(String name) {
    return gameState.getEntities().stream()
        .filter(e -> e instanceof Building && e.getName().equals(name))
        .map(e -> (Building) e)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No building named: " + name));
  }

  /** Find the first entity with the given name. */
  public Entity entity(String name) {
    return gameState.getEntities().stream()
        .filter(e -> e.getName().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No entity named: " + name));
  }

  // -- System access --

  public GameState gameState() {
    return gameState;
  }

  public AoeDamageService aoeDamageService() {
    return aoeDamageService;
  }

  public ProjectileSystem projectileSystem() {
    return projectileSystem;
  }

  public CombatSystem combatSystem() {
    return combatSystem;
  }

  public TargetingSystem targetingSystem() {
    return targetingSystem;
  }

  public AbilitySystem abilitySystem() {
    return abilitySystem;
  }

  public StatusEffectSystem statusEffectSystem() {
    return statusEffectSystem;
  }

  public PhysicsSystem physicsSystem() {
    return physicsSystem;
  }

  public SpawnerSystem spawnerSystem() {
    return spawnerSystem;
  }

  public AreaEffectSystem areaEffectSystem() {
    return areaEffectSystem;
  }

  /** Returns the delta time constant (1/30). */
  public static float dt() {
    return DT;
  }

  // -- Builder --

  public static class Builder {

    private final GameState gameState = new GameState();
    private final List<Entity> entities = new ArrayList<>();
    private final EnumSet<SimSystems> enabledSystems = EnumSet.noneOf(SimSystems.class);
    private boolean deployed = false;

    /** Enable all systems (recommended default). */
    public Builder withAllSystems() {
      enabledSystems.addAll(EnumSet.allOf(SimSystems.class));
      return this;
    }

    /** Enable only the specified systems. */
    public Builder withSystems(SimSystems... systems) {
      for (SimSystems s : systems) {
        enabledSystems.add(s);
      }
      return this;
    }

    /** Spawn a troop from a template. */
    public Builder spawn(TroopTemplate template) {
      entities.add(template.build());
      return this;
    }

    /** Spawn a building from a template. */
    public Builder spawn(BuildingTemplate template) {
      entities.add(template.build());
      return this;
    }

    /** Spawn a pre-built entity directly. */
    public Builder spawn(Entity entity) {
      entities.add(entity);
      return this;
    }

    /** Fast-forward all deploy timers so entities are immediately active. */
    public Builder deployed() {
      this.deployed = true;
      return this;
    }

    /** Build and return the harness. */
    public SimHarness build() {
      return new SimHarness(this);
    }
  }
}
