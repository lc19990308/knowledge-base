package dev.aiqa.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * Gives each integration test class its own disposable PostgreSQL schema in the isolated test
 * database. Credentials come only from the environment; nothing is read from project files.
 */
public final class TestDatabase implements AutoCloseable {

  private final String adminUrl;
  private final String user;
  private final String password;
  private final String schema;
  private final String jdbcUrl;

  private TestDatabase(String adminUrl, String user, String password, String schema) {
    this.adminUrl = adminUrl;
    this.user = user;
    this.password = password;
    this.schema = schema;
    this.jdbcUrl = adminUrl + (adminUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
  }

  public static Optional<TestDatabase> fromEnvironment() {
    String url = System.getenv("IT_DB_URL");
    String user = System.getenv("IT_DB_USER");
    String password = System.getenv("IT_DB_PASSWORD");
    if (url == null || url.isBlank() || user == null || user.isBlank() || password == null) {
      return Optional.empty();
    }
    if (!url.startsWith("jdbc:postgresql:")) {
      throw new IllegalStateException("IT_DB_URL 必须是 PostgreSQL JDBC 地址");
    }
    String schema = "it_" + Long.toHexString(System.nanoTime());
    TestDatabase database = new TestDatabase(url, user, password, schema);
    database.createSchema();
    return Optional.of(database);
  }

  public String jdbcUrl() {
    return jdbcUrl;
  }

  public String schema() {
    return schema;
  }

  public String user() {
    return user;
  }

  public String password() {
    return password;
  }

  private void createSchema() {
    try (Connection connection = DriverManager.getConnection(adminUrl, user, password);
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + schema);
    } catch (SQLException ex) {
      throw new IllegalStateException("无法创建隔离测试 schema", ex);
    }
  }

  public void dropSchema() {
    try (Connection connection = DriverManager.getConnection(adminUrl, user, password);
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    } catch (SQLException ignored) {
      // A leftover schema does not conceal a passing test; the name is unique per run.
    }
  }

  @Override
  public void close() {
    dropSchema();
  }
}
