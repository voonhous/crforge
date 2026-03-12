package org.crforge.core.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.crforge.core.ability.AbilitySystem;
import org.crforge.core.ability.ReflectAbility;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.EffectStats;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Movement;
import org.crforge.core.component.Position;
import org.crforge.core.effect.AppliedEffect;
import org.crforge.core.effect.StatusEffectType;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.base.MovementType;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;
import org.crforge.core.util.Vector2;

/** Handles attack execution, damage dealing, and projectile management. */
public class CombatSystem {

  // Knockback: 15 active frames at 30fps = 0.5s displacement duration
  private static final float KNOCKBACK_DURATION = 0.5f;
  // Speed calculation base: distance / 1.0s (reference JS uses 30 frames for speed calc)
  private static final float KNOCKBACK_MAX_TIME = 1.0f;

  private final GameState gameState;

  public CombatSystem(GameState gameState) {
    this.gameState = gameState;
  }

  /** Process combat for all entities. Called each tick. */
  public void update(float deltaTime) {
    // Capture alive entities once for the entire combat update
    List<Entity> aliveEntities = gameState.getAliveEntities();

    // Process attacks for ANY entity with a combat component (Troop or Building)
    for (Entity entity : aliveEntities) {
      processEntityCombat(entity, aliveEntities);
    }

    // Update and process projectiles
    updateProjectiles(deltaTime);
  }

  private void processEntityCombat(Entity entity, List<Entity> aliveEntities) {
    // Inactive towers or waking up towers cannot attack
    if (entity instanceof Tower tower) {
      if (!tower.isActive() || tower.isWakingUp()) {
        return;
      }
    }

    Combat combat = entity.getCombat();

    // Entity cannot fight (e.g., Elixir Collector)
    if (combat == null) {
      return;
    }

    // Troops cannot attack while deploying
    if (entity instanceof Troop troop && troop.isDeploying()) {
      return;
    }

    // Entities cannot attack while being knocked back
    if (entity.getMovement() != null && entity.getMovement().isKnockedBack()) {
      return;
    }

    if (!combat.hasTarget()) {
      // If we don't have a target, ensure we aren't stuck in attack state
      if (combat.isAttacking()) {
        combat.setAttacking(false);
        combat.setCurrentWindup(0);
      }
      return;
    }

    Entity target = combat.getCurrentTarget();

    // Range Check
    if (!isInAttackRange(entity, target, combat)) {
      // If out of range, cancel any ongoing attack
      if (combat.isAttacking()) {
        combat.setAttacking(false);
        combat.setCurrentWindup(0);
      }
      return;
    }

    // Check Cooldown
    if (!combat.canAttack()) {
      return;
    }

    // Charge impact: skip windup and deal damage instantly on contact
    if (entity instanceof Troop troop
        && troop.getAbility() != null
        && troop.getAbility().isCharged()) {
      combat.startAttackSequence();
      executeAttack(entity, target, combat);
      return;
    }

    // Start Attack if ready
    if (!combat.isAttacking()) {
      combat.startAttackSequence();
    }

    // Check Windup
    if (combat.isWindingUp()) {
      return;
    }

    // Execute Attack (Windup complete)
    executeAttack(entity, target, combat);

    // Multiple targets: attack additional enemies simultaneously (e.g. EWiz hits 2)
    if (combat.getMultipleTargets() > 1) {
      attackAdditionalTargets(entity, target, combat, aliveEntities);
    }
  }

  private boolean isInAttackRange(Entity attacker, Entity target, Combat combat) {
    float distanceSq = attacker.getPosition().distanceToSquared(target.getPosition());
    // Use Collision Radius for range calculation
    float effectiveRange =
        combat.getRange() + attacker.getCollisionRadius() + target.getCollisionRadius();

    // Minimum range check (e.g. Mortar cannot attack nearby enemies)
    if (combat.getMinimumRange() > 0) {
      float effectiveMinRange =
          combat.getMinimumRange() + attacker.getCollisionRadius() + target.getCollisionRadius();
      if (distanceSq < effectiveMinRange * effectiveMinRange) {
        return false;
      }
    }

    return distanceSq <= effectiveRange * effectiveRange;
  }

  private void executeAttack(Entity attacker, Entity target, Combat combat) {
    int baseDamage =
        combat.getDamageOverride() > 0 ? combat.getDamageOverride() : combat.getDamage();

    // Charge ability: override damage for this attack if charged
    if (attacker instanceof Troop troop) {
      baseDamage = AbilitySystem.getChargeDamage(troop.getAbility(), baseDamage);
    }

    if (combat.isRanged()) {
      Projectile projectile = createAttackProjectile(attacker, target, baseDamage, combat);
      gameState.spawnProjectile(projectile);
    } else {
      // Melee attack, deal damage immediately
      int effectiveDamage =
          DamageUtil.adjustForCrownTower(baseDamage, target, combat.getCrownTowerDamagePercent());

      if (combat.getAoeRadius() > 0) {
        // selfAsAoeCenter: AOE is centered on the attacker (e.g. Valkyrie 360-degree splash)
        Entity aoeCenter = combat.isSelfAsAoeCenter() ? attacker : target;
        dealAoeDamage(
            attacker, aoeCenter, effectiveDamage, combat.getAoeRadius(), combat.getHitEffects());
      } else {
        // Apply effects BEFORE damage to ensure One-Hit Kills still trigger effect logic (e.g.
        // Curse)
        applyEffects(target, combat.getHitEffects());
        dealDamage(target, effectiveDamage);
      }

      // Apply buff-on-damage for melee attacks
      applyBuffOnDamage(combat, target);

      // Reflect: if target has REFLECT ability and attacker is within reflect radius, deal
      // counter-damage
      if (target instanceof Troop reflector) {
        int reflectDmg = AbilitySystem.getReflectDamage(reflector);
        if (reflectDmg > 0 && reflector.getAbility().getData() instanceof ReflectAbility reflect) {
          float dist = attacker.getPosition().distanceTo(reflector.getPosition());
          float effectiveRadius = reflect.reflectRadius() + attacker.getCollisionRadius();
          if (dist <= effectiveRadius) {
            applyReflectDamage(reflector, attacker, reflectDmg);
          }
        }
      }
    }

    // Consume charge after attack
    if (attacker instanceof Troop t) {
      AbilitySystem.consumeCharge(t);
    }

    combat.finishAttack();

    // Kamikaze: unit dies after delivering its attack (e.g. Battle Ram)
    if (combat.isKamikaze()) {
      attacker.getHealth().kill();
    }
  }

  /**
   * Finds and attacks additional targets for units with multipleTargets > 1 (e.g. EWiz). The
   * primary target has already been attacked; this method handles the extras.
   */
  private void attackAdditionalTargets(
      Entity attacker, Entity primaryTarget, Combat combat, List<Entity> aliveEntities) {
    int extraTargets = combat.getMultipleTargets() - 1;
    Team enemyTeam = attacker.getTeam().opposite();

    List<Entity> candidates = new ArrayList<>();
    for (Entity e : aliveEntities) {
      if (e == primaryTarget || e.getTeam() != enemyTeam || !e.isTargetable()) {
        continue;
      }
      if (!isInAttackRange(attacker, e, combat)) {
        continue;
      }
      if (!TargetingSystem.canTargetMovementType(combat.getTargetType(), e.getMovementType())) {
        continue;
      }
      candidates.add(e);
    }

    // Sort by squared distance (preserves ordering, avoids sqrt)
    candidates.sort(
        (a, b) -> {
          float da = attacker.getPosition().distanceToSquared(a.getPosition());
          float db = attacker.getPosition().distanceToSquared(b.getPosition());
          return Float.compare(da, db);
        });

    int baseDamage = combat.getDamage();
    int fired = Math.min(extraTargets, candidates.size());

    for (int i = 0; i < fired; i++) {
      Entity extraTarget = candidates.get(i);

      if (combat.isRanged()) {
        Projectile projectile = createAttackProjectile(attacker, extraTarget, baseDamage, combat);
        gameState.spawnProjectile(projectile);
      } else {
        int effectiveDamage =
            DamageUtil.adjustForCrownTower(
                baseDamage, extraTarget, combat.getCrownTowerDamagePercent());
        applyEffects(extraTarget, combat.getHitEffects());
        dealDamage(extraTarget, effectiveDamage);
        applyBuffOnDamage(combat, extraTarget);
      }
    }

    // If we couldn't find enough unique targets, fire remaining shots at the primary target
    int remaining = extraTargets - fired;
    for (int i = 0; i < remaining; i++) {
      if (combat.isRanged()) {
        Projectile projectile = createAttackProjectile(attacker, primaryTarget, baseDamage, combat);
        gameState.spawnProjectile(projectile);
      } else {
        int effectiveDamage =
            DamageUtil.adjustForCrownTower(
                baseDamage, primaryTarget, combat.getCrownTowerDamagePercent());
        applyEffects(primaryTarget, combat.getHitEffects());
        dealDamage(primaryTarget, effectiveDamage);
        applyBuffOnDamage(combat, primaryTarget);
      }
    }
  }

  private void applyBuffOnDamage(Combat combat, Entity target) {
    EffectStats buff = combat.getBuffOnDamage();
    if (buff == null || !target.isAlive()) {
      return;
    }
    // Route through applyEffects so stun/freeze charge reset logic is triggered
    applyEffects(target, List.of(buff));
  }

  private void applyReflectDamage(Troop reflector, Entity attacker, int reflectDamage) {
    ReflectAbility reflect = (ReflectAbility) reflector.getAbility().getData();
    int effectiveDamage =
        DamageUtil.adjustForCrownTower(
            reflectDamage, attacker, reflect.reflectCrownTowerDamagePercent());
    dealDamage(attacker, effectiveDamage);

    // Apply reflect buff (e.g. ZapFreeze stun) to attacker
    if (reflect.reflectBuff() != null && reflect.reflectBuffDuration() > 0) {
      EffectStats reflectEffect =
          EffectStats.builder()
              .type(reflect.reflectBuff())
              .duration(reflect.reflectBuffDuration())
              .buffName(reflect.reflectBuffName())
              .build();
      applyEffects(attacker, List.of(reflectEffect));
    }
  }

  private void updateProjectiles(float deltaTime) {
    List<Projectile> projectiles = gameState.getProjectiles();
    // Use index-based loop: onProjectileHit may spawn new projectiles (chain lightning,
    // spawn-on-impact) which append to the list. We only process projectiles that existed
    // at the start of this tick by capturing the initial size.
    int count = projectiles.size();

    for (int i = 0; i < count; i++) {
      Projectile projectile = projectiles.get(i);
      boolean hit = projectile.update(deltaTime);

      if (hit) {
        onProjectileHit(projectile);
      }

      // Piercing projectiles check for hits every tick while still active
      if (projectile.isPiercing() && projectile.isActive()) {
        processPiercingHits(projectile);
      }
    }

    projectiles.removeIf(p -> !p.isActive());
  }

  private void onProjectileHit(Projectile projectile) {
    int baseDamage = projectile.getDamage();
    int ctdp = projectile.getCrownTowerDamagePercent();

    if (projectile.isPositionTargeted()) {
      applySpellDamage(
          projectile.getTeam(),
          projectile.getTargetX(),
          projectile.getTargetY(),
          baseDamage,
          projectile.getAoeRadius(),
          projectile.getEffects(),
          ctdp);
    } else if (projectile.hasAoe()) {
      if (!projectile.isHoming()) {
        // Non-homing: AOE centered at the fixed landing position (where target was when fired)
        applySpellDamage(
            projectile.getTeam(),
            projectile.getFixedTargetX(),
            projectile.getFixedTargetY(),
            baseDamage,
            projectile.getAoeRadius(),
            projectile.getEffects(),
            ctdp);
      } else {
        dealAoeDamage(
            projectile.getSource(),
            projectile.getTarget(),
            baseDamage,
            projectile.getAoeRadius(),
            projectile.getEffects(),
            ctdp);
      }
    } else {
      Entity target = projectile.getTarget();
      int effectiveDamage = DamageUtil.adjustForCrownTower(baseDamage, target, ctdp);
      // Apply pre-damage effects (e.g. Curse -- dying from damage should still trigger)
      applyEffects(target, filterEffects(projectile.getEffects(), false));
      dealDamage(target, effectiveDamage);
      // Apply post-damage effects (e.g. Ice Wizard SLOW, EWiz STUN)
      applyEffects(target, filterEffects(projectile.getEffects(), true));
    }

    // Apply knockback to entities hit by the projectile
    if (projectile.getPushback() > 0) {
      applyKnockback(projectile);
    }

    // Reflect: if a projectile hits a REFLECT target and the source is within reflect radius, zap
    // them
    if (!projectile.isPositionTargeted()) {
      Entity target = projectile.getTarget();
      if (target instanceof Troop reflector) {
        int reflectDmg = AbilitySystem.getReflectDamage(reflector);
        if (reflectDmg > 0 && reflector.getAbility().getData() instanceof ReflectAbility reflect) {
          Entity source = projectile.getSource();
          if (source != null && source.isAlive()) {
            float dist = source.getPosition().distanceTo(reflector.getPosition());
            float effectiveRadius = reflect.reflectRadius() + source.getCollisionRadius();
            if (dist <= effectiveRadius) {
              applyReflectDamage(reflector, source, reflectDmg);
            }
          }
        }
      }
    }

    // Chain lightning: spawn sub-projectiles to nearby enemies
    if (projectile.getChainedHitCount() > 0 && projectile.getChainedHitRadius() > 0) {
      processChainLightning(projectile);
    }

    // Spawn sub-projectile on impact (Log rolling, Firecracker explosion)
    if (projectile.getSpawnProjectile() != null) {
      processSpawnProjectile(projectile);
    }

    // Spawn area effect on impact (Heal Spirit heal zone, etc.)
    if (projectile.getSpawnAreaEffect() != null) {
      spawnAreaEffectOnImpact(projectile);
    }
  }

  /**
   * Chain lightning: find N closest enemies within chainedHitRadius and create sub-projectiles to
   * each. Excludes the primary target.
   */
  private void processChainLightning(Projectile projectile) {
    Entity primaryTarget = projectile.getTarget();
    if (primaryTarget == null) {
      return;
    }

    float hitX = primaryTarget.getPosition().getX();
    float hitY = primaryTarget.getPosition().getY();
    float chainRadius = projectile.getChainedHitRadius();
    int chainCount = projectile.getChainedHitCount();
    Team team = projectile.getTeam();

    List<Entity> candidates = new ArrayList<>();
    for (Entity e : gameState.getAliveEntities()) {
      if (e == primaryTarget || e.getTeam() == team || !e.isTargetable()) {
        continue;
      }
      float distSq = e.getPosition().distanceToSquared(primaryTarget.getPosition());
      float effectiveRadius = chainRadius + e.getCollisionRadius();
      if (distSq <= effectiveRadius * effectiveRadius) {
        candidates.add(e);
      }
    }

    // Sort by squared distance (preserves ordering, avoids sqrt)
    candidates.sort(
        (a, b) -> {
          float da = a.getPosition().distanceToSquared(primaryTarget.getPosition());
          float db = b.getPosition().distanceToSquared(primaryTarget.getPosition());
          return Float.compare(da, db);
        });

    // chainedHitCount includes the primary target, so spawn (count - 1) chain projectiles
    int chainsToSpawn = Math.min(chainCount - 1, candidates.size());
    for (int i = 0; i < chainsToSpawn; i++) {
      Entity chainTarget = candidates.get(i);
      Projectile chain =
          new Projectile(
              projectile.getSource(),
              chainTarget,
              projectile.getDamage(),
              0,
              projectile.getProjectileSpeed(),
              projectile.getEffects(),
              projectile.getCrownTowerDamagePercent());
      chain.setChainOrigin(primaryTarget);
      // Start chain from primary target position so it visually jumps between targets
      chain
          .getPosition()
          .set(primaryTarget.getPosition().getX(), primaryTarget.getPosition().getY());
      gameState.spawnProjectile(chain);
    }
  }

  /** Spawn sub-projectiles on impact (Log rolling projectile, Firecracker explosion). */
  private void processSpawnProjectile(Projectile projectile) {
    ProjectileStats spawnStats = projectile.getSpawnProjectile();
    if (spawnStats == null) {
      return;
    }

    float hitX, hitY;
    if (projectile.isPositionTargeted()) {
      hitX = projectile.getTargetX();
      hitY = projectile.getTargetY();
    } else if (projectile.getTarget() != null) {
      hitX = projectile.getTarget().getPosition().getX();
      hitY = projectile.getTarget().getPosition().getY();
    } else {
      hitX = projectile.getPosition().getX();
      hitY = projectile.getPosition().getY();
    }

    // Calculate travel direction for non-homing spawned projectiles
    float dx = hitX - projectile.getOriginX();
    float dy = hitY - projectile.getOriginY();
    float dist = Vector2.distance(projectile.getOriginX(), projectile.getOriginY(), hitX, hitY);
    float dirX = dist > 0 ? dx / dist : 0;
    float dirY = dist > 0 ? dy / dist : 1;

    float range = spawnStats.getProjectileRange() > 0 ? spawnStats.getProjectileRange() : 10f;
    float targetX = hitX + dirX * range;
    float targetY = hitY + dirY * range;

    Projectile spawned =
        new Projectile(
            projectile.getTeam(),
            hitX,
            hitY,
            targetX,
            targetY,
            spawnStats.getDamage(),
            spawnStats.getRadius(),
            spawnStats.getSpeed(),
            spawnStats.getHitEffects());
    gameState.spawnProjectile(spawned);
  }

  /**
   * Spawns an AreaEffect entity at the projectile's impact point. Used by projectiles that carry a
   * spawnAreaEffect (e.g. Heal Spirit heal zone).
   */
  private void spawnAreaEffectOnImpact(Projectile projectile) {
    AreaEffectStats stats = projectile.getSpawnAreaEffect();

    float centerX, centerY;
    if (projectile.isPositionTargeted()) {
      centerX = projectile.getTargetX();
      centerY = projectile.getTargetY();
    } else if (projectile.getTarget() != null) {
      centerX = projectile.getTarget().getPosition().getX();
      centerY = projectile.getTarget().getPosition().getY();
    } else {
      return;
    }

    AreaEffect effect =
        AreaEffect.builder()
            .name(stats.getName() != null ? stats.getName() : "SpawnedAreaEffect")
            .team(projectile.getTeam())
            .position(new Position(centerX, centerY))
            .stats(stats)
            .remainingLifetime(stats.getLifeDuration())
            .build();

    gameState.spawnEntity(effect);
  }

  /**
   * Creates a projectile for a ranged attack, resolving stats from the combat component's
   * ProjectileStats with fallback to combat-level values.
   */
  private Projectile createAttackProjectile(
      Entity attacker, Entity target, int damage, Combat combat) {
    ProjectileStats stats = combat.getProjectileStats();

    float speed = (stats != null) ? stats.getSpeed() : 0;
    float aoeRadius = (stats != null) ? stats.getRadius() : combat.getAoeRadius();
    List<EffectStats> effects =
        new ArrayList<>((stats != null) ? stats.getHitEffects() : combat.getHitEffects());

    // Merge buffOnDamage as a post-damage effect if no projectile-level post-damage effect exists
    boolean hasPostDamageEffect = false;
    for (EffectStats e : effects) {
      if (e.isApplyAfterDamage()) {
        hasPostDamageEffect = true;
        break;
      }
    }
    if (!hasPostDamageEffect && combat.getBuffOnDamage() != null) {
      EffectStats bod = combat.getBuffOnDamage();
      effects.add(
          EffectStats.builder()
              .type(bod.getType())
              .duration(bod.getDuration())
              .buffName(bod.getBuffName())
              .applyAfterDamage(true)
              .build());
    }

    Projectile projectile =
        new Projectile(
            attacker,
            target,
            damage,
            aoeRadius,
            speed,
            effects,
            combat.getCrownTowerDamagePercent());

    // Wire advanced projectile features from stats
    if (stats != null) {
      projectile.setChainedHitRadius(stats.getChainedHitRadius());
      projectile.setChainedHitCount(stats.getChainedHitCount());
      projectile.setSpawnProjectile(stats.getSpawnProjectile());
      projectile.setPushback(stats.getPushback());
      projectile.setPushbackAll(stats.isPushbackAll());
      projectile.setSpawnAreaEffect(stats.getSpawnAreaEffect());

      // Non-homing projectiles fly to the target's position at fire time
      if (!stats.isHoming()) {
        projectile.setHoming(false);
      }

      // Piercing projectiles travel in a straight line through enemies
      if (stats.isPiercing() && stats.getProjectileRange() > 0) {
        float dx = target.getPosition().getX() - attacker.getPosition().getX();
        float dy = target.getPosition().getY() - attacker.getPosition().getY();
        float dist =
            (float) Math.sqrt(dx * dx + dy * dy);
        float dirX = dist > 0.001f ? dx / dist : 0f;
        float dirY = dist > 0.001f ? dy / dist : 1f;
        projectile.configurePiercing(
            dirX, dirY, stats.getProjectileRange(), stats.isAoeToGround(), stats.isAoeToAir());
      }
    }

    return projectile;
  }

  /**
   * Filters effects by their applyAfterDamage flag. Returns only effects matching the given phase.
   */
  private List<EffectStats> filterEffects(List<EffectStats> effects, boolean afterDamage) {
    if (effects == null || effects.isEmpty()) {
      return Collections.emptyList();
    }
    List<EffectStats> filtered = new ArrayList<>();
    for (EffectStats e : effects) {
      if (e.isApplyAfterDamage() == afterDamage) {
        filtered.add(e);
      }
    }
    return filtered;
  }

  private void dealDamage(Entity target, int damage) {
    if (target == null || !target.isAlive()) {
      return;
    }
    target.getHealth().takeDamage(damage);
  }

  private void applyEffects(Entity target, List<EffectStats> effects) {
    if (target == null || !target.isAlive() || effects == null || effects.isEmpty()) {
      return;
    }

    for (EffectStats stats : effects) {
      // Buildings (MovementType.BUILDING) cannot be Cursed
      if (stats.getType() == StatusEffectType.CURSE
          && target.getMovementType() == MovementType.BUILDING) {
        continue;
      }

      // Pass buffName and spawnSpecies (if any) to the AppliedEffect
      AppliedEffect effect =
          new AppliedEffect(
              stats.getType(), stats.getDuration(), stats.getBuffName(), stats.getSpawnSpecies());
      target.addEffect(effect);

      // Handle Stun/Freeze Reset Logic (Reset attack windup and charge ability)
      if (stats.getType() == StatusEffectType.STUN || stats.getType() == StatusEffectType.FREEZE) {
        Combat combat = target.getCombat();
        if (combat != null) {
          combat.resetAttackState();
        }
        // Reset charge ability state (Prince, Dark Prince, Battle Ram)
        // Reset variable damage state (Inferno Dragon, Inferno Tower)
        if (target instanceof Troop troop) {
          AbilitySystem.consumeCharge(troop);
          AbilitySystem.resetVariableDamage(troop);
        }
      }
    }
  }

  private void dealAoeDamage(
      Entity source, Entity primaryTarget, int damage, float radius, List<EffectStats> effects) {
    dealAoeDamage(source, primaryTarget, damage, radius, effects, 0);
  }

  private void dealAoeDamage(
      Entity source,
      Entity primaryTarget,
      int damage,
      float radius,
      List<EffectStats> effects,
      int crownTowerDamagePercent) {
    if (primaryTarget == null) {
      return;
    }
    applySpellDamage(
        source.getTeam(),
        primaryTarget.getPosition().getX(),
        primaryTarget.getPosition().getY(),
        damage,
        radius,
        effects,
        crownTowerDamagePercent);
  }

  /**
   * Apply spell damage to all targetable enemies within radius of the given center point. Accounts
   * for entity size in the radius check. No crown tower damage reduction.
   */
  public void applySpellDamage(
      Team sourceTeam,
      float centerX,
      float centerY,
      int damage,
      float radius,
      List<EffectStats> effects) {
    applySpellDamage(sourceTeam, centerX, centerY, damage, radius, effects, 0);
  }

  /**
   * Apply spell damage to all targetable enemies within radius of the given center point. Accounts
   * for entity size in the radius check. Applies crown tower damage reduction to Towers.
   */
  public void applySpellDamage(
      Team sourceTeam,
      float centerX,
      float centerY,
      int damage,
      float radius,
      List<EffectStats> effects,
      int crownTowerDamagePercent) {
    if (radius > 0) {
      gameState.recordAoeDamage(centerX, centerY, radius, sourceTeam);
    }

    Team enemyTeam = sourceTeam.opposite();

    for (Entity entity : gameState.getAliveEntities()) {
      if (entity.getTeam() != enemyTeam) {
        continue;
      }
      if (!entity.isTargetable()) {
        continue;
      }

      float distanceSq = entity.getPosition().distanceToSquared(centerX, centerY);

      // Use Collision Radius for spell AOE check (squared distance avoids sqrt)
      float effectiveRadius = radius + entity.getCollisionRadius();
      if (distanceSq <= effectiveRadius * effectiveRadius) {
        int effectiveDamage =
            DamageUtil.adjustForCrownTower(damage, entity, crownTowerDamagePercent);
        // Apply pre-damage effects (e.g. Curse)
        applyEffects(entity, filterEffects(effects, false));
        dealDamage(entity, effectiveDamage);
        // Apply post-damage effects (e.g. slow, stun)
        applyEffects(entity, filterEffects(effects, true));
      }
    }
  }

  /**
   * Applies knockback displacement to entities hit by a projectile. AOE projectiles push all
   * enemies within radius; non-AOE pushes only the direct target. Buildings and entities with
   * ignorePushback are immune.
   */
  private void applyKnockback(Projectile projectile) {
    float pushback = projectile.getPushback();

    // Determine impact center
    float centerX, centerY;
    if (projectile.isPositionTargeted()) {
      centerX = projectile.getTargetX();
      centerY = projectile.getTargetY();
    } else if (projectile.getTarget() != null) {
      centerX = projectile.getTarget().getPosition().getX();
      centerY = projectile.getTarget().getPosition().getY();
    } else {
      return;
    }

    float radius = projectile.getAoeRadius();
    Team projectileTeam = projectile.getTeam();
    Team enemyTeam = projectileTeam.opposite();

    for (Entity entity : gameState.getAliveEntities()) {
      if (entity.getTeam() != enemyTeam || !entity.isTargetable()) {
        continue;
      }
      if (entity.getMovementType() == MovementType.BUILDING) {
        continue;
      }

      Movement movement = entity.getMovement();
      if (movement == null || movement.isIgnorePushback()) {
        continue;
      }

      // AOE: check radius. Non-AOE (pushbackAll=false): only the direct target.
      if (radius > 0 || projectile.isPushbackAll()) {
        float distSq = entity.getPosition().distanceToSquared(centerX, centerY);
        float effectiveRadius = radius + entity.getCollisionRadius();
        if (radius > 0 && distSq > effectiveRadius * effectiveRadius) {
          continue;
        }
      } else if (projectile.getTarget() != entity) {
        continue;
      }

      // Direction from impact center to entity
      float dx = entity.getPosition().getX() - centerX;
      float dy = entity.getPosition().getY() - centerY;
      float dist = (float) Math.sqrt(dx * dx + dy * dy);
      float dirX = dist > 0.001f ? dx / dist : 0f;
      float dirY = dist > 0.001f ? dy / dist : 1f;

      movement.startKnockback(dirX, dirY, pushback, KNOCKBACK_DURATION, KNOCKBACK_MAX_TIME);
    }
  }

  /**
   * Processes per-tick hit detection for a piercing projectile. Checks all alive enemy entities
   * within the projectile's AOE radius, applies damage and effects, and triggers directional
   * knockback for each newly-hit entity.
   */
  private void processPiercingHits(Projectile projectile) {
    Team enemyTeam = projectile.getTeam().opposite();
    float projX = projectile.getPosition().getX();
    float projY = projectile.getPosition().getY();
    float aoeRadius = projectile.getAoeRadius();
    int baseDamage = projectile.getDamage();
    int ctdp = projectile.getCrownTowerDamagePercent();

    for (Entity entity : gameState.getAliveEntities()) {
      if (entity.getTeam() != enemyTeam || !entity.isTargetable()) {
        continue;
      }
      if (projectile.hasHitEntity(entity.getId())) {
        continue;
      }
      if (!canPiercingHit(projectile, entity)) {
        continue;
      }

      float distSq = entity.getPosition().distanceToSquared(projX, projY);
      float effectiveRadius = aoeRadius + entity.getCollisionRadius();
      if (distSq > effectiveRadius * effectiveRadius) {
        continue;
      }

      projectile.recordHitEntity(entity.getId());

      int effectiveDamage = DamageUtil.adjustForCrownTower(baseDamage, entity, ctdp);
      applyEffects(entity, filterEffects(projectile.getEffects(), false));
      dealDamage(entity, effectiveDamage);
      applyEffects(entity, filterEffects(projectile.getEffects(), true));

      // Apply directional knockback along the projectile's travel direction
      if (projectile.getPushback() > 0) {
        applyDirectionalKnockback(projectile, entity);
      }
    }
  }

  /**
   * Returns true if the entity's movement type is compatible with the piercing projectile's
   * aoeToGround/aoeToAir targeting flags.
   */
  private boolean canPiercingHit(Projectile projectile, Entity entity) {
    MovementType mt = entity.getMovementType();
    if (mt == MovementType.AIR) {
      return projectile.isAoeToAir();
    }
    // GROUND and BUILDING are ground-level targets
    return projectile.isAoeToGround();
  }

  /**
   * Applies knockback to an entity in the piercing projectile's travel direction, rather than
   * radially from the impact point. Buildings and ignorePushback entities are immune.
   */
  private void applyDirectionalKnockback(Projectile projectile, Entity entity) {
    if (entity.getMovementType() == MovementType.BUILDING) {
      return;
    }
    Movement movement = entity.getMovement();
    if (movement == null || movement.isIgnorePushback()) {
      return;
    }
    movement.startKnockback(
        projectile.getPiercingDirX(),
        projectile.getPiercingDirY(),
        projectile.getPushback(),
        KNOCKBACK_DURATION,
        KNOCKBACK_MAX_TIME);
  }

  /** Check if an entity can attack a target (used for validation). */
  public boolean canAttack(Entity attacker, Entity target) {
    if (target == null || !target.isTargetable()) {
      return false;
    }
    if (attacker.getTeam() == target.getTeam()) {
      return false;
    }
    // Inactive towers cannot attack
    if (attacker instanceof Tower tower && (!tower.isActive() || tower.isWakingUp())) {
      return false;
    }

    Combat combat = attacker.getCombat();
    if (combat == null) {
      return false;
    }

    float distanceSq = attacker.getPosition().distanceToSquared(target.getPosition());
    float effectiveRange =
        combat.getRange() + attacker.getCollisionRadius() + target.getCollisionRadius();

    if (distanceSq > effectiveRange * effectiveRange) {
      return false;
    }

    // Minimum range check
    if (combat.getMinimumRange() > 0) {
      float effectiveMinRange =
          combat.getMinimumRange() + attacker.getCollisionRadius() + target.getCollisionRadius();
      if (distanceSq < effectiveMinRange * effectiveMinRange) {
        return false;
      }
    }

    return true;
  }
}
