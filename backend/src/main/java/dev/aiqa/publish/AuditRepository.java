package dev.aiqa.publish;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {

  private final JdbcClient jdbc;

  public AuditRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void record(UUID actor, String action, String resourceType, UUID resourceId, Map<String, String> details) {
    jdbc.sql(
            """
            INSERT INTO audit_event (id, actor_user_id, action, resource_type, resource_id, request_id, details)
            VALUES (:id, :actor, :action, :type, :resource, :requestId, CAST(:details AS jsonb))
            """)
        .param("id", UUID.randomUUID())
        .param("actor", actor)
        .param("action", action)
        .param("type", resourceType)
        .param("resource", resourceId)
        .param("requestId", java.util.UUID.randomUUID().toString())
        .param("details", toJson(details))
        .update();
  }

  private static String toJson(Map<String, String> details) {
    StringBuilder json = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, String> entry : details.entrySet()) {
      if (!first) {
        json.append(',');
      }
      first = false;
      json.append('"').append(escape(entry.getKey())).append("\":\"").append(escape(entry.getValue())).append('"');
    }
    return json.append('}').toString();
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
