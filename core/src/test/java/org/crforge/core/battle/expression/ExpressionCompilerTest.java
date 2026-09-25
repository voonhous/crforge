package org.crforge.core.battle.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The expression compiler, case by case: the opcodes, the value, the fold and the refusals. */
class ExpressionCompilerTest {

  private static final TestEnvironment NONE = new TestEnvironment();

  private static int[] code(String text, ExpressionEnvironment environment, boolean fold) {
    return ExpressionCompiler.compile(text, environment, false, fold).code();
  }

  private static int value(String text, ExpressionEnvironment environment) {
    return ExpressionEvaluator.evaluate(
        ExpressionCompiler.compile(text, environment, false, false), environment);
  }

  private static int[] words(String words) {
    return Arrays.stream(words.trim().split("\\s+")).mapToInt(Integer::parseInt).toArray();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = ';',
      value = {
        "1+2; 1 1 1 2 4; 3",
        "5-2; 1 5 1 2 5; 3",
        "3*4; 1 3 1 4 6; 12",
        "7/2; 1 7 1 2 7; 3",
        "7%3; 1 7 1 3 8; 1",
        "1<2; 1 1 1 2 9; 1",
        "2<=1; 1 2 1 1 10; 0",
        "1>2; 1 1 1 2 11; 0",
        "2>=2; 1 2 1 2 12; 1",
        "2==2; 1 2 1 2 13; 1",
        "2!=2; 1 2 1 2 14; 0",
        "-5; 1 5 15; -5",
        "!0; 1 0 16; 1",
        "!3; 1 3 16; 0",
        "1&&0; 1 1 1 0 17; 0",
        "1||0; 1 1 1 0 18; 1",
        "1+2*3; 1 1 1 2 1 3 6 4; 7",
        "(1+2)*3; 1 1 1 2 4 1 3 6; 9",
        "1+2<4&&1; 1 1 1 2 4 1 4 9 1 1 17; 1",
        "0||1&&0; 1 0 1 1 1 0 17 18; 0",
        "10-3-2; 1 10 1 3 5 1 2 5; 5",
        "100/10/2; 1 100 1 10 7 1 2 7; 5",
        "2*3%4; 1 2 1 3 6 1 4 8; 2",
        "true; 1 1; 1",
        "false; 1 0; 0",
        "0; 1 0; 0",
        "2147483647; 1 2147483647; 2147483647"
      })
  @DisplayName("every operator emits its opcode after both operands, at its precedence, leftwards")
  void everyOperatorAtItsPrecedence(String text, String opcodes, int value) {
    assertThat(code(text, NONE, false)).containsExactly(words(opcodes));
    assertThat(value(text, NONE)).isEqualTo(value);
  }

  @Test
  @DisplayName("the environment is asked first and a zero-argument symbol takes either form")
  void theEnvironmentIsAskedFirst() {
    TestEnvironment env =
        new TestEnvironment()
            .with("tag", 0, 0, 0, a -> 7)
            .with("one_arg", 1, 1, 1, a -> a[0] * 10)
            .with("two_args", 2, 0, 2, a -> IntStream.of(a).sum())
            .with("min", 3, 0, 0, a -> 555);

    assertThat(code("tag", env, false)).containsExactly(3, 0, 0);
    assertThat(value("tag", env)).isEqualTo(7);
    assertThat(code("tag()", env, false)).containsExactly(3, 0, 0);
    assertThat(code("one_arg(3)", env, false)).containsExactly(1, 3, 3, 1, 1);
    assertThat(value("one_arg(3)", env)).isEqualTo(30);
    assertThat(code("two_args(4,5)", env, false)).containsExactly(1, 4, 1, 5, 3, 2, 2);
    assertThat(value("two_args(4,5)", env)).isEqualTo(9);
    assertThat(code("two_args()", env, false)).containsExactly(3, 2, 0);
    // The environment's min shadows the builtin, with its own argument count.
    assertThatThrownBy(() -> code("min(1,2)", env, false)).isInstanceOf(ExpressionException.class);
    assertThat(code("min", env, false)).containsExactly(3, 3, 0);
    assertThat(value("min", env)).isEqualTo(555);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = ';',
      value = {
        "min(3,4); 1 3 1 4 2 1 2; 3",
        "max(3,4); 1 3 1 4 2 2 2; 4",
        "abs(0-9); 1 0 1 9 5 2 3 1; 9",
        "clamp(5,1,3); 1 5 1 1 1 3 2 4 3; 3",
        "clamp(0,1,3); 1 0 1 1 1 3 2 4 3; 1",
        "select(1,10,20); 1 1 1 10 1 20 2 5 3; 10",
        "select(0,10,20); 1 0 1 10 1 20 2 5 3; 20",
        "logX10000(100); 1 100 2 6 1; 46052",
        "log10X10000(100); 1 100 2 7 1; 20000",
        "Min(1,2); 1 1 1 2 2 1 2; 1"
      })
  @DisplayName("every builtin, its names matched without regard to case")
  void everyBuiltin(String text, String opcodes, int value) {
    assertThat(code(text, NONE, false)).containsExactly(words(opcodes));
    assertThat(value(text, NONE)).isEqualTo(value);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = ';',
      value = {
        "1+2*3; 1 7",
        "(1+2)*3; 1 9",
        "min(3,4); 1 3",
        "abs(0-9); 1 9",
        "select(1,10,20); 1 10",
        "!0; 1 1",
        "2147483647+1; 1 -2147483648"
      })
  @DisplayName("the fold turns a subtree without a call into its constant")
  void theFoldTurnsConstantsIntoOne(String text, String opcodes) {
    assertThat(code(text, NONE, true)).containsExactly(words(opcodes));
  }

  @Test
  @DisplayName("a call stops the fold, and a division or modulo by zero is left to run")
  void whatTheFoldLeaves() {
    TestEnvironment env = new TestEnvironment().with("tag", 0, 0, 0, a -> 7);
    assertThat(code("tag+1", env, true)).containsExactly(3, 0, 0, 1, 1, 4);
    assertThat(code("1/0", NONE, true)).containsExactly(1, 1, 1, 0, 7);
    assertThat(code("1%0", NONE, true)).containsExactly(1, 1, 1, 0, 8);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "1a",
        "1..2",
        "#",
        "(1",
        "1)",
        "one_arg(1",
        "one_arg()",
        "one_arg(1,2)",
        "two_args(1,2,3)",
        "unknown",
        "unknown(1)",
        "tag(1)",
        "1 2",
        "+",
        "1+",
        "()",
        "1.5",
        "0x10",
        "1e3"
      })
  @DisplayName("a string that is not an expression of the language is refused")
  void refused(String text) {
    TestEnvironment env =
        new TestEnvironment()
            .with("tag", 0, 0, 0, a -> 7)
            .with("one_arg", 1, 1, 1, a -> a[0])
            .with("two_args", 2, 0, 2, a -> IntStream.of(a).sum());
    assertThatThrownBy(() -> ExpressionCompiler.compile(text, env))
        .isInstanceOf(ExpressionException.class);
  }

  @Test
  @DisplayName("an empty string is the constant 0 only when it is allowed")
  void theEmptyString() {
    assertThatThrownBy(() -> ExpressionCompiler.compile("", NONE, false, true))
        .isInstanceOf(ExpressionException.class);
    Expression empty = ExpressionCompiler.compile("", NONE, true, true);
    assertThat(empty.code()).containsExactly(1, 0);
    assertThat(empty.maxDepth()).isEqualTo(1);
    assertThat(ExpressionCompiler.compile("   ", NONE, true, true).code()).containsExactly(1, 0);
  }

  @Test
  @DisplayName("whitespace, identifiers and literals as the tokenizer reads them")
  void theTokenizer() {
    TestEnvironment env =
        new TestEnvironment().with("A_1", 0, 0, 0, a -> 1).with("_x", 1, 0, 0, a -> 2);
    assertThat(ExpressionEvaluator.evaluate(ExpressionCompiler.compile("  1 + 2  ", env), env))
        .isEqualTo(3);
    assertThat(ExpressionEvaluator.evaluate(ExpressionCompiler.compile("1\t+\n2", env), env))
        .isEqualTo(3);
    assertThat(ExpressionEvaluator.evaluate(ExpressionCompiler.compile("A_1", env), env))
        .isEqualTo(1);
    assertThat(ExpressionEvaluator.evaluate(ExpressionCompiler.compile("_x", env), env))
        .isEqualTo(2);
    // A literal is read as C reads it, so one too large for an int wraps.
    assertThat(ExpressionEvaluator.evaluate(ExpressionCompiler.compile("4294967296", env), env))
        .isZero();
    assertThat(ExpressionEvaluator.evaluate(ExpressionCompiler.compile("99999999999", env), env))
        .isEqualTo(1215752191);
    assertThat(code("-2147483648", env, false)).containsExactly(1, -2147483648, 15);
    assertThat(value("-2147483648", env)).isEqualTo(-2147483648);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = ';',
      value = {"1; 1", "1+2; 2", "1+2+3; 2", "1+(2+(3+4)); 4", "tag; 1", "1+tag*2; 3"})
  @DisplayName("the deepest the value stack goes")
  void theStackDepth(String text, int depth) {
    TestEnvironment env = new TestEnvironment().with("tag", 0, 0, 0, a -> 1);
    assertThat(ExpressionCompiler.compile(text, env, false, false).maxDepth()).isEqualTo(depth);
  }
}
