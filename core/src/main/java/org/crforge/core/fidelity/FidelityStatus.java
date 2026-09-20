package org.crforge.core.fidelity;

/**
 * How well a class's behaviour is known to match the standard game.
 *
 * <p>This records confidence, not code quality. A {@link #GUESS} can be well written, well tested
 * against itself and still wrong about what the real game does.
 *
 * <p>{@link #GUESS} is the default and needs no annotation: anything unproven is a guess, and that
 * is the safe assumption to start from. Only departures from it are worth writing down.
 */
public enum FidelityStatus {

  /**
   * Behaviour is established and the class reproduces it. Reserve this for classes covered by
   * fixtures that would fail if the behaviour regressed, not for code that merely looks right.
   */
  TRACED,

  /**
   * Some of the class is established and some is still inferred. The note says which parts are
   * which, so the remaining work is visible without reading the class.
   */
  PARTIAL,

  /**
   * Behaviour is inferred from observation, from a reference port, or from reasoning about what the
   * game appears to do, and someone has looked at the class and said so. The note says what is
   * uncertain.
   */
  GUESS,

  /**
   * Nobody has assessed this class yet, so it is assumed to be a {@link #GUESS}. The ledger assigns
   * this to every unannotated class; it is never written by hand, and it is not a build failure.
   * The only difference from {@link #GUESS} is that no one has looked.
   */
  UNASSESSED
}
