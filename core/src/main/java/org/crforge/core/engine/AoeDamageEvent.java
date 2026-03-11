package org.crforge.core.engine;

import org.crforge.core.player.Team;

/**
 * Records an instantaneous AOE damage burst for visualization.
 * Captured in CombatSystem.applySpellDamage() and consumed by the renderer.
 */
public record AoeDamageEvent(float centerX, float centerY, float radius, Team sourceTeam) {}
