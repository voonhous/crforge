package org.crforge.core.engine;

import org.crforge.core.player.Team;

/**
 * Records an instantaneous AOE damage burst for visualization. Captured in
 * CombatSystem.applySpellDamage() and consumed by the renderer. Center and radius are game units.
 */
public record AoeDamageEvent(int centerX, int centerY, int radius, Team sourceTeam) {}
