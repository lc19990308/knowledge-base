package dev.aiqa.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record AppUser(
    UUID id,
    String issuer,
    String subject,
    String displayName,
    boolean enabled,
    boolean canCreateSpace,
    long authVersion,
    List<String> roles) {

  public boolean isSystemAdmin() {
    return roles.contains("SYSTEM_ADMIN");
  }
}
