package org.crforge.core.ability.handler;

import org.crforge.core.ability.AbilityComponent;
import org.crforge.core.entity.base.Entity;

/** Strategy interface for ability-specific update logic. */
public interface AbilityHandler {
  void update(Entity entity, AbilityComponent ability, float deltaTime);
}
