package org.crforge.core.engine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.Card;
import org.crforge.core.card.CardType;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.LiveSpawnConfig;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
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
import org.crforge.core.player.Player;
import org.crforge.core.player.Team;
import org.crforge.core.player.dto.PlayerActionDTO;
import org.crforge.core.util.FormationLayout;
import org.crforge.core.util.Vector2;

/**
 * Handles the processing of Player Actions and the spawning of cards (Troops, Spells, Buildings).
 * Decouples game rules (Elixir spending, Card Cycling) and Entity Creation from the main loop.
 *
 * <p>Deployments go through a server sync delay before entities actually spawn on the arena. Elixir
 * is spent and the hand is cycled immediately, but the entity appears after {@link
 * #PLACEMENT_SYNC_DELAY} seconds -- matching real Clash Royale's server latency buffer.
 */
@RequiredArgsConstructor
public class DeploymentSystem {

  /** Server synchronization delay before a card's deploy timer starts (seconds). */
  public static final float PLACEMENT_SYNC_DELAY = 1.0f;

  /** Fixed delay between each unit spawn for multi-unit troop cards (seconds). */
  public static final float STAGGER_DELAY = 0.1f;

  private static final float SPELL_TRAVEL_DISTANCE = 10f;

  private final GameState state;
  private final CombatSystem combatSystem;

  // Internal queue to hold requests associated with the player who made them
  private final Queue<DeploymentRequest> requestQueue = new ConcurrentLinkedQueue<>();

  // Deployments waiting for the sync delay to expire before spawning
  @Getter private final List<PendingDeployment> pendingDeployments = new ArrayList<>();

  public void queueAction(Player player, PlayerActionDTO action) {
    requestQueue.offer(new DeploymentRequest(player, action));
  }

  /** Clears all queued actions and pending deployments. Called on match reset. */
  public void reset() {
    requestQueue.clear();
    pendingDeployments.clear();
  }

  /**
   * Processes queued actions and ticks pending deployment timers. Multi-unit TROOP cards spawn one
   * unit at a time with stagger delay after the sync delay expires.
   *
   * @param deltaTime time elapsed since last update (seconds)
   */
  public void update(float deltaTime) {
    // 1. Drain request queue -> create PendingDeployments
    while (!requestQueue.isEmpty()) {
      DeploymentRequest request = requestQueue.poll();
      processRequest(request);
    }

    // 2. Tick pending deployment timers with two-phase spawning
    Iterator<PendingDeployment> it = pendingDeployments.iterator();
    while (it.hasNext()) {
      PendingDeployment pending = it.next();

      // Phase 1: Sync delay countdown
      if (!pending.syncComplete) {
        pending.remainingDelay -= deltaTime;
        if (pending.remainingDelay <= 0) {
          pending.syncComplete = true;
          // Carry over leftover time from sync into stagger phase
          pending.staggerTimer = pending.remainingDelay; // negative or zero
        } else {
          continue;
        }
      } else {
        // Phase 2 tick: only subtract deltaTime on ticks after sync completed
        pending.staggerTimer -= deltaTime;
      }

      while (pending.staggerTimer <= 0 && pending.nextUnitIndex < pending.totalUnits) {
        if (pending.isTroop()) {
          // Spawn one troop at a time (single-unit cards have staggerDelay=0, so the
          // while loop spawns the one unit instantly with no behavioral change)
          spawnSingleTroop(
              pending.team,
              pending.card,
              pending.x,
              pending.y,
              pending.level,
              pending.nextUnitIndex);

          // Fire deploy effect only on the first unit
          if (!pending.deployEffectFired && pending.card.getDeployEffect() != null) {
            deployAreaEffect(
                pending.team,
                pending.card.getDeployEffect(),
                pending.x,
                pending.y,
                pending.card.getRarity(),
                pending.level);
            pending.deployEffectFired = true;
          }

          // Fire spawn projectile only on the first unit (e.g. MegaKnight landing damage)
          if (!pending.spawnProjectileFired && pending.card.getSpawnProjectile() != null) {
            fireSpawnProjectile(pending.team, pending.card, pending.x, pending.y, pending.level);
            pending.spawnProjectileFired = true;
          }
        } else {
          // Non-staggered: buildings and spells
          spawnCard(pending.team, pending.card, pending.x, pending.y, pending.level);
          pending.nextUnitIndex = pending.totalUnits; // mark complete
          break;
        }

        pending.nextUnitIndex++;
        pending.staggerTimer += pending.staggerDelay;
      }

      // Remove when all units have spawned
      if (pending.nextUnitIndex >= pending.totalUnits) {
        it.remove();
      }
    }
  }

  private void processRequest(DeploymentRequest request) {
    Player player = request.player;
    PlayerActionDTO action = request.action;

    // 1. Validate Resources & Cycle Card (elixir spent immediately)
    Card card = player.tryPlayCard(action);

    if (card != null) {
      int cardLevel = player.getLevelConfig().getLevelFor(card);
      // 2. Queue for spawn after sync delay
      pendingDeployments.add(
          new PendingDeployment(
              player.getTeam(),
              card,
              action.getX(),
              action.getY(),
              cardLevel,
              PLACEMENT_SYNC_DELAY));
    }
  }

  /**
   * Spawns a non-troop card (building or spell). Troop cards are handled directly by the stagger
   * loop in {@link #update(float)}.
   */
  private void spawnCard(Team team, Card card, float x, float y, int level) {
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
  private void spawnSingleTroop(Team team, Card card, float x, float y, int level, int idx) {
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
    SpawnerComponent spawner = null;

    if (idx == 0 && unitStats.getLiveSpawn() != null) {
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
                .rarity(card.getRarity())
                .level(level)
                .build();
      }
    }

    // Unit-level death mechanics (e.g. Golem death damage + death spawn)
    boolean hasUnitDeathMechanics =
        unitStats.getDeathDamage() > 0 || !unitStats.getDeathSpawns().isEmpty();
    if (hasUnitDeathMechanics) {
      int scaledDeathDmg =
          LevelScaling.scaleCard(unitStats.getDeathDamage(), card.getRarity(), level);

      if (spawner == null) {
        // Create a death-only SpawnerComponent
        spawner =
            SpawnerComponent.builder()
                .deathDamage(scaledDeathDmg)
                .deathDamageRadius(unitStats.getDeathDamageRadius())
                .deathSpawns(unitStats.getDeathSpawns())
                .rarity(card.getRarity())
                .level(level)
                .build();
      } else {
        // Merge death mechanics into existing spawner
        spawner.setDeathDamage(scaledDeathDmg);
        spawner.setDeathDamageRadius(unitStats.getDeathDamageRadius());
        spawner.setDeathSpawns(unitStats.getDeathSpawns());
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
    state.spawnEntity(troop);
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

    Combat combat =
        buildCombatComponent(stats, scaledDamage, initialLoad)
            .aoeRadius(stats.getAoeRadius())
            .multipleTargets(stats.getMultipleTargets())
            .multipleProjectiles(stats.getMultipleProjectiles())
            .buffOnDamage(stats.getBuffOnDamage())
            .build();

    // Scale shield by level
    int scaledShield =
        stats.getShieldHitpoints() > 0
            ? LevelScaling.scaleCard(stats.getShieldHitpoints(), rarity, level)
            : 0;

    // Create ability component if unit has one
    AbilityComponent abilityComponent =
        stats.getAbility() != null ? new AbilityComponent(stats.getAbility()) : null;

    Movement movement =
        new Movement(
            stats.getSpeed(),
            stats.getMass(),
            stats.getCollisionRadius(),
            stats.getVisualRadius(),
            stats.getMovementType());
    movement.setIgnorePushback(stats.isIgnorePushback());
    movement.setJumpEnabled(stats.isJumpEnabled());

    // Explicitly set deployTimer to deployTime to avoid default value issues
    return Troop.builder()
        .name(stats.getName())
        .team(team)
        .position(new Position(spawnX, spawnY))
        .health(new Health(scaledHp, scaledShield))
        .movement(movement)
        .combat(combat)
        .deployTime(stats.getDeployTime())
        .deployTimer(stats.getDeployTime())
        .spawner(spawner)
        .ability(abilityComponent)
        .level(level)
        .build();
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
    boolean hasUnitLevelDeath =
        unitStats.getDeathDamage() > 0 || !unitStats.getDeathSpawns().isEmpty();

    if (hasLiveSpawn || hasUnitLevelDeath) {
      LiveSpawnConfig ls = hasLiveSpawn ? unitStats.getLiveSpawn() : null;
      TroopStats spawnStats = card.getSpawnTemplate();
      int scaledDeathDmg =
          LevelScaling.scaleCard(unitStats.getDeathDamage(), card.getRarity(), level);

      SpawnerComponent.SpawnerComponentBuilder spawnerBuilder =
          SpawnerComponent.builder()
              .deathDamage(scaledDeathDmg)
              .deathDamageRadius(unitStats.getDeathDamageRadius())
              .deathSpawns(unitStats.getDeathSpawns())
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
            .formationRadius(ls.spawnRadius());

        // Derive deathSpawnCount from first death spawn entry
        if (unitStats.getDeathSpawns() != null && !unitStats.getDeathSpawns().isEmpty()) {
          spawnerBuilder.deathSpawnCount(unitStats.getDeathSpawns().get(0).count());
        }
      }

      spawner = spawnerBuilder.build();
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
            .lifetime(unitStats.getLifeTime())
            .remainingLifetime(unitStats.getLifeTime())
            .spawner(spawner)
            .deployTime(unitStats.getDeployTime())
            .deployTimer(unitStats.getDeployTime())
            .level(level)
            .build();

    state.spawnEntity(building);
  }

  private void castSpell(Team team, Card card, float x, float y, int level) {
    // Summon character spells (Rage -> RageBottle, Heal -> HealSpirit)
    if (card.getSummonTemplate() != null) {
      Troop summoned =
          createTroop(
              team, card.getSummonTemplate(), x, y, null, level, card.getRarity(), 0, 1, 0f, null);
      state.spawnEntity(summoned);
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
      // Traveling spell
      float startY = (team == Team.BLUE) ? y - SPELL_TRAVEL_DISTANCE : y + SPELL_TRAVEL_DISTANCE;
      Projectile p =
          new Projectile(
              team,
              x,
              startY,
              x,
              y,
              damage,
              radius,
              speed,
              effects,
              proj.getCrownTowerDamagePercent());
      p.setPushback(proj.getPushback());
      p.setPushbackAll(proj.isPushbackAll());
      state.spawnProjectile(p);
    } else {
      // Instant spell
      combatSystem.applySpellDamage(
          team, x, y, damage, radius, effects, proj.getCrownTowerDamagePercent());
    }
  }

  private void fireSpawnProjectile(Team team, Card card, float x, float y, int level) {
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

  private void deployAreaEffect(
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

    AreaEffect effect =
        AreaEffect.builder()
            .name(stats.getName())
            .team(team)
            .position(new Position(x, y))
            .stats(stats)
            .scaledDamage(scaledDamage)
            .resolvedCrownTowerDamagePercent(resolvedCtdp)
            .buildingDamagePercent(buildingDmgPct)
            .remainingLifetime(stats.getLifeDuration())
            .build();

    state.spawnEntity(effect);
  }

  private static float resolveInitialSpawnerTimer(LiveSpawnConfig ls) {
    return ls.spawnStartTime();
  }

  /**
   * Builds the shared subset of Combat fields common to both troops and buildings. Callers can
   * chain additional builder calls for troop-specific fields before .build().
   */
  private static Combat.CombatBuilder buildCombatComponent(
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
        .minimumRange(stats.getMinimumRange())
        .crownTowerDamagePercent(stats.getCrownTowerDamagePercent())
        .selfAsAoeCenter(stats.isSelfAsAoeCenter())
        .kamikaze(stats.isKamikaze());
  }

  // Simple container for the queue
  private record DeploymentRequest(Player player, PlayerActionDTO action) {}

  /**
   * Holds a resolved deployment during the server sync delay and staggered unit spawning. Elixir
   * has already been spent and the hand cycled.
   *
   * <p>Two-phase lifecycle:
   *
   * <ol>
   *   <li>Sync delay: {@code remainingDelay} counts down to zero
   *   <li>Stagger: multi-unit TROOP cards spawn one unit at a time with {@code staggerDelay}
   *       between each; buildings/spells/single-unit troops spawn all at once
   * </ol>
   */
  @Getter
  public static class PendingDeployment {

    final Team team;
    final Card card;
    final float x;
    final float y;
    final int level;
    float remainingDelay;

    // Stagger state
    int nextUnitIndex;
    final int totalUnits;
    final float staggerDelay;
    float staggerTimer;
    boolean syncComplete;
    boolean deployEffectFired;
    boolean spawnProjectileFired;

    PendingDeployment(Team team, Card card, float x, float y, int level, float remainingDelay) {
      this.team = team;
      this.card = card;
      this.x = x;
      this.y = y;
      this.level = level;
      this.remainingDelay = remainingDelay;

      // Multi-unit TROOP cards get staggered spawning
      this.totalUnits = card.getType() == CardType.TROOP ? card.getTotalDeployCount() : 1;
      this.staggerDelay = totalUnits > 1 ? STAGGER_DELAY : 0f;
    }

    /** Whether this deployment is a troop card (all troops go through the stagger path). */
    public boolean isTroop() {
      return card.getType() == CardType.TROOP;
    }

    /**
     * Whether this deployment is currently in the stagger phase (sync delay expired, but not all
     * units have spawned yet).
     */
    public boolean isStaggering() {
      return syncComplete && nextUnitIndex < totalUnits;
    }

    /** Returns the fraction of stagger progress (0.0 = no units spawned, 1.0 = all spawned). */
    public float getStaggerProgress() {
      return totalUnits > 0 ? (float) nextUnitIndex / totalUnits : 1f;
    }
  }
}
