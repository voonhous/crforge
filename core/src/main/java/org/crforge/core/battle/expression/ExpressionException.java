package org.crforge.core.battle.expression;

/** A string the expression compiler refuses. */
public class ExpressionException extends RuntimeException {

  public ExpressionException(String message) {
    super(message);
  }
}
