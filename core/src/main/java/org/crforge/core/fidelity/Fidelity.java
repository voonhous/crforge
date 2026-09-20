package org.crforge.core.fidelity;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares how well a class or method is known to match the standard game.
 *
 * <p>Unannotated code is a {@link FidelityStatus#GUESS}. There is no "not looked at yet" state:
 * whoever wrote the logic knew whether they had something to write it against, and code that cannot
 * point to evidence is a guess. So this annotation is only needed to record a departure from that
 * default, or to say what specifically is uncertain about a guess.
 *
 * <p>{@link FidelityStatus#TRACED} and {@link FidelityStatus#PARTIAL} require a {@link #note()},
 * because those are the two claims that could mislead someone into building on them. {@code
 * FidelityLedgerTest} fails the build if either is left unjustified.
 *
 * <p>Keep the note descriptive rather than evidential. Say what is uncertain ("windup timing
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

  /** How well this element's behaviour is known. */
  FidelityStatus status();

  /** What specifically is uncertain, or for a settled element, what it is settled against. */
  String note() default "";
}
