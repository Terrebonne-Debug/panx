package org.panx;

import java.util.Objects;

public record Server(
    long id,
    String name,
    String host,
    int port,
    boolean tls,
    String username,
    String password,
    int connections) {
  public Server {
    name = Objects.requireNonNull(name).strip();
    host = Objects.requireNonNull(host).strip();
    username = Objects.requireNonNullElse(username, "");
    password = Objects.requireNonNullElse(password, "");
    if (name.isBlank() || host.isBlank() || host.chars().anyMatch(Character::isWhitespace))
      throw new IllegalArgumentException("A server name and valid host are required");
    if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be 1–65535");
    if (connections < 1 || connections > 40)
      throw new IllegalArgumentException("Connections must be 1–40");
    if ((username + password).contains("\r") || (username + password).contains("\n"))
      throw new IllegalArgumentException("Invalid credentials");
  }

  @Override
  public String toString() {
    return name;
  }
}
