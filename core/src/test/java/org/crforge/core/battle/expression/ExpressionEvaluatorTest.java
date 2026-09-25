package org.crforge.core.battle.expression;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The evaluator: 32-bit arithmetic, truth, no short circuit, calls and the early answers. */
class ExpressionEvaluatorTest {

  private static int run(String text, ExpressionEnvironment environment) {
    return ExpressionEvaluator.evaluate(
        ExpressionCompiler.compile(text, environment, false, false), environment);
  }

  @ParameterizedTest(name = "{0} = {1}")
  @CsvSource(
      delimiter = ';',
      value = {
        "2147483647+1; -2147483648",
        "0-2147483648; -2147483648",
        "(0-7)/2; -3",
        "(0-7)%2; -1",
        "7/2; 3",
        "7%3; 1",
        "1/0; 0",
        "1%0; 0",
        "0/0; 0",
        "(0-2147483648)-1; 2147483647",
        "65536*65536; 0",
        "(0-1)*(0-1); 1"
      })
  @DisplayName("arithmetic wraps at 32 bits, truncates, and gives 0 for a division by zero")
  void arithmetic(String text, int value) {
    assertThat(run(text, new TestEnvironment())).isEqualTo(value);
  }

  @ParameterizedTest(name = "{0} = {1}")
  @CsvSource(
      delimiter = ';',
      value = {"3&&2; 1", "3||0; 1", "0||0; 0", "!5; 0", "!0; 1", "(1<2)+(2<3); 2", "(5>4)*10; 10"})
  @DisplayName("a comparison yields 0 or 1 and any value other than 0 is true")
  void truth(String text, int value) {
    assertThat(run(text, new TestEnvironment())).isEqualTo(value);
  }

  @Test
  @DisplayName("neither && nor || skips its right side")
  void noShortCircuit() {
    TestEnvironment env =
        new TestEnvironment().with("left", 0, 0, 0, a -> 0).with("right", 1, 0, 0, a -> 1);
    run("left&&right", env);
    assertThat(env.calls).containsExactly("left", "right");
    env.calls.clear();
    run("left||right", env);
    assertThat(env.calls).containsExactly("left", "right");
  }

  @ParameterizedTest(name = "{0} = {1}")
  @CsvSource(
      delimiter = ';',
      value = {"f(); 1", "f(10); 11", "f(10,20); 31", "f(f(1),f(2)); 6", "f(1)+f(2); 5"})
  @DisplayName("a call pops its arguments and pushes its answer")
  void calls(String text, int value) {
    TestEnvironment env = new TestEnvironment().with("f", 0, 0, 3, a -> IntStream.of(a).sum() + 1);
    assertThat(run(text, env)).isEqualTo(value);
  }

  @Test
  @DisplayName("twenty calls in one expression")
  void twentyCalls() {
    TestEnvironment env = new TestEnvironment().with("f", 0, 0, 3, a -> IntStream.of(a).sum() + 1);
    String deep =
        IntStream.range(0, 20).mapToObj(i -> "f(" + i + ")").collect(Collectors.joining("+"));
    assertThat(run(deep, env)).isEqualTo(IntStream.range(0, 20).map(i -> i + 1).sum());
  }

  @Test
  @DisplayName("an empty code answers 0 and a lone push answers its value, without a call")
  void theEarlyAnswers() {
    assertThat(ExpressionEvaluator.evaluate(new Expression(new int[0], 0), null)).isZero();
    assertThat(ExpressionEvaluator.evaluate(new Expression(new int[] {1, 42}, 1), null))
        .isEqualTo(42);
  }
}
