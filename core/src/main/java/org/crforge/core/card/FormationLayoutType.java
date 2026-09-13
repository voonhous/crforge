package org.crforge.core.card;

/** How a multi-unit troop card positions its units relative to the deploy point. */
public enum FormationLayoutType {
  /**
   * Explicit {@code formationOffsets} when present, otherwise the legacy circular fallback driven
   * by the raw summonRadius (see {@link org.crforge.core.util.FormationLayout}).
   */
  DEFAULT,

  /**
   * Integer radial layout computed at deploy time (see {@link
   * org.crforge.core.util.FormationHelper}).
   */
  RADIAL
}
