package org.crforge.core.battle.expression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/** An environment the tests fill with named functions and the answers they give. */
final class TestEnvironment implements ExpressionEnvironment {

  private final Map<String, ExpressionEnvironment.Function> names = new HashMap<>();
  private final Map<Integer, ToIntFunction<int[]>> answers = new HashMap<>();

  /** The names of the functions called, in the order they were called. */
  final List<String> calls = new ArrayList<>();

  private final Map<Integer, String> nameOfId = new HashMap<>();

  TestEnvironment with(String name, int id, int min, int max, ToIntFunction<int[]> answer) {
    names.put(name, new ExpressionEnvironment.Function(id, min, max));
    answers.put(id, answer);
    nameOfId.put(id, name);
    return this;
  }

  @Override
  public ExpressionEnvironment.Function resolve(String name) {
    return names.get(name);
  }

  @Override
  public int call(int id, int[] arguments) {
    calls.add(nameOfId.get(id));
    return answers.get(id).applyAsInt(arguments);
  }
}
