package org.crforge.core.ability;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.ability.handler.AbilityHandler;
import org.crforge.core.ability.handler.BuffAllyHandler;
import org.crforge.core.ability.handler.ChargeHandler;
import org.crforge.core.ability.handler.DashHandler;
import org.crforge.core.ability.handler.HidingHandler;
import org.crforge.core.ability.handler.HookHandler;
import org.crforge.core.ability.handler.RangedAttackHandler;
import org.crforge.core.ability.handler.ReflectHandler;
import org.crforge.core.ability.handler.StealthHandler;
import org.crforge.core.ability.handler.TunnelHandler;
import org.crforge.core.ability.handler.VariableDamageHandler;
import org.crforge.core.engine.GameState;
import org.crforge.core.entity.base.Entity;
import org.crforge.core.entity.structure.Building;
import org.crforge.core.entity.unit.Troop;

/**
 * Processes ability updates each tick. Dispatches to per-ability-type handlers.
 *
 * <p>Should run before CombatSystem in the tick loop so damage modifications take effect on the
 * current tick's attacks.
 */
public class AbilitySystem {

  private final GameState gameState;
  private final Map<AbilityType, AbilityHandler> handlers;
  private final TunnelHandler tunnelHandler;
  private final BuffAllyHandler buffAllyHandler;

  public AbilitySystem(GameState gameState) {
    this.gameState = gameState;

    tunnelHandler = new TunnelHandler();
    buffAllyHandler = new BuffAllyHandler(gameState);

    handlers = new EnumMap<>(AbilityType.class);
    handlers.put(AbilityType.CHARGE, new ChargeHandler());
    handlers.put(AbilityType.VARIABLE_DAMAGE, new VariableDamageHandler());
    handlers.put(AbilityType.DASH, new DashHandler(gameState));
    handlers.put(AbilityType.HOOK, new HookHandler(gameState));
    handlers.put(AbilityType.REFLECT, new ReflectHandler());
    handlers.put(AbilityType.TUNNEL, tunnelHandler);
    handlers.put(AbilityType.STEALTH, new StealthHandler());
    handlers.put(AbilityType.HIDING, new HidingHandler());
    handlers.put(AbilityType.BUFF_ALLY, buffAllyHandler);
    handlers.put(AbilityType.RANGED_ATTACK, new RangedAttackHandler(gameState));
  }

  public void setTunnelMorphHandler(TunnelMorphHandler tunnelMorphHandler) {
    tunnelHandler.setTunnelMorphHandler(tunnelMorphHandler);
  }

  public void update(float deltaTime) {
    List<Entity> aliveEntities = gameState.getAliveEntities();
    for (Entity entity : aliveEntities) {
      AbilityComponent ability = null;
      AbilityType type = null;

      if (entity instanceof Troop troop) {
        ability = troop.getAbility();
        if (ability == null) {
          continue;
        }
        type = ability.getData().type();
        // Tunnel ability runs even while deploying; other abilities wait for deploy to finish
        if (troop.isDeploying() && type != AbilityType.TUNNEL) {
          continue;
        }
      } else if (entity instanceof Building building) {
        ability = building.getAbility();
        if (ability == null) {
          continue;
        }
        type = ability.getData().type();
        // Buildings cannot attack while deploying, so skip ability updates too
        if (building.isDeploying()) {
          continue;
        }
      }

      if (ability != null) {
        AbilityHandler handler = handlers.get(type);
        if (handler != null) {
          handler.update(entity, ability, deltaTime);
        }
      }
    }

    // Tick giant buff state on all alive troops (delay timers, persist timers, expiry)
    buffAllyHandler.tickGiantBuffs(deltaTime);
  }
}
