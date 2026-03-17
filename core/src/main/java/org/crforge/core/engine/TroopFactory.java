package org.crforge.core.engine;

import java.util.List;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.BuffAllyAbility;
import org.crforge.core.ability.RangedAttackAbility;
import org.crforge.core.ability.TunnelAbility;
import org.crforge.core.arena.Arena;
import org.crforge.core.card.AttackSequenceHit;
import org.crforge.core.card.Card;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.LiveSpawnConfig;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.component.AttachedComponent;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.ModifierSource;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.util.FormationLayout;
import org.crforge.core.util.Vector2;

/** Creates Troop entities with level-scaled stats, formation offsets, and attached units. */
class TroopFactory {

  private final GameState state;

  TroopFactory(GameState state) {
    this.state = state;
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
        float initialTimer = EntityScaling.resolveInitialSpawnerTimer(ls);
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
    if (EntityScaling.hasDeathMechanics(unitStats)) {
      int scaledDeathDmg =
          LevelScaling.scaleCard(unitStats.getDeathDamage(), card.getRarity(), level);
      ProjectileStats deathProjStats =
          EntityScaling.scaleDeathProjectile(
              unitStats.getDeathSpawnProjectile(), card.getRarity(), level);

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

  /**
   * Spawns a bomb summon entity (health=0 in data). Overrides HP to 1 and builds a SpawnerComponent
   * with selfDestruct=true so the entity dies after its deploy phase, triggering death mechanics
   * (death area effect, death spawns, death damage, etc.). Mirrors the bomb entity pattern in
   * {@link org.crforge.core.entity.SpawnerSystem#doSpawn}.
   */
  Troop spawnBombSummon(Team team, TroopStats stats, float x, float y, int level, Rarity rarity) {
    int scaledDeathDamage =
        stats.getDeathDamage() > 0
            ? LevelScaling.scaleCard(stats.getDeathDamage(), rarity, level)
            : 0;

    ProjectileStats deathProjStats =
        EntityScaling.scaleDeathProjectile(stats.getDeathSpawnProjectile(), rarity, level);

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

  /**
   * Spawns units that are permanently attached to a parent entity (e.g. Ram Rider on Ram, Spear
   * Goblins on Goblin Giant). Attached units ride on the parent, are non-targetable, invulnerable,
   * and die when the parent dies.
   */
  void spawnAttachedUnits(Troop parent, Card card, int level) {
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
          EntityScaling.scaleAttackSequence(
              spawnStats.getAttackSequence(), card.getRarity(), level);

      Combat combat =
          EntityScaling.buildCombatComponent(spawnStats, scaledDamage, initialLoad)
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
  Troop createTroop(
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
        EntityScaling.scaleAttackSequence(stats.getAttackSequence(), rarity, level);

    Combat combat =
        EntityScaling.buildCombatComponent(stats, scaledDamage, initialLoad)
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
  void initializeTunnel(Troop troop, float targetX, float targetY) {
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
}
