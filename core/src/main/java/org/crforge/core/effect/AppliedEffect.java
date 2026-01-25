package org.crforge.core.effect;

/**
 * Represents an instance of an effect currently affecting an entity.
 */
public class AppliedEffect {

  private final StatusEffectType type;
  private float remainingDuration;
  private final float intensity; // e.g., 0.5 for a 50% slow

  public AppliedEffect(StatusEffectType type, float duration, float intensity) {
    this.type = type;
    this.remainingDuration = duration;
    this.intensity = intensity;
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

  /**
   * Resets the duration if a fresh application of the same effect occurs.
   */
  public void refresh(float duration) {
    this.remainingDuration = Math.max(this.remainingDuration, duration);
  }
}
