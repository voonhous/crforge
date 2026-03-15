package org.crforge.core.card;

/**
 * A single entry in a spawn sequence, describing when and where to spawn a character relative to
 * the area effect center. Used by Graveyard to define its 13-skeleton spawn pattern.
 *
 * @param spawnDelay delay in seconds from area effect creation before this spawn fires
 * @param relativeX X offset in tiles relative to the area effect center
 * @param relativeY Y offset in tiles relative to the area effect center
 */
public record SpawnSequenceEntry(float spawnDelay, float relativeX, float relativeY) {}
