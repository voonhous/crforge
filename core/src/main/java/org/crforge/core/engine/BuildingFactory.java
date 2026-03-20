package org.crforge.core.engine;

import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.VariableDamageAbility;
import org.crforge.core.card.Card;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.LiveSpawnConfig;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.ElixirCollectorComponent;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;

/** Creates Building entities and tunnel-dig troops with level-scaled stats. */
class BuildingFactory {

  private final GameState state;
  private final TroopFactory troopFactory;

  BuildingFactory(GameState state, TroopFactory troopFactory) {
    this.state = state;
    this.troopFactory = troopFactory;
  }

  void spawnBuilding(Team team, Card card, float x, float y, int level) {
    TroopStats unitStats = card.getUnitStats();
    if (unitStats == null) {
      return;
    }

    int scaledBuildingHp = LevelScaling.scaleCard(unitStats.getHealth(), level);
    int scaledBuildingDamage = LevelScaling.scaleCard(unitStats.getDamage(), level);

    Combat combat = null;
    if (unitStats.getDamage() > 0) {
      float initialLoad = unitStats.isNoPreload() ? 0f : unitStats.getLoadTime();
      combat =
          EntityScaling.buildCombatComponent(unitStats, scaledBuildingDamage, initialLoad).build();
    }

    // Create Spawner Component if needed
    SpawnerComponent spawner = null;
    boolean hasLiveSpawn = unitStats.getLiveSpawn() != null;
    boolean hasUnitLevelDeath = EntityScaling.hasDeathMechanics(unitStats);

    if (hasLiveSpawn || hasUnitLevelDeath) {
      LiveSpawnConfig ls = hasLiveSpawn ? unitStats.getLiveSpawn() : null;
      TroopStats spawnStats = card.getSpawnTemplate();
      int scaledDeathDmg = LevelScaling.scaleCard(unitStats.getDeathDamage(), level);

      ProjectileStats deathProjStats =
          EntityScaling.scaleDeathProjectile(unitStats.getDeathSpawnProjectile(), level);

      SpawnerComponent.SpawnerComponentBuilder spawnerBuilder =
          SpawnerComponent.builder()
              .deathDamage(scaledDeathDmg)
              .deathDamageRadius(unitStats.getDeathDamageRadius())
              .deathSpawns(unitStats.getDeathSpawns())
              .deathAreaEffect(unitStats.getDeathAreaEffect())
              .manaOnDeathForOpponent(unitStats.getManaOnDeathForOpponent())
              .deathSpawnProjectile(deathProjStats)
              .level(level);

      if (ls != null) {
        float initialTimer = EntityScaling.resolveInitialSpawnerTimer(ls);
        spawnerBuilder
            .spawnInterval(ls.spawnInterval())
            .spawnPauseTime(ls.spawnPauseTime())
            .unitsPerWave(ls.spawnNumber())
            .spawnStartTime(ls.spawnStartTime())
            .currentTimer(initialTimer)
            .spawnStats(spawnStats)
            .formationRadius(ls.spawnRadius())
            .spawnLimit(ls.spawnLimit())
            .destroyAtLimit(ls.destroyAtLimit())
            .spawnOnAggro(ls.spawnOnAggro())
            .aggroDetectionRange(ls.spawnOnAggro() ? unitStats.getRange() : 0f);

        // Derive deathSpawnCount from first death spawn entry
        if (unitStats.getDeathSpawns() != null && !unitStats.getDeathSpawns().isEmpty()) {
          spawnerBuilder.deathSpawnCount(unitStats.getDeathSpawns().get(0).count());
        }
      }

      spawner = spawnerBuilder.build();
    }

    // Create ability component if unit has one (e.g. Inferno Tower with VARIABLE_DAMAGE)
    AbilityComponent abilityComponent =
        unitStats.getAbility() != null ? new AbilityComponent(unitStats.getAbility()) : null;

    // Scale VARIABLE_DAMAGE stage damages by card level (e.g. Inferno Tower)
    if (abilityComponent != null
        && abilityComponent.getData() instanceof VariableDamageAbility varDmg) {
      abilityComponent.setScaledVariableDamageStageDamages(
          varDmg.stages().stream().map(s -> LevelScaling.scaleCard(s.damage(), level)).toList());
    }

    // Create ElixirCollectorComponent if this building generates elixir
    ElixirCollectorComponent elixirCollector = null;
    if (unitStats.getManaGenerateTime() > 0) {
      elixirCollector =
          ElixirCollectorComponent.builder()
              .manaCollectAmount(unitStats.getManaCollectAmount())
              .manaGenerateTime(unitStats.getManaGenerateTime())
              .manaOnDeath(unitStats.getManaOnDeath())
              .collectionTimer(unitStats.getManaGenerateTime())
              .build();
    }

    // Always use Building class, attach components optionally
    Building building =
        Building.builder()
            .name(card.getName())
            .team(team)
            .position(new Position(x, y))
            .health(new Health(scaledBuildingHp))
            .movement(
                new Movement(
                    0,
                    0,
                    unitStats.getCollisionRadius(),
                    unitStats.getVisualRadius(),
                    MovementType.BUILDING))
            .combat(combat)
            .ability(abilityComponent)
            .elixirCollector(elixirCollector)
            .lifetime(unitStats.getLifeTime())
            .remainingLifetime(unitStats.getLifeTime())
            .spawner(spawner)
            .deployTime(unitStats.getDeployTime())
            .deployTimer(unitStats.getDeployTime() + unitStats.getDeployDelay())
            .level(level)
            .build();

    state.spawnEntity(building);
  }

  /**
   * Spawns a tunnel dig troop for a building card that deploys via underground travel (e.g.
   * GoblinDrill -> GoblinDrillDig). The dig troop tunnels from the king tower to the target, then
   * morphs into the building on arrival.
   */
  void spawnTunnelBuilding(Team team, Card card, float x, float y, int level) {
    TroopStats digStats = card.getTunnelDigUnit();
    if (digStats == null) {
      return;
    }

    // Create the dig troop (single unit, no formation)
    Troop digTroop = troopFactory.createTroop(team, digStats, x, y, null, level, 0, 1, 0f, null);

    // Set up tunnel travel from king tower to target
    troopFactory.initializeTunnel(digTroop, x, y);

    // Carry the original card and level so the building can be created on morph
    digTroop.setMorphCard(card);
    digTroop.setMorphLevel(level);

    state.spawnEntity(digTroop);
  }
}
