package org.crforge.core.effect;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static registry for buff definitions loaded from parsed_buffs.json.
 * Follows the same pattern as CardRegistry.
 */
public class BuffRegistry {

  private static final Map<String, BuffDefinition> BUFFS = new LinkedHashMap<>();

  public static void register(String name, BuffDefinition definition) {
    BUFFS.put(name, definition);
  }

  /**
   * Returns the BuffDefinition for the given buff name, or null if not registered.
   */
  public static BuffDefinition get(String name) {
    if (name == null) {
      return null;
    }
    return BUFFS.get(name);
  }

  public static Collection<BuffDefinition> getAll() {
    return Collections.unmodifiableCollection(BUFFS.values());
  }

  public static void clear() {
    BUFFS.clear();
  }
}
