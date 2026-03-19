package org.crforge.core.engine;

import java.util.List;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.structure.Tower;
import org.crforge.core.entity.unit.Troop;

/**
 * Handles per-entity timer logic each tick: deploy countdowns, building lifetime decay, troop
 * lifetime, grounded timer, and tower activation. CES architecture: all per-tick timer logic lives
 * here rather than in entity update() methods.
 */
public class EntityTimerSystem {

  /** Update all entity timers. Called once per tick (step 8 in GameEngine). */
  public void update(List<Entity> aliveEntities, float deltaTime) {
    for (Entity entity : aliveEntities) {
      if (entity instanceof Tower tower) {
        updateBuilding(tower, deltaTime);
        updateTowerActivation(tower, deltaTime);
      } else if (entity instanceof Building building) {
        updateBuilding(building, deltaTime);
      } else if (entity instanceof Troop troop) {
        updateTroop(troop, deltaTime);
      }
    }
  }

  private void updateTroop(Troop troop, float deltaTime) {
    if (troop.isDead()) {
      return;
    }

    // Handle deploy timer
    if (troop.getDeployTimer() > 0) {
      troop.setDeployTimer(troop.getDeployTimer() - deltaTime);
      if (troop.getDeployTimer() <= 0) {
        troop.setDeployTimer(0);
      }
      return;
    }

    // Lifetime countdown: kill troop when timer expires (e.g. kamikaze form's 20s lifeTime)
    if (troop.getLifeTimer() > 0) {
      troop.setLifeTimer(troop.getLifeTimer() - deltaTime);
      if (troop.getLifeTimer() <= 0) {
        troop.getHealth().kill();
        return;
      }
    }

    // Decrement grounded timer (Vines air-to-ground)
    if (troop.getGroundedTimer() > 0) {
      troop.setGroundedTimer(troop.getGroundedTimer() - deltaTime);
    }
  }

  private void updateBuilding(Building building, float deltaTime) {
    if (building.isDead()) {
      return;
    }

    // Handle deploy timer
    // While deploying, buildings are targetable but do not decay or attack
    if (building.isDeploying()) {
      building.setDeployTimer(building.getDeployTimer() - deltaTime);
      if (building.getDeployTimer() <= 0) {
        building.setDeployTimer(0);
      }
      return;
    }

    // Reduce lifetime and apply health decay
    if (building.hasLifetime()) {
      building.setRemainingLifetime(building.getRemainingLifetime() - deltaTime);

      // Calculate decay
      // Rate: MaxHP / TotalLifetime (damage per second)
      float decayRate = (float) building.getHealth().getMax() / building.getLifetime();
      float decayAmount = decayRate * deltaTime;

      building.setDecayAccumulator(building.getDecayAccumulator() + decayAmount);

      if (building.getDecayAccumulator() >= 1.0f) {
        int damage = (int) building.getDecayAccumulator();
        building.getHealth().takeDamage(damage);
        building.setDecayAccumulator(building.getDecayAccumulator() - damage);
      }

      // Check lifetime expiry
      // If lifetime expires, we force kill by depleting health.
      // We do NOT call markDead() here directly, because GameState.processDeaths()
      // handles the transition from Health<=0 to Dead=true and triggers onDeath events.
      if (building.getRemainingLifetime() <= 0) {
        building.setRemainingLifetime(0);
        if (building.getHealth().isAlive()) {
          building.getHealth().takeDamage(building.getHealth().getCurrent());
        }
      }
    }
  }

  private void updateTowerActivation(Tower tower, float deltaTime) {
    // Crown Tower activates when first damaged (health < max)
    if (!tower.isActive() && tower.getHealth().getCurrent() < tower.getHealth().getMax()) {
      tower.activate();
    }

    // Count down activation timer (waking up period)
    if (tower.getActivationTimer() > 0) {
      tower.setActivationTimer(tower.getActivationTimer() - deltaTime);
    }
  }
}
