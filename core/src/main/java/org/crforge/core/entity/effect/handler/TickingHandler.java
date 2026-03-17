package org.crforge.core.entity.effect.handler;

import org.crforge.core.entity.effect.AreaEffect;

/**
 * Handles ticking area effects (Poison, Earthquake). Accumulates time and applies damage/buffs when
 * the hitSpeed threshold is reached.
 */
public class TickingHandler {

  private final TargetApplicationRouter router;

  public TickingHandler(TargetApplicationRouter router) {
    this.router = router;
  }

  public void process(AreaEffect effect, float deltaTime) {
    float acc = effect.getTickAccumulator() + deltaTime;

    while (acc >= effect.getStats().getHitSpeed()) {
      acc -= effect.getStats().getHitSpeed();
      router.applyToTargets(effect);
    }

    effect.setTickAccumulator(acc);
  }
}
