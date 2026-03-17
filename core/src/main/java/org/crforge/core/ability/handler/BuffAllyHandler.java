package org.crforge.core.ability.handler;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.ability.BuffAllyAbility;
import org.crforge.core.ability.DamageMultiplierEntry;
import org.crforge.core.ability.GiantBuffState;
import org.crforge.core.component.Combat;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;

/**
 * Handles the BUFF_ALLY ability (GiantBuffer). Manages the buff cycle timer, target selection, buff
 * application, and per-tick giant buff state updates.
 */
public class BuffAllyHandler implements AbilityHandler {

  private final GameState gameState;

  public BuffAllyHandler(GameState gameState) {
    this.gameState = gameState;
  }

  @Override
  public void update(Entity entity, AbilityComponent ability, float deltaTime) {
    if (!(entity instanceof Troop troop)) {
      return;
    }

    BuffAllyAbility data = (BuffAllyAbility) ability.getData();

    // Tick down the timer (action delay on first cycle, cooldown on subsequent)
    ability.setBuffAllyTimer(ability.getBuffAllyTimer() - deltaTime);
    if (ability.getBuffAllyTimer() > 0) {
      return;
    }

    // Timer expired -- fire buff cycle
    ability.setBuffAllyActionDelayDone(true);
    ability.setBuffAllyTimer(data.cooldown());

    // Find and buff closest friendlies
    List<Troop> targets = findBuffAllyTargets(troop, data);
    for (Troop target : targets) {
      applyGiantBuff(troop, target, ability, data);
    }
  }

  /**
   * Ticks all GiantBuff states on alive troops: activates after delay, tracks source death, expires
   * after persist duration. Called by AbilitySystem after the entity loop.
   */
  public void tickGiantBuffs(float deltaTime) {
    for (Entity entity : gameState.getAliveEntities()) {
      if (!(entity instanceof Troop troop)) {
        continue;
      }
      GiantBuffState buff = troop.getGiantBuff();
      if (buff == null) {
        continue;
      }

      // Tick activation delay
      if (!buff.isActive()) {
        buff.setDelayTimer(buff.getDelayTimer() - deltaTime);
        if (buff.getDelayTimer() <= 0) {
          buff.setActive(true);
        }
        continue;
      }

      // Track source death
      if (!buff.isSourceDead()) {
        Entity source = findEntityById(buff.getSourceEntityId());
        if (source == null || !source.isAlive()) {
          buff.setSourceDead(true);
        }
      }

      // Tick persist timer after source death
      if (buff.isSourceDead()) {
        buff.setPersistTimer(buff.getPersistTimer() + deltaTime);
        if (buff.isExpired()) {
          troop.setGiantBuff(null);
        }
      }
    }
  }

  /**
   * Finds the closest friendly troops eligible for the GiantBuffer's buff. Excludes self, attached
   * troops, and dead troops.
   */
  private List<Troop> findBuffAllyTargets(Troop source, BuffAllyAbility data) {
    List<Troop> candidates = new ArrayList<>();
    for (Entity entity : gameState.getAliveEntities()) {
      if (entity == source) {
        continue;
      }
      if (entity.getTeam() != source.getTeam()) {
        continue;
      }
      if (!(entity instanceof Troop troop)) {
        continue;
      }
      if (troop.isAttached()) {
        continue;
      }
      if (!troop.isAlive()) {
        continue;
      }

      float dist = source.getPosition().distanceTo(troop.getPosition());
      if (dist <= data.searchRange()) {
        candidates.add(troop);
      }
    }

    // Sort by distance (ascending)
    candidates.sort(
        (a, b) -> {
          float da = source.getPosition().distanceToSquared(a.getPosition());
          float db = source.getPosition().distanceToSquared(b.getPosition());
          return Float.compare(da, db);
        });

    return candidates.subList(0, Math.min(data.maxTargets(), candidates.size()));
  }

  /**
   * Applies or refreshes a GiantBuff on a target troop. Singleton: re-application resets the
   * counter rather than stacking.
   */
  private void applyGiantBuff(
      Troop source, Troop target, AbilityComponent ability, BuffAllyAbility data) {
    GiantBuffState existing = target.getGiantBuff();
    if (existing != null) {
      // Refresh existing buff (singleton -- reset counter and delay)
      existing.refresh(data.buffDelay());
      return;
    }

    GiantBuffState buff =
        new GiantBuffState(
            ability.getScaledAddedDamage(),
            ability.getScaledAddedCrownTowerDamage(),
            data.attackAmount(),
            data.damageMultipliers(),
            source.getId(),
            data.buffDelay(),
            data.persistAfterDeath());
    target.setGiantBuff(buff);
  }

  /**
   * Called by CombatSystem when a buffed troop attacks. Increments the attack counter and returns
   * the bonus damage if a proc occurs (every Nth attack). Returns 0 if no proc or no active buff.
   *
   * @param attacker the troop attacking
   * @param target the entity being attacked
   * @param combat the attacker's combat component (for projectile stats lookup)
   * @return bonus damage to add, or 0
   */
  public static int processGiantBuffHit(Troop attacker, Entity target, Combat combat) {
    GiantBuffState buff = attacker.getGiantBuff();
    if (buff == null || !buff.isActive()) {
      return 0;
    }

    buff.setAttackCounter(buff.getAttackCounter() + 1);
    if (buff.getAttackCounter() < buff.getAttackAmount()) {
      return 0;
    }

    // Proc! Reset counter
    buff.setAttackCounter(0);

    // Determine base bonus: crown tower uses addedCrownTowerDamage
    int baseBonus;
    if (target instanceof Tower) {
      baseBonus = buff.getAddedCrownTowerDamage();
    } else {
      baseBonus = buff.getAddedDamage();
    }

    // Look up damage multiplier override
    int multiplier = lookupDamageMultiplier(attacker, combat, buff.getDamageMultipliers());

    return baseBonus * multiplier / 100;
  }

  /**
   * Looks up the damage multiplier for a buffed attacker. Checks attacker name first, then
   * projectile name against the multiplier table. Default (no match) = 100 (1x).
   */
  private static int lookupDamageMultiplier(
      Troop attacker, Combat combat, List<DamageMultiplierEntry> multipliers) {
    if (multipliers == null || multipliers.isEmpty()) {
      return 100;
    }

    // Check attacker unit name
    String attackerName = attacker.getName();
    for (DamageMultiplierEntry entry : multipliers) {
      if (entry.name().equals(attackerName)) {
        return entry.multiplier();
      }
    }

    // Check projectile name
    if (combat != null && combat.getProjectileStats() != null) {
      String projName = combat.getProjectileStats().getName();
      if (projName != null) {
        for (DamageMultiplierEntry entry : multipliers) {
          if (entry.name().equals(projName)) {
            return entry.multiplier();
          }
        }
      }
    }

    // No match -- default 1x
    return 100;
  }

  private Entity findEntityById(long id) {
    for (Entity entity : gameState.getAliveEntities()) {
      if (entity.getId() == id) {
        return entity;
      }
    }
    return null;
  }
}
