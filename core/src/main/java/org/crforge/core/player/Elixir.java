package org.crforge.core.player;

import lombok.Getter;
import lombok.Setter;

@Getter
public class Elixir {

  public static final float MAX_ELIXIR = 10.0f;
  // Standard CR generation: 1 Elixir every 2.8 seconds
  public static final float REGEN_PERIOD_NORMAL = 2.8f;

  private float current;
  @Setter private int regenMultiplier;

  public Elixir(float startAmount) {
    this.current = startAmount;
    this.regenMultiplier = 1;
  }

  /**
   * Updates elixir based on delta time.
   *
   * @param deltaTime Time in seconds since last tick
   */
  public void update(float deltaTime) {
    if (current >= MAX_ELIXIR) {
      current = MAX_ELIXIR;
      return;
    }

    // Calculate rate: base period divided by multiplier (1x normal, 2x overtime, 3x triple)
    float period = REGEN_PERIOD_NORMAL / regenMultiplier;
    float rate = 1.0f / period;

    current += rate * deltaTime;

    if (current > MAX_ELIXIR) {
      current = MAX_ELIXIR;
    }
  }

  /** Grants elixir (e.g. from Elixir Golem death). Caps at MAX_ELIXIR. */
  public void add(float amount) {
    current = Math.min(current + amount, MAX_ELIXIR);
  }

  /** Checks if the player has enough elixir. */
  public boolean has(int cost) {
    return current >= cost;
  }

  /**
   * Spends elixir if sufficient funds are available.
   *
   * @return true if spent successfully, false otherwise
   */
  public boolean spend(int cost) {
    if (has(cost)) {
      current -= cost;
      return true;
    }
    return false;
  }

  /** Returns the current elixir as a usable integer (floor). */
  public int getFloor() {
    return (int) Math.floor(current);
  }
}
