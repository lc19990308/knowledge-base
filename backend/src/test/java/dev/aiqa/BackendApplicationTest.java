package dev.aiqa;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BackendApplicationTest {

  @Test
  void applicationPackageUsesExpectedBasePackage() {
    assertThat(BackendApplication.class.getPackageName()).isEqualTo("dev.aiqa");
  }
}
