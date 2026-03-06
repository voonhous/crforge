package org.crforge.core.ability;

/**
 * A single stage in a variable damage (inferno) progression.
 *
 * @param damage the damage dealt during this stage
 * @param durationSeconds how long this stage lasts (0 for the initial stage)
 */
public record VariableDamageStage(int damage, float durationSeconds) {}
