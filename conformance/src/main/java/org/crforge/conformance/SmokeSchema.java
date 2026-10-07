package org.crforge.conformance;

import java.util.Arrays;

/**
 * The smoke observation schemas a run can be made in. A run names its schema and observation scope
 * in its identity; any other pair is refused, so a reference of an unknown contract is never
 * answered with a trace of another.
 */
public enum SmokeSchema {

  /**
   * The exact-horizon contract: tick 0 and then exactly {@code ticks} steps. A battle that stops
   * stepping before the horizon cannot be represented and makes the run invalid.
   */
  V1(
      "crforge.native-battle-smoke.v1",
      "tick/rng/live-entity-position/character-hp-state/hand-elixir",
      false),

  /**
   * The terminal-aware contract: every observation also carries the battle's own stop predicate
   * ({@code stopped}), and the run steps until the last observation is stopped or the requested
   * horizon is reached, whichever is first. The manifest records the steps actually executed and
   * why the run ended.
   */
  V2(
      "crforge.native-battle-smoke.v2",
      "tick/rng/live-entity-position/character-hp-state/hand-elixir/stopped",
      true);

  private final String id;
  private final String observationScope;
  private final boolean terminal;

  SmokeSchema(String id, String observationScope, boolean terminal) {
    this.id = id;
    this.observationScope = observationScope;
    this.terminal = terminal;
  }

  /** The schema's name, as a manifest's {@code schema} field gives it. */
  public String id() {
    return id;
  }

  /** The observation scope that goes with the schema, as a manifest's field gives it. */
  public String observationScope() {
    return observationScope;
  }

  /** Whether the run stops at the battle's own stop state and observes the stop predicate. */
  public boolean terminal() {
    return terminal;
  }

  /**
   * The schema an identity names.
   *
   * @param id the identity's {@code schema}
   * @param observationScope the identity's {@code observation_scope}
   * @throws IllegalArgumentException when the pair is not a supported schema and its scope
   */
  public static SmokeSchema of(String id, String observationScope) {
    return Arrays.stream(values())
        .filter(s -> s.id.equals(id) && s.observationScope.equals(observationScope))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "not a supported smoke schema and observation scope: "
                        + id
                        + " / "
                        + observationScope));
  }
}
