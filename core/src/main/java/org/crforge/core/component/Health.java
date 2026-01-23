package org.crforge.core.component;

import lombok.Getter;
import lombok.ToString;

@Getter
@ToString
public class Health {

  private final int max;
  private int current;
  private int shield;

  public Health(int max) {
    this.max = max;
    this.current = max;
    this.shield = 0;
  }

  public Health(int max, int shield) {
    this.max = max;
    this.current = max;
    this.shield = shield;
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

    // Shield absorbs damage first
    if (shield > 0) {
      if (damage <= shield) {
        shield -= damage;
        return 0;
      } else {
        damage -= shield;
        shield = 0;
      }
    }

    // Apply remaining damage to health
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

  public Health copy() {
    Health copy = new Health(max, shield);
    copy.current = this.current;
    return copy;
  }
}
