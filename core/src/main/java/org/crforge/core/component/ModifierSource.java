package org.crforge.core.component;

/**
 * Identifies the system or ability that set a modifier on a component. Used to prevent systems from
 * trampling each other's flags -- each system only clears its own source, leaving other sources
 * untouched.
 */
public enum ModifierSource {
  STATUS_EFFECT, // StatusEffectSystem (stun, freeze, slow, rage)
  ABILITY_HOOK, // Hook wind-up/pull/drag
  ABILITY_DASH, // Dash sequence
  ABILITY_CHARGE, // Charge speed boost
  ABILITY_JUMP, // River jump speed boost
  ABILITY_TUNNEL // Underground tunnel travel (Miner)
}
