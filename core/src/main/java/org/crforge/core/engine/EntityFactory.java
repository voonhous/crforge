package org.crforge.core.engine;

import java.util.List;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.BuffAllyAbility;
import org.crforge.core.ability.RangedAttackAbility;
import org.crforge.core.ability.TunnelAbility;
import org.crforge.core.arena.Arena;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.AttackSequenceHit;
import org.crforge.core.card.Card;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.LiveSpawnConfig;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.ScaledDamageTier;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.AoeDamageService;
import org.crforge.core.component.AttachedComponent;
import org.crforge.core.component.Combat;
import org.crforge.core.component.ElixirCollectorComponent;
import org.crforge.core.component.Health;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.effect.BuffDefinition;
import org.crforge.core.effect.BuffRegistry;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.util.FormationLayout;
import org.crforge.core.util.Vector2;

/**
 * Constructs game entities (Troops, Buildings, AreaEffects, Projectiles) with level-scaled stats.
 * Used exclusively by {@link DeploymentSystem} to separate entity construction from deployment
 * orchestration.
 */
class EntityFactory {

  private static final float SPELL_TRAVEL_DISTANCE = 10f;

  private final GameState state;
  private final AoeDamageService aoeDamageService;

  EntityFactory(GameState state, AoeDamageService aoeDamageService) {
    this.state = state;
    this.aoeDamageService = aoeDamageService;
  }

  // --- Package-private methods called by DeploymentSystem ---

  /**
   * Spawns a non-troop card (building or spell). Troop cards are handled directly by the stagger
   * loop in {@link DeploymentSystem#update(float)}.
   */
  void spawnCard(Team team, Card card, float x, float y, int level) {
    switch (card.getType()) {
      case BUILDING -> spawnBuilding(team, card, x, y, level);
      case SPELL -> castSpell(team, card, x, y, level);
      default -> {}
    }
  }

  /**
   * Spawns a single troop at the given index within the card's formation. Handles primary/secondary
   * unit selection, spawner components (idx==0 only), death mechanics, and formation offset
   * positioning.
   */
  void spawnSingleTroop(Team team, Card card, float x, float y, int level, int idx) {
    TroopStats primaryStats = card.getUnitStats();
    if (primaryStats == null) {
      return;
    }

    int primaryCount = card.getUnitCount();
    int totalUnits = card.getTotalDeployCount();
    TroopStats secondaryStats = card.getSecondaryUnitStats();
    List<float[]> formationOffsets = card.getFormationOffsets();
    float summonRadius = card.getSummonRadius();

    boolean isSecondary = idx >= primaryCount;
    TroopStats unitStats = isSecondary ? secondaryStats : primaryStats;
    if (unitStats == null) {
      return;
    }

    // Check for spawner capability (Witch/Mother Witch logic) -- only first primary unit
    // spawnAttach units (Ram Rider, Goblin Giant) are handled separately after spawn
    SpawnerComponent spawner = null;
    boolean isSpawnAttach =
        idx == 0 && unitStats.getLiveSpawn() != null && unitStats.getLiveSpawn().spawnAttach();

    if (idx == 0 && unitStats.getLiveSpawn() != null && !isSpawnAttach) {
      LiveSpawnConfig ls = unitStats.getLiveSpawn();
      TroopStats spawnStats = card.getSpawnTemplate();
      if (spawnStats != null) {
        float initialTimer = resolveInitialSpawnerTimer(ls);
        spawner =
            SpawnerComponent.builder()
                .spawnInterval(ls.spawnInterval())
                .spawnPauseTime(ls.spawnPauseTime())
                .unitsPerWave(ls.spawnNumber())
                .spawnStartTime(ls.spawnStartTime())
                .currentTimer(initialTimer)
                .spawnStats(spawnStats)
                .formationRadius(ls.spawnRadius())
                .spawnOnAggro(ls.spawnOnAggro())
                .aggroDetectionRange(ls.spawnOnAggro() ? unitStats.getRange() : 0f)
                .rarity(card.getRarity())
                .level(level)
                .build();
      }
    }

    // Unit-level death mechanics (e.g. Golem death damage + death spawn, RageBarbarianBottle death
    // area effect)
    if (hasDeathMechanics(unitStats)) {
      int scaledDeathDmg =
          LevelScaling.scaleCard(unitStats.getDeathDamage(), card.getRarity(), level);
      ProjectileStats deathProjStats =
          scaleDeathProjectile(unitStats.getDeathSpawnProjectile(), card.getRarity(), level);

      if (spawner == null) {
        // Create a death-only SpawnerComponent
        spawner =
            SpawnerComponent.builder()
                .deathDamage(scaledDeathDmg)
                .deathDamageRadius(unitStats.getDeathDamageRadius())
                .deathSpawns(unitStats.getDeathSpawns())
                .deathAreaEffect(unitStats.getDeathAreaEffect())
                .manaOnDeathForOpponent(unitStats.getManaOnDeathForOpponent())
                .deathSpawnProjectile(deathProjStats)
                .rarity(card.getRarity())
                .level(level)
                .build();
      } else {
        // Merge death mechanics into existing spawner
        spawner.setDeathDamage(scaledDeathDmg);
        spawner.setDeathDamageRadius(unitStats.getDeathDamageRadius());
        spawner.setDeathSpawns(unitStats.getDeathSpawns());
        spawner.setDeathAreaEffect(unitStats.getDeathAreaEffect());
        spawner.setManaOnDeathForOpponent(unitStats.getManaOnDeathForOpponent());
        spawner.setDeathSpawnProjectile(deathProjStats);
      }
    }

    Troop troop =
        createTroop(
            team,
            unitStats,
            x,
            y,
            spawner,
            level,
            card.getRarity(),
            idx,
            totalUnits,
            summonRadius,
            formationOffsets);

    // Tunnel ability: override spawn position to king tower and set up underground travel
    if (troop.getAbility() != null && troop.getAbility().getData() instanceof TunnelAbility) {
      initializeTunnel(troop, x, y);
    }

    state.spawnEntity(troop);

    // Spawn attached units (Ram Rider on Ram, Spear Goblins on Goblin Giant)
    if (isSpawnAttach) {
      spawnAttachedUnits(troop, card, level);
    }
  }

  void deployAreaEffect(
      Team team, AreaEffectStats stats, float x, float y, Rarity rarity, int level) {
    int scaledDamage =
        stats.getDamage() > 0 ? LevelScaling.scaleCard(stats.getDamage(), rarity, level) : 0;
    int resolvedCtdp = stats.getCrownTowerDamagePercent();
    int buildingDmgPct = 0;

    // Resolve values from BuffDefinition if the area effect has a buff
    BuffDefinition buffDef = BuffRegistry.get(stats.getBuff());
    if (buffDef != null) {
      // If area effect has no damage but buff defines DPS, compute base damage per tick
      if (scaledDamage == 0 && buffDef.getDamagePerSecond() > 0) {
        float hitSpeed = stats.getHitSpeed() > 0 ? stats.getHitSpeed() : 1.0f;
        int baseDamage = Math.round(buffDef.getDamagePerSecond() * hitSpeed);
        scaledDamage = LevelScaling.scaleCard(baseDamage, rarity, level);
      }

      // Resolve crown tower damage percent from buff if not set on stats
      if (resolvedCtdp == 0 && buffDef.getCrownTowerDamagePercent() != 0) {
        resolvedCtdp = buffDef.getCrownTowerDamagePercent();
      }

      // Resolve building damage percent from buff (e.g. Earthquake)
      if (buffDef.getBuildingDamagePercent() != 0) {
        buildingDmgPct = buffDef.getBuildingDamagePercent();
      }
    }

    // Resolve DOT scaling for targeted effects (Vines)
    int scaledDotDamage = 0;
    int scaledCrownTowerDotDamage = 0;
    if (stats.getTargetCount() > 0 && buffDef != null) {
      float hitFrequency = buffDef.getHitFrequency() > 0 ? buffDef.getHitFrequency() : 1.0f;
      int baseDotDamage = Math.round(buffDef.getDamagePerSecond() * hitFrequency);
      scaledDotDamage = LevelScaling.scaleCard(baseDotDamage, rarity, level);
      if (buffDef.getCrownTowerDamagePerHit() > 0) {
        scaledCrownTowerDotDamage =
            LevelScaling.scaleCard(buffDef.getCrownTowerDamagePerHit(), rarity, level);
      }
    }

    // Absolute CT damage for general ticking AEOs (GoblinCurse)
    if (stats.getTargetCount() == 0 && buffDef != null && buffDef.getCrownTowerDamagePerHit() > 0) {
      scaledCrownTowerDotDamage =
          LevelScaling.scaleCard(buffDef.getCrownTowerDamagePerHit(), rarity, level);
    }

    // Scale laser ball damage tiers (DarkMagic)
    // damageTier DPS and CT values are at Common L1 base, not the card's rarity base.
    // We must scale using COMMON rarity with the effective card level (clamped to rarity min).
    // IMPORTANT: scale damagePerSecond FIRST, then multiply by hitFrequency. This avoids
    // intermediate rounding errors that cause +/-1 deviations from expected values.
    List<ScaledDamageTier> scaledTiers = List.of();
    int totalLaserScans = 0;
    if (!stats.getDamageTiers().isEmpty()) {
      totalLaserScans = stats.computeTotalLaserScans();
      int effectiveLevel = Math.max(level, LevelScaling.getMinLevel(rarity));
      scaledTiers =
          stats.getDamageTiers().stream()
              .map(
                  tier -> {
                    // Scale raw DPS as Common L1 base, then convert to per-hit
                    int scaledDPS =
                        LevelScaling.scaleCard(
                            tier.damagePerSecond(), Rarity.COMMON, effectiveLevel);
                    int scaledDamagePerHit = (int) (scaledDPS * tier.hitFrequency());
                    int scaledCtPerHit =
                        tier.crownTowerDamagePerHit() > 0
                            ? LevelScaling.scaleCard(
                                tier.crownTowerDamagePerHit(), Rarity.COMMON, effectiveLevel)
                            : 0;
                    return new ScaledDamageTier(
                        scaledDamagePerHit, scaledCtPerHit, tier.maxTargets());
                  })
              .toList();
    }

    AreaEffect effect =
        AreaEffect.builder()
            .name(stats.getName())
            .team(team)
            .position(new Position(x, y))
            .stats(stats)
            .scaledDamage(scaledDamage)
            .resolvedCrownTowerDamagePercent(resolvedCtdp)
            .buildingDamagePercent(buildingDmgPct)
            .scaledDotDamage(scaledDotDamage)
            .scaledCrownTowerDotDamage(scaledCrownTowerDotDamage)
            .scaledDamageTiers(scaledTiers)
            .totalLaserScans(totalLaserScans)
            .remainingLifetime(stats.getLifeDuration())
            .rarity(rarity)
            .level(level)
            .build();

    state.spawnEntity(effect);
  }

  void fireSpawnProjectile(Team team, Card card, float x, float y, int level) {
    ProjectileStats stats = card.getSpawnProjectile();
    int damage = LevelScaling.scaleCard(stats.getDamage(), card.getRarity(), level);
    float startY = (team == Team.BLUE) ? y - SPELL_TRAVEL_DISTANCE : y + SPELL_TRAVEL_DISTANCE;
    Projectile p =
        new Projectile(
            team,
            x,
            startY,
            x,
            y,
            damage,
            stats.getRadius(),
            stats.getSpeed(),
            stats.getHitEffects(),
            stats.getCrownTowerDamagePercent());
    p.setPushback(stats.getPushback());
    p.setPushbackAll(stats.isPushbackAll());
    state.spawnProjectile(p);
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
    Troop digTroop =
        createTroop(team, digStats, x, y, null, level, card.getRarity(), 0, 1, 0f, null);

    // Set up tunnel travel from king tower to target
    initializeTunnel(digTroop, x, y);

    // Carry the original card and level so the building can be created on morph
    digTroop.setMorphCard(card);
    digTroop.setMorphLevel(level);

    state.spawnEntity(digTroop);
  }

  // --- Private methods ---

  /**
   * Spawns units that are permanently attached to a parent entity (e.g. Ram Rider on Ram, Spear
   * Goblins on Goblin Giant). Attached units ride on the parent, are non-targetable, invulnerable,
   * and die when the parent dies.
   */
  private void spawnAttachedUnits(Troop parent, Card card, int level) {
    LiveSpawnConfig ls = card.getUnitStats().getLiveSpawn();
    TroopStats spawnStats = card.getSpawnTemplate();
    if (ls == null || spawnStats == null) {
      return;
    }

    int count = ls.spawnNumber();
    float formationRadius = ls.spawnRadius();

    for (int i = 0; i < count; i++) {
      // Calculate formation offset for this attached unit
      Vector2 offset =
          FormationLayout.calculateOffset(
              i, count, formationRadius, spawnStats.getCollisionRadius());
      float offsetX = offset.getX();
      float offsetY = offset.getY();

      int scaledHp = LevelScaling.scaleCard(spawnStats.getHealth(), card.getRarity(), level);
      int scaledDamage = LevelScaling.scaleCard(spawnStats.getDamage(), card.getRarity(), level);

      float initialLoad = spawnStats.isNoPreload() ? 0f : spawnStats.getLoadTime();

      List<AttackSequenceHit> scaledAttachSequence =
          scaleAttackSequence(spawnStats.getAttackSequence(), card.getRarity(), level);

      Combat combat =
          buildCombatComponent(spawnStats, scaledDamage, initialLoad)
              .aoeRadius(spawnStats.getAoeRadius())
              .multipleTargets(spawnStats.getMultipleTargets())
              .multipleProjectiles(spawnStats.getMultipleProjectiles())
              .buffOnDamage(spawnStats.getBuffOnDamage())
              .attackSequence(scaledAttachSequence)
              .build();

      int scaledShield =
          spawnStats.getShieldHitpoints() > 0
              ? LevelScaling.scaleCard(spawnStats.getShieldHitpoints(), card.getRarity(), level)
              : 0;

      AttachedComponent attachedComponent = new AttachedComponent(parent, offsetX, offsetY);

      Troop child =
          Troop.builder()
              .name(spawnStats.getName())
              .team(parent.getTeam())
              .position(
                  new Position(
                      parent.getPosition().getX() + offsetX, parent.getPosition().getY() + offsetY))
              .health(new Health(scaledHp, scaledShield))
              .movement(
                  new Movement(
                      spawnStats.getSpeed(),
                      spawnStats.getMass(),
                      spawnStats.getCollisionRadius(),
                      spawnStats.getVisualRadius(),
                      spawnStats.getMovementType()))
              .combat(combat)
              .deployTime(0f)
              .deployTimer(0f)
              .attached(attachedComponent)
              .invulnerable(true)
              .level(level)
              .build();

      state.spawnEntity(child);
    }
  }

  /**
   * The Red player's view is rotated 180 degrees relative to the Blue player's view.
   *
   * <p>This means that a formation defined with offsets (dx, dy) for Blue (where +y is forward and
   * +x is right) should be applied as (-dx, -dy) for Red to preserve the formation's relative shape
   * and orientation towards the enemy.
   */
  private Troop createTroop(
      Team team,
      TroopStats stats,
      float baseX,
      float baseY,
      SpawnerComponent spawner,
      int level,
      Rarity rarity,
      int index,
      int total,
      float summonRadius,
      List<float[]> formationOffsets) {
    float offsetX = 0f;
    float offsetY = 0f;

    if (formationOffsets != null && index < formationOffsets.size()) {
      // Pre-computed offsets (already in tile units, no TILE_SCALE conversion needed)
      float[] offset = formationOffsets.get(index);
      offsetX = offset[0];
      offsetY = offset[1];
    } else if (total > 1 && summonRadius > 0) {
      // Fallback: circular formation algorithm
      Vector2 offset =
          FormationLayout.calculateDeployOffset(
              index, total, summonRadius, stats.getCollisionRadius());
      offsetX = offset.getX();
      offsetY = offset.getY();
    }

    if (team == Team.RED) {
      offsetX = -offsetX;
      offsetY = -offsetY;
    }

    float spawnX = baseX + offsetX;
    float spawnY = baseY + offsetY;

    // Troops enter the arena preloaded per RoyaleAPI secret stats:
    // https://royaleapi.com/blog/secret-stats
    // Exception: noPreload units (e.g. Sparky) start with 0 charge.
    float initialLoad = stats.isNoPreload() ? 0f : stats.getLoadTime();

    int scaledHp = LevelScaling.scaleCard(stats.getHealth(), rarity, level);
    int scaledDamage = LevelScaling.scaleCard(stats.getDamage(), rarity, level);

    List<AttackSequenceHit> scaledSequence =
        scaleAttackSequence(stats.getAttackSequence(), rarity, level);

    Combat combat =
        buildCombatComponent(stats, scaledDamage, initialLoad)
            .aoeRadius(stats.getAoeRadius())
            .multipleTargets(stats.getMultipleTargets())
            .multipleProjectiles(stats.getMultipleProjectiles())
            .buffOnDamage(stats.getBuffOnDamage())
            .attackSequence(scaledSequence)
            .build();

    // Scale shield by level
    int scaledShield =
        stats.getShieldHitpoints() > 0
            ? LevelScaling.scaleCard(stats.getShieldHitpoints(), rarity, level)
            : 0;

    // Create ability component if unit has one
    AbilityComponent abilityComponent =
        stats.getAbility() != null ? new AbilityComponent(stats.getAbility()) : null;

    // Scale BUFF_ALLY bonus damage by card level
    if (abilityComponent != null
        && abilityComponent.getData() instanceof BuffAllyAbility buffAlly) {
      abilityComponent.setScaledAddedDamage(
          LevelScaling.scaleCard(buffAlly.addedDamage(), rarity, level));
      abilityComponent.setScaledAddedCrownTowerDamage(
          LevelScaling.scaleCard(buffAlly.addedCrownTowerDamage(), rarity, level));
    }

    // Scale RANGED_ATTACK projectile damage by card level
    if (abilityComponent != null && abilityComponent.getData() instanceof RangedAttackAbility ra) {
      int baseDmg = ra.projectile() != null ? ra.projectile().getDamage() : 0;
      abilityComponent.setScaledRangedDamage(LevelScaling.scaleCard(baseDmg, rarity, level));
    }

    Movement movement =
        new Movement(
            stats.getSpeed(),
            stats.getMass(),
            stats.getCollisionRadius(),
            stats.getVisualRadius(),
            stats.getMovementType());
    movement.setIgnorePushback(stats.isIgnorePushback());
    movement.setJumpEnabled(stats.isJumpEnabled());
    movement.setHovering(stats.isHovering());

    // Explicitly set deployTimer to deployTime to avoid default value issues
    return Troop.builder()
        .name(stats.getName())
        .team(team)
        .position(new Position(spawnX, spawnY))
        .health(new Health(scaledHp, scaledShield))
        .movement(movement)
        .combat(combat)
        .deployTime(stats.getDeployTime())
        .deployTimer(stats.getDeployTime() + stats.getDeployDelay())
        .spawner(spawner)
        .ability(abilityComponent)
        .transformConfig(stats.getTransformConfig())
        .lifeTimer(stats.getLifeTime())
        .level(level)
        .build();
  }

  /**
   * Sets up the tunnel travel for a Miner-type troop. Overrides spawn position to the team's king
   * tower and computes a river dogleg waypoint if the target crosses the river.
   */
  private void initializeTunnel(Troop troop, float targetX, float targetY) {
    AbilityComponent ability = troop.getAbility();
    Team team = troop.getTeam();

    // Start at own king tower
    float startX = Arena.WIDTH / 2f; // 9.0
    float startY = team == Team.BLUE ? 3.0f : Arena.HEIGHT - 3.0f;
    troop.getPosition().set(startX, startY);

    // Store tunnel target
    ability.setTunnelTargetX(targetX);
    ability.setTunnelTargetY(targetY);

    // Compute river dogleg waypoint if target crosses the river
    boolean crossesRiver =
        (team == Team.BLUE && targetY > Arena.RIVER_Y - 1)
            || (team == Team.RED && targetY < Arena.RIVER_Y + 1);

    if (crossesRiver) {
      // Pick the bridge lane closer to the target's X position
      float waypointX = targetX < Arena.WIDTH / 2f ? 4.1f : 13.9f;
      float waypointY = team == Team.BLUE ? 15.0f : 17.0f;
      ability.setTunnelWaypointX(waypointX);
      ability.setTunnelWaypointY(waypointY);
      ability.setTunnelUsingWaypoint(true);
    }

    // Activate tunnel state
    ability.setTunnelState(AbilityComponent.TunnelState.TUNNELING);
    troop.setTunneling(true);
    troop.setInvulnerable(true);

    // Disable combat while tunneling
    if (troop.getCombat() != null) {
      troop.getCombat().setCombatDisabled(ModifierSource.ABILITY_TUNNEL, true);
    }
    troop.getMovement().setMovementDisabled(ModifierSource.ABILITY_TUNNEL, true);

    // Skip initial deploy animation -- tunnel handles the spawn delay instead
    troop.setDeployTimer(0);
  }

  private void spawnBuilding(Team team, Card card, float x, float y, int level) {
    TroopStats unitStats = card.getUnitStats();
    if (unitStats == null) {
      return;
    }

    int scaledBuildingHp = LevelScaling.scaleCard(unitStats.getHealth(), card.getRarity(), level);
    int scaledBuildingDamage =
        LevelScaling.scaleCard(unitStats.getDamage(), card.getRarity(), level);

    Combat combat = null;
    if (unitStats.getDamage() > 0) {
      float initialLoad = unitStats.isNoPreload() ? 0f : unitStats.getLoadTime();
      combat = buildCombatComponent(unitStats, scaledBuildingDamage, initialLoad).build();
    }

    // Create Spawner Component if needed
    SpawnerComponent spawner = null;
    boolean hasLiveSpawn = unitStats.getLiveSpawn() != null;
    boolean hasUnitLevelDeath = hasDeathMechanics(unitStats);

    if (hasLiveSpawn || hasUnitLevelDeath) {
      LiveSpawnConfig ls = hasLiveSpawn ? unitStats.getLiveSpawn() : null;
      TroopStats spawnStats = card.getSpawnTemplate();
      int scaledDeathDmg =
          LevelScaling.scaleCard(unitStats.getDeathDamage(), card.getRarity(), level);

      ProjectileStats deathProjStats =
          scaleDeathProjectile(unitStats.getDeathSpawnProjectile(), card.getRarity(), level);

      SpawnerComponent.SpawnerComponentBuilder spawnerBuilder =
          SpawnerComponent.builder()
              .deathDamage(scaledDeathDmg)
              .deathDamageRadius(unitStats.getDeathDamageRadius())
              .deathSpawns(unitStats.getDeathSpawns())
              .deathAreaEffect(unitStats.getDeathAreaEffect())
              .manaOnDeathForOpponent(unitStats.getManaOnDeathForOpponent())
              .deathSpawnProjectile(deathProjStats)
              .rarity(card.getRarity())
              .level(level);

      if (ls != null) {
        float initialTimer = resolveInitialSpawnerTimer(ls);
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

  private void castSpell(Team team, Card card, float x, float y, int level) {
    // Summon character spells (Rage -> RageBarbarianBottle, Heal -> HealSpirit)
    if (card.getSummonTemplate() != null) {
      TroopStats summonStats = card.getSummonTemplate();
      if (summonStats.getHealth() <= 0) {
        // Bomb entity (e.g. RageBarbarianBottle): 1 HP, selfDestruct, carries death mechanics
        Troop summoned = spawnBombSummon(team, summonStats, x, y, level, card.getRarity());
        state.spawnEntity(summoned);
      } else {
        Troop summoned =
            createTroop(team, summonStats, x, y, null, level, card.getRarity(), 0, 1, 0f, null);
        state.spawnEntity(summoned);
      }
    }

    // Area effect spells (Zap, Freeze, Poison, Earthquake, etc.)
    if (card.getAreaEffect() != null) {
      deployAreaEffect(team, card.getAreaEffect(), x, y, card.getRarity(), level);
      return;
    }

    // Projectile-based spells (Fireball, Arrows, Rocket, etc.)
    ProjectileStats proj = card.getProjectile();
    if (proj == null) {
      return;
    }

    // Scale spell damage by card level and rarity
    int damage = LevelScaling.scaleCard(proj.getDamage(), card.getRarity(), level);
    float speed = proj.getSpeed();
    float radius = proj.getRadius();
    List<EffectStats> effects = proj.getHitEffects();

    if (speed > 0) {
      // Traveling spell -- may be a multi-wave spell (e.g. Arrows fires 3 staggered projectiles)
      float startX, startY, destX, destY;

      if (card.isSpellAsDeploy()) {
        // spellAsDeploy: projectile starts at deploy point, travels forward
        startX = x;
        startY = y;
        destX = x;
        float forward = proj.getMinDistance() > 0 ? proj.getMinDistance() / 1000f : 3.0f;
        destY = (team == Team.BLUE) ? y + forward : y - forward;
      } else {
        // Standard: projectile flies in from behind
        startX = x;
        startY = (team == Team.BLUE) ? y - SPELL_TRAVEL_DISTANCE : y + SPELL_TRAVEL_DISTANCE;
        destX = x;
        destY = y;
      }

      int waves = card.getProjectileWaves() > 1 ? card.getProjectileWaves() : 1;
      int waveDelayFrames =
          Math.round(card.getProjectileWaveInterval() * GameEngine.TICKS_PER_SECOND);
      for (int i = 0; i < waves; i++) {
        Projectile p =
            new Projectile(
                team,
                startX,
                startY,
                destX,
                destY,
                damage,
                radius,
                speed,
                effects,
                proj.getCrownTowerDamagePercent());
        p.setPushback(proj.getPushback());
        p.setPushbackAll(proj.isPushbackAll());
        if (i > 0) {
          p.setDelayFrames(i * waveDelayFrames);
        }

        // Wire spawn character info for projectile spawn-on-impact (e.g. GoblinBarrel)
        if (proj.getSpawnCharacter() != null) {
          p.setSpawnCharacterStats(proj.getSpawnCharacter());
          p.setSpawnCharacterCount(proj.getSpawnCharacterCount());
          p.setSpawnCharacterRarity(card.getRarity());
          p.setSpawnCharacterLevel(level);
          p.setSpawnDeployTime(proj.getSpawnDeployTime());
        }

        // Wire spawnProjectile for sub-projectile spawning on impact (e.g. Log rolling)
        if (proj.getSpawnProjectile() != null) {
          p.setSpawnProjectile(proj.getSpawnProjectile());
          p.setSpellRarity(card.getRarity());
          p.setSpellLevel(level);
        }

        state.spawnProjectile(p);
      }
    } else {
      // Instant spell
      aoeDamageService.applySpellDamage(
          team, x, y, damage, radius, effects, proj.getCrownTowerDamagePercent());
    }
  }

  /**
   * Spawns a bomb summon entity (health=0 in data). Overrides HP to 1 and builds a SpawnerComponent
   * with selfDestruct=true so the entity dies after its deploy phase, triggering death mechanics
   * (death area effect, death spawns, death damage, etc.). Mirrors the bomb entity pattern in
   * {@link org.crforge.core.entity.SpawnerSystem#doSpawn}.
   */
  private Troop spawnBombSummon(
      Team team, TroopStats stats, float x, float y, int level, Rarity rarity) {
    int scaledDeathDamage =
        stats.getDeathDamage() > 0
            ? LevelScaling.scaleCard(stats.getDeathDamage(), rarity, level)
            : 0;

    ProjectileStats deathProjStats =
        scaleDeathProjectile(stats.getDeathSpawnProjectile(), rarity, level);

    SpawnerComponent spawner =
        SpawnerComponent.builder()
            .deathDamage(scaledDeathDamage)
            .deathDamageRadius(stats.getDeathDamageRadius())
            .deathPushback(stats.getDeathPushback())
            .deathSpawns(stats.getDeathSpawns())
            .deathAreaEffect(stats.getDeathAreaEffect())
            .manaOnDeathForOpponent(stats.getManaOnDeathForOpponent())
            .deathSpawnProjectile(deathProjStats)
            .rarity(rarity)
            .level(level)
            .selfDestruct(true)
            .build();

    return Troop.builder()
        .name(stats.getName())
        .team(team)
        .position(new Position(x, y))
        .health(new Health(1))
        .movement(
            new Movement(
                stats.getSpeed(),
                stats.getMass(),
                stats.getCollisionRadius(),
                stats.getVisualRadius(),
                stats.getMovementType()))
        .deployTime(stats.getDeployTime())
        .deployTimer(stats.getDeployTime() + stats.getDeployDelay())
        .spawner(spawner)
        .level(level)
        .build();
  }

  // --- Static utility methods ---

  /**
   * Builds the shared subset of Combat fields common to both troops and buildings. Callers can
   * chain additional builder calls for troop-specific fields before .build().
   */
  static Combat.CombatBuilder buildCombatComponent(
      TroopStats stats, int scaledDamage, float initialLoad) {
    return Combat.builder()
        .damage(scaledDamage)
        .range(stats.getRange())
        .sightRange(stats.getSightRange())
        .attackCooldown(stats.getAttackCooldown())
        .loadTime(stats.getLoadTime())
        .accumulatedLoadTime(initialLoad)
        .targetType(stats.getTargetType())
        .hitEffects(stats.getHitEffects())
        .projectileStats(stats.getProjectile())
        .targetOnlyBuildings(stats.isTargetOnlyBuildings())
        .targetOnlyTroops(stats.isTargetOnlyTroops())
        .ignoreTargetsWithBuff(stats.getIgnoreTargetsWithBuff())
        .minimumRange(stats.getMinimumRange())
        .crownTowerDamagePercent(stats.getCrownTowerDamagePercent())
        .selfAsAoeCenter(stats.isSelfAsAoeCenter())
        .kamikaze(stats.isKamikaze())
        .attackDashTime(stats.getAttackDashTime())
        .attackPushBack(stats.getAttackPushBack())
        .areaEffectOnHit(stats.getAreaEffectOnHit());
  }

  static float resolveInitialSpawnerTimer(LiveSpawnConfig ls) {
    return ls.spawnStartTime();
  }

  /** Scales the damage of a death spawn projectile by card level and preserves spawn character. */
  private static ProjectileStats scaleDeathProjectile(
      ProjectileStats deathProj, Rarity rarity, int level) {
    if (deathProj == null) {
      return null;
    }
    ProjectileStats scaled =
        deathProj.withDamage(LevelScaling.scaleCard(deathProj.getDamage(), rarity, level));
    if (deathProj.getSpawnCharacter() != null) {
      scaled = scaled.withSpawnCharacter(deathProj.getSpawnCharacter());
    }
    return scaled;
  }

  /** Returns true if the unit has any death-triggered mechanics. */
  private static boolean hasDeathMechanics(TroopStats stats) {
    return stats.getDeathDamage() > 0
        || !stats.getDeathSpawns().isEmpty()
        || stats.getDeathAreaEffect() != null
        || stats.getManaOnDeathForOpponent() > 0
        || stats.getDeathSpawnProjectile() != null;
  }

  /** Scales all hits in an attack sequence by card level. */
  private static List<AttackSequenceHit> scaleAttackSequence(
      List<AttackSequenceHit> sequence, Rarity rarity, int level) {
    return sequence.stream()
        .map(hit -> new AttackSequenceHit(LevelScaling.scaleCard(hit.damage(), rarity, level)))
        .toList();
  }
}
