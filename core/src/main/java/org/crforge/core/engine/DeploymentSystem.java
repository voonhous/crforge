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
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.LevelScaling;
import org.crforge.core.card.LiveSpawnConfig;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.Rarity;
import org.crforge.core.card.TroopStats;
import org.crforge.core.combat.CombatSystem;
import org.crforge.core.effect.BuffDefinition;
import org.crforge.core.effect.BuffRegistry;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Health;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.component.SpawnerComponent;
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
 * <p>Deployments go through a server sync delay before entities actually spawn on the arena.
 * Elixir is spent and the hand is cycled immediately, but the entity appears after
 * {@link #PLACEMENT_SYNC_DELAY} seconds -- matching real Clash Royale's server latency buffer.
 */
@RequiredArgsConstructor
public class DeploymentSystem {

  /**
   * Server synchronization delay before a card's deploy timer starts (seconds).
   */
  public static final float PLACEMENT_SYNC_DELAY = 1.0f;

  private static final float SPELL_TRAVEL_DISTANCE = 10f;

  private final GameState state;
  private final CombatSystem combatSystem;

  // Internal queue to hold requests associated with the player who made them
  private final Queue<DeploymentRequest> requestQueue = new ConcurrentLinkedQueue<>();

  // Deployments waiting for the sync delay to expire before spawning
  @Getter
  private final List<PendingDeployment> pendingDeployments = new ArrayList<>();

  public void queueAction(Player player, PlayerActionDTO action) {
    requestQueue.offer(new DeploymentRequest(player, action));
  }

  /**
   * Processes queued actions and ticks pending deployment timers.
   *
   * @param deltaTime time elapsed since last update (seconds)
   */
  public void update(float deltaTime) {
    // 1. Drain request queue -> create PendingDeployments
    while (!requestQueue.isEmpty()) {
      DeploymentRequest request = requestQueue.poll();
      processRequest(request);
    }

    // 2. Tick pending deployment timers and spawn when ready
    Iterator<PendingDeployment> it = pendingDeployments.iterator();
    while (it.hasNext()) {
      PendingDeployment pending = it.next();
      pending.remainingDelay -= deltaTime;
      if (pending.remainingDelay <= 0) {
        spawnCard(pending.team, pending.card, pending.x, pending.y, pending.level);
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
      pendingDeployments.add(new PendingDeployment(
          player.getTeam(), card, action.getX(), action.getY(), cardLevel,
          PLACEMENT_SYNC_DELAY));
    }
  }

  private void spawnCard(Team team, Card card, float x, float y, int level) {
    switch (card.getType()) {
      case TROOP -> spawnTroops(team, card, x, y, level);
      case BUILDING -> spawnBuilding(team, card, x, y, level);
      case SPELL -> castSpell(team, card, x, y, level);
    }
  }

  private void spawnTroops(Team team, Card card, float x, float y, int level) {
    TroopStats unitStats = card.getUnitStats();
    if (unitStats == null) {
      return;
    }

    int totalUnits = card.getUnitCount();
    float summonRadius = card.getSummonRadius();

    for (int idx = 0; idx < totalUnits; idx++) {
      // Check for spawner capability (Witch/Mother Witch logic)
      SpawnerComponent spawner = null;

      if (idx == 0 && unitStats.getLiveSpawn() != null) {
        LiveSpawnConfig ls = unitStats.getLiveSpawn();
        TroopStats spawnStats = card.getSpawnTemplate();
        if (spawnStats != null) {
          float initialTimer = ls.spawnStartTime() > 0
              ? ls.spawnStartTime() : ls.spawnPauseTime();
          spawner = SpawnerComponent.builder()
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
        int scaledDeathDmg = LevelScaling.scaleCard(unitStats.getDeathDamage(), card.getRarity(),
            level);

        if (spawner == null) {
          // Create a death-only SpawnerComponent
          spawner = SpawnerComponent.builder()
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

      Troop troop = createTroop(team, unitStats, x, y, spawner, level, card.getRarity(),
          idx, totalUnits, summonRadius);
      state.spawnEntity(troop);
    }

    // Deploy effect (e.g. ElectroWizard stun, IceWizard slow on entry)
    if (card.getDeployEffect() != null) {
      deployAreaEffect(team, card.getDeployEffect(), x, y, card.getRarity(), level);
    }
  }

  /**
   * The Red player's view is rotated 180 degrees relative to the Blue player's view.
   * <p>
   * This means that a formation defined with offsets (dx, dy) for Blue (where +y is forward and +x
   * is right) should be applied as (-dx, -dy) for Red to preserve the formation's relative shape
   * and orientation towards the enemy.
   */
  private Troop createTroop(Team team, TroopStats stats, float baseX, float baseY,
      SpawnerComponent spawner, int level, Rarity rarity,
      int index, int total, float summonRadius) {
    float offsetX = 0f;
    float offsetY = 0f;

    if (total > 1 && summonRadius > 0) {
      // Use calculated circular formation offsets
      Vector2 offset = FormationLayout.calculateDeployOffset(
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

    Combat combat = Combat.builder()
        .damage(scaledDamage)
        .range(stats.getRange())
        .sightRange(stats.getSightRange())
        .attackCooldown(stats.getAttackCooldown())
        .loadTime(stats.getLoadTime())
        .accumulatedLoadTime(initialLoad)
        .aoeRadius(stats.getAoeRadius())
        .targetType(stats.getTargetType())
        .hitEffects(stats.getHitEffects())
        .projectileStats(stats.getProjectile())
        .targetOnlyBuildings(stats.isTargetOnlyBuildings())
        .minimumRange(stats.getMinimumRange())
        .crownTowerDamagePercent(stats.getCrownTowerDamagePercent())
        .multipleTargets(stats.getMultipleTargets())
        .multipleProjectiles(stats.getMultipleProjectiles())
        .buffOnDamage(stats.getBuffOnDamage())
        .build();

    // Scale shield by level
    int scaledShield = stats.getShieldHitpoints() > 0
        ? LevelScaling.scaleCard(stats.getShieldHitpoints(), rarity, level) : 0;

    // Create ability component if unit has one
    AbilityComponent abilityComponent = stats.getAbility() != null
        ? new AbilityComponent(stats.getAbility()) : null;

    // Explicitly set deployTimer to deployTime to avoid default value issues
    return Troop.builder()
        .name(stats.getName())
        .team(team)
        .position(new Position(spawnX, spawnY))
        .health(new Health(scaledHp, scaledShield))
        .movement(new Movement(
            stats.getSpeed(),
            stats.getMass(),
            stats.getCollisionRadius(),
            stats.getVisualRadius(),
            stats.getMovementType()))
        .combat(combat)
        .deployTime(stats.getDeployTime())
        .deployTimer(stats.getDeployTime())
        .spawner(spawner)
        .ability(abilityComponent)
        .build();
  }

  private void spawnBuilding(Team team, Card card, float x, float y, int level) {
    TroopStats unitStats = card.getUnitStats();

    Combat combat = null;
    int scaledBuildingHp = unitStats != null
        ? LevelScaling.scaleCard(unitStats.getHealth(), card.getRarity(), level) : 0;
    int scaledBuildingDamage = unitStats != null
        ? LevelScaling.scaleCard(unitStats.getDamage(), card.getRarity(), level) : 0;

    if (unitStats != null && unitStats.getDamage() > 0) {
      float initialLoad = unitStats.isNoPreload() ? 0f : unitStats.getLoadTime();
      combat = Combat.builder()
          .damage(scaledBuildingDamage)
          .range(unitStats.getRange())
          .sightRange(unitStats.getSightRange())
          .attackCooldown(unitStats.getAttackCooldown())
          .loadTime(unitStats.getLoadTime())
          .accumulatedLoadTime(initialLoad)
          .targetType(unitStats.getTargetType())
          .hitEffects(unitStats.getHitEffects())
          .projectileStats(unitStats.getProjectile())
          .targetOnlyBuildings(unitStats.isTargetOnlyBuildings())
          .minimumRange(unitStats.getMinimumRange())
          .crownTowerDamagePercent(unitStats.getCrownTowerDamagePercent())
          .build();
    }

    float collisionRadius = unitStats != null ? unitStats.getCollisionRadius() : 1.0f;
    float visualRadius = unitStats != null ? unitStats.getVisualRadius() : 1.0f;
    float deployTime = unitStats != null ? unitStats.getDeployTime() : 1.0f;
    float lifeTime = unitStats != null ? unitStats.getLifeTime() : 0f;

    // Create Spawner Component if needed
    SpawnerComponent spawner = null;
    boolean hasLiveSpawn = unitStats != null && unitStats.getLiveSpawn() != null;
    boolean hasUnitLevelDeath = unitStats != null
        && (unitStats.getDeathDamage() > 0 || !unitStats.getDeathSpawns().isEmpty());

    if (hasLiveSpawn || hasUnitLevelDeath) {
      LiveSpawnConfig ls = hasLiveSpawn ? unitStats.getLiveSpawn() : null;
      TroopStats spawnStats = card.getSpawnTemplate();
      int scaledDeathDmg = unitStats != null
          ? LevelScaling.scaleCard(unitStats.getDeathDamage(), card.getRarity(), level) : 0;

      SpawnerComponent.SpawnerComponentBuilder spawnerBuilder = SpawnerComponent.builder()
          .deathDamage(scaledDeathDmg)
          .deathDamageRadius(unitStats != null ? unitStats.getDeathDamageRadius() : 0f)
          .deathSpawns(unitStats != null ? unitStats.getDeathSpawns() : List.of())
          .rarity(card.getRarity())
          .level(level);

      if (ls != null) {
        float initialTimer = ls.spawnStartTime() > 0
            ? ls.spawnStartTime() : ls.spawnPauseTime();
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
    Building building = Building.builder()
        .name(card.getName())
        .team(team)
        .position(new Position(x, y))
        .health(new Health(scaledBuildingHp))
        .movement(new Movement(0, 0, collisionRadius, visualRadius, MovementType.BUILDING))
        .combat(combat)
        .lifetime(lifeTime)
        .remainingLifetime(lifeTime)
        .spawner(spawner)
        .deployTime(deployTime)
        .deployTimer(deployTime) // Explicitly set timer to match time
        .build();

    state.spawnEntity(building);
  }

  private void castSpell(Team team, Card card, float x, float y, int level) {
    // Summon character spells (Rage -> RageBottle, Heal -> HealSpirit)
    if (card.getSummonTemplate() != null) {
      Troop summoned = createTroop(team, card.getSummonTemplate(), x, y, null, level,
          card.getRarity(), 0, 1, 0f);
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
      Projectile p = new Projectile(team, x, startY, x, y, damage, radius, speed, effects);
      state.spawnProjectile(p);
    } else {
      // Instant spell
      combatSystem.applySpellDamage(team, x, y, damage, radius, effects);
    }
  }

  private void deployAreaEffect(Team team, AreaEffectStats stats, float x, float y,
      Rarity rarity, int level) {
    int scaledDamage = stats.getDamage() > 0
        ? LevelScaling.scaleCard(stats.getDamage(), rarity, level)
        : 0;
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

    AreaEffect effect = AreaEffect.builder()
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

  // Simple container for the queue
  private record DeploymentRequest(Player player, PlayerActionDTO action) {

  }

  /**
   * Holds a resolved deployment during the server sync delay. Elixir has already been spent and the
   * hand cycled; the entity spawns once {@code remainingDelay} reaches zero.
   */
  @Getter
  public static class PendingDeployment {

    final Team team;
    final Card card;
    final float x;
    final float y;
    final int level;
    float remainingDelay;

    PendingDeployment(Team team, Card card, float x, float y, int level, float remainingDelay) {
      this.team = team;
      this.card = card;
      this.x = x;
      this.y = y;
      this.level = level;
      this.remainingDelay = remainingDelay;
    }
  }
}
