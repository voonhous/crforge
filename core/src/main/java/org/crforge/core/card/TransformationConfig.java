package org.crforge.core.card;

/**
 * Configuration for HP-threshold transformation (e.g. GoblinDemolisher -> kamikaze form at 50% HP).
 *
 * @param transformStats the TroopStats of the form to transform into
 * @param healthPercent HP percentage threshold (0-100) at which the transformation triggers
 */
public record TransformationConfig(TroopStats transformStats, int healthPercent) {}
