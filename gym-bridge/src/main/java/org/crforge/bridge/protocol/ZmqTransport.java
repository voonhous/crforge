package org.crforge.bridge.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.Closeable;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

/**
 * ZeroMQ PAIR socket transport layer for the bridge protocol. Handles JSON
 * serialization/deserialization over ZMQ frames.
 */
public class ZmqTransport implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(ZmqTransport.class);

  private final ObjectMapper mapper;
  private final ZContext context;
  private final ZMQ.Socket socket;
  private final String endpoint;

  public ZmqTransport(String endpoint) {
    this.endpoint = endpoint;
    this.mapper = new ObjectMapper();
    this.context = new ZContext();
    this.socket = context.createSocket(SocketType.PAIR);
  }

  /** Binds the socket as a server. */
  public void bind() {
    socket.bind(endpoint);
    log.info("ZMQ PAIR socket bound on {}", endpoint);
  }

  /**
   * Receives a JSON message from the socket. Blocks until a message arrives.
   *
   * @return parsed JSON node, or null if the socket was interrupted
   */
  public JsonNode receive() throws IOException {
    byte[] data = socket.recv(0);
    if (data == null) {
      return null;
    }
    String json = new String(data, java.nio.charset.StandardCharsets.UTF_8);
    log.debug("Received: {}", json);
    return mapper.readTree(json);
  }

  /** Sends a JSON response with a type field. */
  public void send(String type, Object payload) throws IOException {
    ObjectNode response = mapper.createObjectNode();
    response.put("type", type);
    if (payload != null) {
      response.set("data", mapper.valueToTree(payload));
    }
    String json = mapper.writeValueAsString(response);
    log.debug("Sending: {}", json);
    socket.send(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0);
  }

  /** Sends an error response. */
  public void sendError(String message) throws IOException {
    ObjectNode response = mapper.createObjectNode();
    response.put("type", "error");
    response.put("message", message);
    String json = mapper.writeValueAsString(response);
    socket.send(json.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0);
  }

  public ObjectMapper getMapper() {
    return mapper;
  }

  @Override
  public void close() {
    context.close();
    log.info("ZMQ transport closed");
  }
}
