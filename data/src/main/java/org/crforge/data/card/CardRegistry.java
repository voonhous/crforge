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
import org.crforge.core.effect.BuffDefinition;
import org.crforge.core.effect.BuffRegistry;
import org.crforge.data.loader.BuffLoader;
import org.crforge.data.loader.CardLoader;

@Slf4j
public class CardRegistry {

  private static final Map<String, Card> CARDS = new LinkedHashMap<>();

  static {
    loadDefaultCards();
    loadDefaultBuffs();
  }

  private static void loadDefaultCards() {
    // Load from classpath resource
    try (InputStream is = CardRegistry.class.getResourceAsStream("/cards/cards.json")) {
      List<Card> loadedCards = CardLoader.loadCards(is);
      for (Card card : loadedCards) {
        register(card);
      }
    } catch (IOException e) {
      log.error("Error loading default cards", e);
      throw new RuntimeException(e);
    }
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

  private static void register(Card card) {
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
}
