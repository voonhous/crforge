package org.crforge.core.component;

import lombok.Getter;
import lombok.ToString;

@Getter
@ToString
public class Health {

  private final int max;
  private int current;
  private int shield;
  private final int shieldMax;

  public Health(int max) {
    this.max = max;
    this.current = max;
    this.shield = 0;
    this.shieldMax = 0;
  }

  public Health(int max, int shield) {
    this.max = max;
    this.current = max;
    this.shield = shield;
    this.shieldMax = shield;
  }

  public boolean isAlive() {
    return current > 0;
  }

  public boolean isDead() {
    return current <= 0;
  }

  public float percentage() {
    return (float) current / max;
  }

  public int takeDamage(int damage) {
    if (damage <= 0) {
      return 0;
    }

    int actualDamage;

    // Shield absorbs damage first -- no overflow into health.
    // The hit that breaks the shield is fully consumed by it.
    if (shield > 0) {
      shield = Math.max(0, shield - damage);
      return 0;
    }

    // No shield remaining, damage goes to health
    actualDamage = Math.min(damage, current);
    current -= actualDamage;
    return actualDamage;
  }

  public void heal(int amount) {
    if (amount <= 0) {
      return;
    }
    current = Math.min(current + amount, max);
  }

  public void addShield(int amount) {
    if (amount > 0) {
      shield += amount;
    }
  }

  public void setShield(int amount) {
    shield = Math.max(0, amount);
  }

  /**
   * Instantly kills this entity by setting HP to 0. Bypasses shield absorption (shields are
   * irrelevant for self-destruct).
   */
  public void kill() {
    current = 0;
  }

  public Health copy() {
    Health copy = new Health(max, shieldMax);
    copy.current = this.current;
    copy.shield = this.shield;
    return copy;
  }
}
