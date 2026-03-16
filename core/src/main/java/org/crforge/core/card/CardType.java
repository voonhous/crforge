package org.crforge.core.card;

public enum CardType {
  TROOP,
  SPELL,
  BUILDING,
  HERO;

  /** Returns true if this card type deploys as a troop (TROOP or HERO). */
  public boolean isTroopLike() {
    return this == TROOP || this == HERO;
  }
}
