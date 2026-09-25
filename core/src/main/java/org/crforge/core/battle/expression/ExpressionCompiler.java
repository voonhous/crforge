package org.crforge.core.battle.expression;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.crforge.core.fidelity.Fidelity;
import org.crforge.core.fidelity.FidelityStatus;

/**
 * Compiles the expression columns of the data into the opcode words {@link ExpressionEvaluator}
 * runs.
 *
 * <p>The language, lowest precedence first, every level left-associative:
 *
 * <pre>
 * expression := or
 * or         := and        ( '||' and )*
 * and        := equality   ( '&amp;&amp;' equality )*
 * equality   := relational ( ( '==' | '!=' ) relational )*
 * relational := additive   ( ( '&lt;' | '&lt;=' | '&gt;' | '&gt;=' ) additive )*
 * additive   := multiply   ( ( '+' | '-' ) multiply )*
 * multiply   := unary      ( ( '*' | '/' | '%' ) unary )*
 * unary      := ( '-' | '!' )* primary
 * primary    := '(' expression ')' | number | 'true' | 'false' | symbol [ '(' arguments ')' ]
 * </pre>
 *
 * <p>Each level emits its operator after both operands. A number is a run of decimal digits read as
 * C reads it: into a long, then cut to 32 bits, so a literal too large for an int wraps. Any
 * character below {@code 0x21} is whitespace. A symbol names a function of the environment, asked
 * first, or a builtin; a zero-argument function may be written with or without its parentheses.
 *
 * <p>With folding on, every subtree without a call to the environment is replaced by the one
 * constant it evaluates to, except a division or modulo by zero, which is left to run.
 */
@Fidelity(
    status = FidelityStatus.PARTIAL,
    note =
        "Settled: the grammar and its precedence, the opcodes each level emits, literals and"
            + " their wrap, whitespace, identifiers, the order symbols are resolved in, argument"
            + " counts, the fold and its exception for a division by zero, the refusals and the"
            + " stack depth, on every recorded case. Not modelled: data references - variables,"
            + " game tags and data rows by name - which need the loaded data. Not settled: whether"
            + " true and false are matched without regard to case.")
public final class ExpressionCompiler {

  private final String text;
  private final ExpressionEnvironment environment;
  private final boolean fold;
  private int cursor;

  /** The pending token: its kind and its text. */
  private Kind kind;

  private String token;

  private enum Kind {
    END,
    NUMBER,
    SYMBOL,
    PUNCTUATION
  }

  /** One compiled subtree: its words, and its value when it is a constant the fold may use. */
  private record Node(List<Integer> code, Integer constant) {

    static Node of(int value) {
      return new Node(List.of(Expression.PUSH, value), value);
    }
  }

  private ExpressionCompiler(String text, ExpressionEnvironment environment, boolean fold) {
    this.text = text;
    this.environment = environment;
    this.fold = fold;
  }

  /**
   * Compiles one expression with folding on and an empty string refused.
   *
   * @throws ExpressionException when the text is not an expression of the language
   */
  public static Expression compile(String text, ExpressionEnvironment environment) {
    return compile(text, environment, false, true);
  }

  /**
   * Compiles one expression.
   *
   * @param text the source
   * @param environment the functions its symbols may name
   * @param allowEmpty true to accept an empty source as the constant 0
   * @param fold true to replace every subtree without a call to the environment by its value
   * @throws ExpressionException when the text is not an expression of the language
   */
  public static Expression compile(
      String text, ExpressionEnvironment environment, boolean allowEmpty, boolean fold) {
    ExpressionCompiler compiler = new ExpressionCompiler(text, environment, fold);
    compiler.advance();
    if (compiler.kind == Kind.END) {
      if (!allowEmpty) {
        throw new ExpressionException("Empty expression");
      }
      return build(List.of(Expression.PUSH, 0));
    }
    Node node = compiler.or();
    if (compiler.kind != Kind.END) {
      throw new ExpressionException("Unexpected '" + compiler.token + "' after the expression");
    }
    return build(node.code());
  }

  private static Expression build(List<Integer> words) {
    int[] code = new int[words.size()];
    for (int i = 0; i < code.length; i++) {
      code[i] = words.get(i);
    }
    return new Expression(code, maxDepth(code));
  }

  /** The deepest the value stack goes while the code runs. */
  private static int maxDepth(int[] code) {
    int depth = 0;
    int max = 0;
    int i = 0;
    while (i < code.length) {
      int op = code[i];
      if (op == Expression.PUSH) {
        depth++;
        i += 2;
      } else if (op == Expression.BUILTIN || op == Expression.CALL) {
        depth += 1 - code[i + 2];
        i += 3;
      } else if (op == Expression.NEGATE || op == Expression.NOT) {
        i++;
      } else {
        depth--;
        i++;
      }
      max = Math.max(max, depth);
    }
    return max;
  }

  // -------------------------------------------------------------------------------------------
  // The tokenizer
  // -------------------------------------------------------------------------------------------

  /** Reads the next token into the pending one. */
  private void advance() {
    while (cursor < text.length() && text.charAt(cursor) < 0x21) {
      cursor++;
    }
    if (cursor >= text.length()) {
      kind = Kind.END;
      token = "";
      return;
    }
    char c = text.charAt(cursor);
    int start = cursor;
    if (c >= '0' && c <= '9') {
      while (cursor < text.length() && isDigit(text.charAt(cursor))) {
        cursor++;
      }
      if (cursor < text.length() && isIdentifierPart(text.charAt(cursor))) {
        throw new ExpressionException("Invalid number (extra characters after number)");
      }
      kind = Kind.NUMBER;
    } else if (isIdentifierStart(c)) {
      while (cursor < text.length() && isIdentifierPart(text.charAt(cursor))) {
        cursor++;
      }
      kind = Kind.SYMBOL;
    } else {
      cursor++;
      // The two-character operators.
      if (cursor < text.length()) {
        String pair = text.substring(start, cursor + 1);
        if (pair.equals("||")
            || pair.equals("&&")
            || pair.equals("==")
            || pair.equals("!=")
            || pair.equals("<=")
            || pair.equals(">=")) {
          cursor++;
        }
      }
      kind = Kind.PUNCTUATION;
    }
    token = text.substring(start, cursor);
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isIdentifierStart(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
  }

  private static boolean isIdentifierPart(char c) {
    return isIdentifierStart(c) || isDigit(c);
  }

  private boolean at(String punctuation) {
    return kind == Kind.PUNCTUATION && token.equals(punctuation);
  }

  private void expect(String punctuation) {
    if (!at(punctuation)) {
      throw new ExpressionException("Expected '" + punctuation + "' but found '" + token + "'");
    }
    advance();
  }

  // -------------------------------------------------------------------------------------------
  // The grammar
  // -------------------------------------------------------------------------------------------

  private Node or() {
    Node left = and();
    while (at("||")) {
      advance();
      left = binary(left, and(), Expression.OR);
    }
    return left;
  }

  private Node and() {
    Node left = equality();
    while (at("&&")) {
      advance();
      left = binary(left, equality(), Expression.AND);
    }
    return left;
  }

  private Node equality() {
    Node left = relational();
    while (at("==") || at("!=")) {
      int op = at("==") ? Expression.EQUAL : Expression.NOT_EQUAL;
      advance();
      left = binary(left, relational(), op);
    }
    return left;
  }

  private Node relational() {
    Node left = additive();
    while (at("<") || at("<=") || at(">") || at(">=")) {
      int op =
          switch (token) {
            case "<" -> Expression.LESS;
            case "<=" -> Expression.LESS_OR_EQUAL;
            case ">" -> Expression.GREATER;
            default -> Expression.GREATER_OR_EQUAL;
          };
      advance();
      left = binary(left, additive(), op);
    }
    return left;
  }

  private Node additive() {
    Node left = multiply();
    while (at("+") || at("-")) {
      int op = at("+") ? Expression.ADD : Expression.SUBTRACT;
      advance();
      left = binary(left, multiply(), op);
    }
    return left;
  }

  private Node multiply() {
    Node left = unary();
    while (at("*") || at("/") || at("%")) {
      int op =
          switch (token) {
            case "*" -> Expression.MULTIPLY;
            case "/" -> Expression.DIVIDE;
            default -> Expression.MODULO;
          };
      advance();
      left = binary(left, unary(), op);
    }
    return left;
  }

  private Node unary() {
    if (at("-") || at("!")) {
      int op = at("-") ? Expression.NEGATE : Expression.NOT;
      advance();
      Node operand = unary();
      if (fold && operand.constant() != null) {
        int v = operand.constant();
        return Node.of(op == Expression.NEGATE ? -v : (v == 0 ? 1 : 0));
      }
      return new Node(append(operand.code(), op), null);
    }
    return primary();
  }

  private Node primary() {
    if (at("(")) {
      advance();
      Node inner = or();
      expect(")");
      return inner;
    }
    if (kind == Kind.NUMBER) {
      int value = atoi(token);
      advance();
      return Node.of(value);
    }
    if (kind == Kind.SYMBOL) {
      String name = token;
      advance();
      if (name.equals("true")) {
        return Node.of(1);
      }
      if (name.equals("false")) {
        return Node.of(0);
      }
      return symbol(name);
    }
    throw new ExpressionException(
        kind == Kind.END ? "Unexpected end of expression" : "Unexpected '" + token + "'");
  }

  /** A symbol: a function of the environment, asked first, or a builtin. */
  private Node symbol(String name) {
    boolean parenthesised = at("(");
    List<Node> arguments = parenthesised ? arguments() : List.of();
    ExpressionEnvironment.Function function =
        environment == null ? null : environment.resolve(name);
    if (function != null) {
      if (arguments.size() < function.minArguments()
          || arguments.size() > function.maxArguments()) {
        throw new ExpressionException("Wrong number of arguments for " + name);
      }
      return call(arguments, Expression.CALL, function.id());
    }
    Builtins.Builtin builtin = Builtins.resolve(name);
    if (builtin != null) {
      if (!parenthesised || arguments.size() != builtin.arguments()) {
        throw new ExpressionException("Wrong number of arguments for " + name);
      }
      Node node = call(arguments, Expression.BUILTIN, builtin.id());
      if (fold && arguments.stream().allMatch(a -> a.constant() != null)) {
        int[] values = arguments.stream().mapToInt(Node::constant).toArray();
        return Node.of(Builtins.call(builtin.id(), values));
      }
      return node;
    }
    throw new ExpressionException("Unknown symbol " + name);
  }

  private List<Node> arguments() {
    expect("(");
    List<Node> arguments = new ArrayList<>();
    if (at(")")) {
      advance();
      return arguments;
    }
    arguments.add(or());
    while (at(",")) {
      advance();
      arguments.add(or());
    }
    expect(")");
    return arguments;
  }

  private static Node call(List<Node> arguments, int op, int id) {
    List<Integer> code = new ArrayList<>();
    for (Node argument : arguments) {
      code.addAll(argument.code());
    }
    code.add(op);
    code.add(id);
    code.add(arguments.size());
    return new Node(code, null);
  }

  /** Emits a binary operator after both operands, or folds two constants into one. */
  private Node binary(Node left, Node right, int op) {
    boolean byZero =
        (op == Expression.DIVIDE || op == Expression.MODULO)
            && right.constant() != null
            && right.constant() == 0;
    if (fold && left.constant() != null && right.constant() != null && !byZero) {
      return Node.of(ExpressionEvaluator.apply(op, left.constant(), right.constant()));
    }
    List<Integer> code = new ArrayList<>(left.code());
    code.addAll(right.code());
    code.add(op);
    return new Node(code, null);
  }

  private static List<Integer> append(List<Integer> code, int op) {
    List<Integer> out = new ArrayList<>(code);
    out.add(op);
    return out;
  }

  /** A decimal literal as C's atoi reads it: into a long, saturating, then cut to 32 bits. */
  private static int atoi(String digits) {
    BigInteger value = new BigInteger(digits);
    BigInteger max = BigInteger.valueOf(Long.MAX_VALUE);
    return (int) (value.compareTo(max) > 0 ? Long.MAX_VALUE : value.longValue());
  }
}
