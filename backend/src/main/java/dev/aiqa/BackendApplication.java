package dev.aiqa;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class BackendApplication {

  /**
   * One artifact, two process roles: {@code api} serves HTTP without workers, {@code worker}
   * consumes jobs without a web server, {@code all} (default) does both for local development.
   */
  public static void main(String[] args) {
    String role = System.getenv().getOrDefault("PROCESS_ROLE", "all");
    SpringApplicationBuilder builder = new SpringApplicationBuilder(BackendApplication.class);
    switch (role) {
      case "api" -> builder.properties("aiqa.worker.enabled=false");
      case "worker" -> builder.web(WebApplicationType.NONE).properties("aiqa.worker.enabled=true");
      default -> {
        // both roles in one process
      }
    }
    builder.run(args);
  }
}
