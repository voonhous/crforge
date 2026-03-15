package org.crforge.core.combat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.ability.AbilitySystem;
import org.crforge.core.ability.ReflectAbility;
import org.crforge.core.card.AreaEffectStats;
import org.crforge.core.card.EffectStats;
import org.crforge.core.component.Combat;
import org.crforge.core.component.Position;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.effect.AreaEffect;
import org.crforge.core.entity.projectile.Projectile;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;
import org.crforge.core.player.Team;

/** Handles attack execution, damage dealing, and delegates projectile management. */
public class CombatSystem {

  private final GameState gameState;
  private final AoeDamageService aoeDamageService;
  private final ProjectileSystem projectileSystem;

  public CombatSystem(
      GameState gameState, AoeDamageService aoeDamageService, ProjectileSystem projectileSystem) {
    this.gameState = gameState;
    this.aoeDamageService = aoeDamageService;
    this.projectileSystem = projectileSystem;
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
    projectileSystem.update(deltaTime);
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

    // Troops and buildings cannot attack while deploying
    if (entity instanceof Troop troop && troop.isDeploying()) {
      return;
    }
    if (entity instanceof Building building && building.isDeploying()) {
      return;
    }

    // Entities cannot attack while being knocked back; reset any in-progress attack
    if (entity.getMovement() != null && entity.getMovement().isKnockedBack()) {
      if (combat.isAttacking()) {
        combat.setAttacking(false);
        combat.setCurrentWindup(0);
      }
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
      // Attack dash: lunge toward target when attack starts (e.g. Bat)
      if (entity instanceof Troop troop
          && combat.getAttackDashTime() > 0
          && troop.getMovement() != null) {
        float dx = target.getPosition().getX() - entity.getPosition().getX();
        float dy = target.getPosition().getY() - entity.getPosition().getY();
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist > 0.001f) {
          float dirX = dx / dist;
          float dirY = dy / dist;
          troop
              .getMovement()
              .startAttackDash(
                  dirX, dirY, troop.getMovement().getSpeed(), combat.getAttackDashTime());
        }
      }
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
        combat.getDamageOverride() > 0 ? combat.getDamageOverride() : combat.getEffectiveDamage();

    // Charge ability: override damage for this attack if charged
    if (attacker instanceof Troop troop) {
      baseDamage = AbilitySystem.getChargeDamage(troop.getAbility(), baseDamage);
    }

    if (combat.isRanged()
        && combat.getMultipleProjectiles() > 1
        && combat.getProjectileStats() != null
        && combat.getProjectileStats().getScatter() != null) {
      projectileSystem.fireScatterProjectiles(attacker, target, baseDamage, combat);
    } else if (combat.isRanged()) {
      Projectile projectile =
          projectileSystem.createAttackProjectile(attacker, target, baseDamage, combat);
      gameState.spawnProjectile(projectile);

      // Lock combat while returning projectile (boomerang) is in flight
      if (projectile.isReturningEnabled()) {
        combat.setCombatDisabled(
            org.crforge.core.component.ModifierSource.RETURNING_PROJECTILE, true);
      }
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
        aoeDamageService.applyEffects(target, combat.getHitEffects());
        aoeDamageService.dealDamage(target, effectiveDamage);
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

    // Area effect on hit: spawn a healing/buff zone centered on the attacker (e.g. BattleHealer)
    if (combat.getAreaEffectOnHit() != null) {
      spawnAreaEffectOnAttack(attacker, combat.getAreaEffectOnHit());
    }

    // Attack recoil: push the attacker backward when they fire (e.g. Firecracker)
    if (combat.getAttackPushBack() > 0f && attacker.getMovement() != null) {
      float dx = target.getPosition().getX() - attacker.getPosition().getX();
      float dy = target.getPosition().getY() - attacker.getPosition().getY();
      float dist = (float) Math.sqrt(dx * dx + dy * dy);
      if (dist > 0.001f) {
        float recoilDirX = -dx / dist;
        float recoilDirY = -dy / dist;
        attacker
            .getMovement()
            .startKnockback(
                recoilDirX,
                recoilDirY,
                combat.getAttackPushBack(),
                ProjectileSystem.KNOCKBACK_DURATION,
                ProjectileSystem.KNOCKBACK_MAX_TIME);
      }
    }

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
      if (e instanceof Troop t && t.isInvisible()) {
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
        Projectile projectile =
            projectileSystem.createAttackProjectile(attacker, extraTarget, baseDamage, combat);
        gameState.spawnProjectile(projectile);
      } else {
        int effectiveDamage =
            DamageUtil.adjustForCrownTower(
                baseDamage, extraTarget, combat.getCrownTowerDamagePercent());
        aoeDamageService.applyEffects(extraTarget, combat.getHitEffects());
        aoeDamageService.dealDamage(extraTarget, effectiveDamage);
        applyBuffOnDamage(combat, extraTarget);
      }
    }

    // If we couldn't find enough unique targets, fire remaining shots at the primary target
    int remaining = extraTargets - fired;
    for (int i = 0; i < remaining; i++) {
      if (combat.isRanged()) {
        Projectile projectile =
            projectileSystem.createAttackProjectile(attacker, primaryTarget, baseDamage, combat);
        gameState.spawnProjectile(projectile);
      } else {
        int effectiveDamage =
            DamageUtil.adjustForCrownTower(
                baseDamage, primaryTarget, combat.getCrownTowerDamagePercent());
        aoeDamageService.applyEffects(primaryTarget, combat.getHitEffects());
        aoeDamageService.dealDamage(primaryTarget, effectiveDamage);
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
    aoeDamageService.applyEffects(target, List.of(buff));
  }

  private void applyReflectDamage(Troop reflector, Entity attacker, int reflectDamage) {
    ReflectAbility reflect = (ReflectAbility) reflector.getAbility().getData();
    int effectiveDamage =
        DamageUtil.adjustForCrownTower(
            reflectDamage, attacker, reflect.reflectCrownTowerDamagePercent());
    aoeDamageService.dealDamage(attacker, effectiveDamage);

    // Apply reflect buff (e.g. ZapFreeze stun) to attacker
    if (reflect.reflectBuff() != null && reflect.reflectBuffDuration() > 0) {
      EffectStats reflectEffect =
          EffectStats.builder()
              .type(reflect.reflectBuff())
              .duration(reflect.reflectBuffDuration())
              .buffName(reflect.reflectBuffName())
              .build();
      aoeDamageService.applyEffects(attacker, List.of(reflectEffect));
    }
  }

  /**
   * Spawns an AreaEffect entity centered on the attacker when they land an attack. Used by units
   * that trigger area effects on hit (e.g. BattleHealer heal zone).
   */
  private void spawnAreaEffectOnAttack(Entity attacker, AreaEffectStats stats) {
    AreaEffect effect =
        AreaEffect.builder()
            .name(stats.getName() != null ? stats.getName() : "AttackAreaEffect")
            .team(attacker.getTeam())
            .position(new Position(attacker.getPosition().getX(), attacker.getPosition().getY()))
            .stats(stats)
            .remainingLifetime(stats.getLifeDuration())
            .build();
    gameState.spawnEntity(effect);
  }

  private void dealAoeDamage(
      Entity source, Entity primaryTarget, int damage, float radius, List<EffectStats> effects) {
    if (primaryTarget == null) {
      return;
    }
    aoeDamageService.applySpellDamage(
        source.getTeam(),
        primaryTarget.getPosition().getX(),
        primaryTarget.getPosition().getY(),
        damage,
        radius,
        effects,
        0);
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
