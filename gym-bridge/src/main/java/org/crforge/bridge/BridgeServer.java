package org.crforge.bridge;

import org.crforge.bridge.protocol.ZmqTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the gym-bridge server. Binds a ZMQ PAIR socket and runs a session loop.
 *
 * <p>Usage: java -jar gym-bridge.jar [port] Default port: 9876
 */
public class BridgeServer {

  private static final Logger log = LoggerFactory.getLogger(BridgeServer.class);
  private static final int DEFAULT_PORT = 9876;

  public static void main(String[] args) {
    int port = DEFAULT_PORT;
    if (args.length > 0) {
      try {
        port = Integer.parseInt(args[0]);
      } catch (NumberFormatException e) {
        log.error("Invalid port number: {}", args[0]);
        System.exit(1);
      }
    }

    String endpoint = "tcp://*:" + port;
    log.info("Starting CRForge Bridge Server on {}", endpoint);

    // Force CardRegistry static initialization before accepting connections
    log.info(
        "Loading card registry ({} cards)...",
        org.crforge.data.card.CardRegistry.getAllIds().size());

    try (ZmqTransport transport = new ZmqTransport(endpoint)) {
      transport.bind();

      // Run session loop -- reconnects automatically after client disconnects
      while (true) {
        log.info("Waiting for client connection on port {}...", port);
        BridgeSession session = new BridgeSession(transport);
        session.run();
        log.info("Session ended, ready for next connection");
      }
    } catch (Exception e) {
      log.error("Bridge server error", e);
      System.exit(1);
    }
  }
}
