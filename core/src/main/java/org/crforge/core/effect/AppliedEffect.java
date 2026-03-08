package org.crforge.core.effect;

import org.crforge.core.card.TroopStats;

/**
 * Represents an instance of an effect currently affecting an entity.
 */
public class AppliedEffect {

  private final StatusEffectType type;
  private final float intensity; // e.g., 0.5 for a 50% slow
  private final TroopStats spawnSpecies; // For Curse effects
  private final String buffName; // Original buff name from parsed_buffs.json (e.g. "Poison", "IceWizardCold")
  private float remainingDuration;

  /**
   * Constructor with buff name for data-driven multiplier resolution.
   */
  public AppliedEffect(StatusEffectType type, float duration, String buffName) {
    this(type, duration, 0f, null, buffName);
  }

  /**
   * Constructor with buff name and spawn species (for Curse effects with data-driven params).
   */
  public AppliedEffect(StatusEffectType type, float duration, String buffName,
      TroopStats spawnSpecies) {
    this(type, duration, 0f, spawnSpecies, buffName);
  }

  /**
   * Full constructor with all fields.
   */
  public AppliedEffect(StatusEffectType type, float duration, float intensity,
      TroopStats spawnSpecies, String buffName) {
    this.type = type;
    this.remainingDuration = duration;
    this.intensity = intensity;
    this.spawnSpecies = spawnSpecies;
    this.buffName = buffName;
  }

  public void update(float deltaTime) {
    remainingDuration -= deltaTime;
  }

  public boolean isExpired() {
    return remainingDuration <= 0;
  }

  public StatusEffectType getType() {
    return type;
  }

  public float getIntensity() {
    return intensity;
  }

  public float getRemainingDuration() {
    return remainingDuration;
  }

  public TroopStats getSpawnSpecies() {
    return spawnSpecies;
  }

  public String getBuffName() {
    return buffName;
  }

  /**
   * Returns the BuffDefinition for this effect's buff name, or null if not registered.
   */
  public BuffDefinition getBuffDefinition() {
    return BuffRegistry.get(buffName);
  }

  /**
   * Resets the duration if a fresh application of the same effect occurs.
   */
  public void refresh(float duration) {
    this.remainingDuration = Math.max(this.remainingDuration, duration);
  }
}
