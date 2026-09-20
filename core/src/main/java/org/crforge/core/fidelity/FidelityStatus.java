package org.crforge.core.fidelity;

/**
 * How well a simulation class's behaviour is known to match the standard game.
 *
 * <p>This records confidence, not code quality. A {@link #GUESS} can be well written, well tested
 * against itself and still wrong about what the real game does.
 */
public enum FidelityStatus {

  /**
   * Behaviour is established and the class reproduces it. Reserve this for classes covered by
   * fixtures that would fail if the behaviour regressed, not for code that merely looks right.
   */
  TRACED,

  /**
   * Some of the class is established and some is still inferred. The note should say which parts
   * are which, so the remaining work is visible without reading the class.
   */
  PARTIAL,

  /**
   * Behaviour is inferred from observation, from a reference port, or from reasoning about what the
   * game appears to do. This is the honest default for anything not yet established.
   */
  GUESS,

  /**
   * No {@link Fidelity} annotation is present. The ledger assigns this when reporting; it is not
   * meant to be written by hand, and {@code FidelityLedgerTest} fails if a simulation class is left
   * in this state.
   */
  UNDECLARED
}
