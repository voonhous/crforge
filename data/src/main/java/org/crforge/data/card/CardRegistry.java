package org.crforge.data.card;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.crforge.core.card.Card;
import org.crforge.core.card.ProjectileStats;
import org.crforge.core.card.TroopStats;
import org.crforge.core.effect.BuffDefinition;
import org.crforge.core.effect.BuffRegistry;
import org.crforge.data.loader.BuffLoader;
import org.crforge.data.loader.CardLoader;
import org.crforge.data.loader.ProjectileLoader;
import org.crforge.data.loader.UnitLoader;

@Slf4j
public class CardRegistry {

  private static final Map<String, Card> CARDS = new LinkedHashMap<>();

  // Card ID -> insertion index (0-based), for observation encoding
  private static final Map<String, Integer> CARD_INDEX = new LinkedHashMap<>();

  static {
    // Loading order matters: buffs -> projectiles -> units -> cards
    loadDefaultBuffs();
    Map<String, ProjectileStats> projectileMap = loadDefaultProjectiles();
    Map<String, TroopStats> unitMap = loadDefaultUnits(projectileMap);
    loadDefaultCards(unitMap, projectileMap);
  }

  private static void loadDefaultBuffs() {
    try (InputStream is = CardRegistry.class.getResourceAsStream("/cards/buffs.json")) {
      Map<String, BuffDefinition> buffs = BuffLoader.loadBuffs(is);
      for (Map.Entry<String, BuffDefinition> entry : buffs.entrySet()) {
        BuffRegistry.register(entry.getKey(), entry.getValue());
      }
    } catch (IOException e) {
      log.error("Error loading default buffs", e);
      throw new RuntimeException(e);
    }
  }

  private static Map<String, ProjectileStats> loadDefaultProjectiles() {
    try (InputStream is = CardRegistry.class.getResourceAsStream("/cards/projectiles.json")) {
      return ProjectileLoader.loadProjectiles(is);
    } catch (IOException e) {
      log.error("Error loading default projectiles", e);
      throw new RuntimeException(e);
    }
  }

  private static Map<String, TroopStats> loadDefaultUnits(
      Map<String, ProjectileStats> projectileMap) {
    try (InputStream is = CardRegistry.class.getResourceAsStream("/cards/units.json")) {
      return UnitLoader.loadUnits(is, projectileMap);
    } catch (IOException e) {
      log.error("Error loading default units", e);
      throw new RuntimeException(e);
    }
  }

  private static void loadDefaultCards(
      Map<String, TroopStats> unitMap, Map<String, ProjectileStats> projectileMap) {
    try (InputStream is = CardRegistry.class.getResourceAsStream("/cards/cards.json")) {
      List<Card> loadedCards = CardLoader.loadCards(is, unitMap, projectileMap);
      for (Card card : loadedCards) {
        register(card);
      }
    } catch (IOException e) {
      log.error("Error loading default cards", e);
      throw new RuntimeException(e);
    }
  }

  private static void register(Card card) {
    CARD_INDEX.put(card.getId(), CARDS.size());
    CARDS.put(card.getId(), card);
  }

  public static Card get(String id) {
    return CARDS.get(id);
  }

  public static Collection<Card> getAll() {
    return Collections.unmodifiableCollection(CARDS.values());
  }

  public static List<String> getAllIds() {
    return new ArrayList<>(CARDS.keySet());
  }

  public static boolean exists(String id) {
    return CARDS.containsKey(id);
  }

  /** Returns the 0-based index of the card in the registry, or -1 if not found. */
  public static int getIndex(String id) {
    Integer idx = CARD_INDEX.get(id);
    return idx != null ? idx : -1;
  }

  /** Returns the total number of registered cards. */
  public static int size() {
    return CARDS.size();
  }
}
