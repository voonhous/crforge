package org.crforge.core.fidelity;

/**
 * How well a class's behaviour is known to match the standard game.
 *
 * <p>This records confidence, not code quality. A {@link #GUESS} can be well written, well tested
 * against itself and still wrong about what the real game does.
 *
 * <p>There is no state for "not looked at yet". Game logic is either written against something
 * established or made up, and whoever wrote it knew which. Code that cannot point to evidence is a
 * {@link #GUESS}, so that is the default and needs no annotation; only {@link #PARTIAL} and {@link
 * #TRACED} have to be declared and justified.
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
   * game appears to do. This is what unannotated code is, so the annotation is only worth adding
   * here to record what specifically is uncertain.
   */
  GUESS
}
