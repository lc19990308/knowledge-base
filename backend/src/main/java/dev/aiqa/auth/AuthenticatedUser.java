package dev.aiqa.auth;

/**
 * Authenticated principal as asserted by the identity provider. Never derived from client input.
 */
public record AuthenticatedUser(String issuer, String subject, String displayName) {}
