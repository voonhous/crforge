package org.crforge.core.entity.effect.handler;

import org.crforge.core.entity.effect.AreaEffect;

/**
 * Handles one-shot area effects (Zap, Freeze deploy). Applies once on the first tick, then lets the
 * effect expire naturally.
 */
public class OneShotHandler {

  private final TargetApplicationRouter router;

  public OneShotHandler(TargetApplicationRouter router) {
    this.router = router;
  }

  public void process(AreaEffect effect, float deltaTime) {
    if (!effect.isInitialApplied()) {
      router.applyToTargets(effect);
      effect.setInitialApplied(true);
    }
  }
}
