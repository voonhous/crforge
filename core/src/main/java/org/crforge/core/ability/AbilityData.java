package org.crforge.core.ability;

/**
 * Sealed interface for ability definitions loaded from units.json. Each ability type is a record
 * with only the fields it needs, making illegal states unrepresentable. Stored in TroopStats and
 * copied to AbilityComponent at deployment.
 */
public sealed interface AbilityData
    permits ChargeAbility,
        VariableDamageAbility,
        DashAbility,
        HookAbility,
        ReflectAbility,
        TunnelAbility,
        StealthAbility {
  AbilityType type();
}
