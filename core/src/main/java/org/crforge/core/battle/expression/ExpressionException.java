/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.core.battle.expression;

/** A string the expression compiler refuses. */
public class ExpressionException extends RuntimeException {

  public ExpressionException(String message) {
    super(message);
  }
}
