package org.crforge.core.fidelity;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares how well a simulation class or method is known to match the standard game.
 *
 * <p>The point of carrying this in the code rather than in a document is that it cannot drift: a
 * class is annotated where it lives, {@code FidelityLedgerTest} fails when a new simulation class
 * arrives without one, and {@code ./gradlew :core:fidelityReport} turns the annotations into a
 * count that should fall over time.
 *
 * <p>Keep {@link #note()} descriptive rather than evidential. Say what is uncertain ("windup timing
 * inferred from observation"), not where the answer came from. Evidence belongs in working notes
 * outside the repository.
 *
 * <p>Applying this to a method narrows the claim to that method; the class-level status still
 * describes the class as a whole, so a {@link FidelityStatus#PARTIAL} class will usually carry
 * method-level annotations marking which parts are settled.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Fidelity {

  /** How well this element's behaviour is known. Never {@link FidelityStatus#UNDECLARED}. */
  FidelityStatus status();

  /** What specifically is uncertain, or for a settled element, what it is settled against. */
  String note() default "";
}
