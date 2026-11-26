package dev.aiqa;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards the HTTP surface against drift in both directions: every implemented route must be
 * declared, and every declared operation must exist. The check is static so it needs no database or
 * web context.
 */
class OpenApiContractTest {

  private static final Set<Class<?>> CONTROLLERS =
      Set.of(
          dev.aiqa.api.AdminUserController.class,
          dev.aiqa.api.AnswerStreamController.class,
          dev.aiqa.api.AnswerTemplateController.class,
          dev.aiqa.api.ModelConfigController.class,
          dev.aiqa.api.AuthController.class,
          dev.aiqa.api.CleaningController.class,
          dev.aiqa.api.DocumentController.class,
          dev.aiqa.api.DraftController.class,
          dev.aiqa.api.PublishController.class,
          dev.aiqa.api.QaController.class,
          dev.aiqa.api.SearchController.class,
          dev.aiqa.api.SettingsController.class,
          dev.aiqa.api.SpaceController.class,
          dev.aiqa.api.UsageController.class);

  /** 由 Spring Security 提供、没有控制器方法的操作。 */
  private static final Set<String> FILTER_PROVIDED = Set.of("POST /api/v1/auth/logout");

  @Test
  void contractAndImplementationCoverEachOther() throws Exception {
    Set<String> implemented = new TreeSet<>();
    for (Class<?> controller : CONTROLLERS) {
      assertThat(controller.isAnnotationPresent(RestController.class)).isTrue();
      String base = firstPath(controller.getAnnotation(RequestMapping.class).value());
      for (Method method : controller.getDeclaredMethods()) {
        String[] verbs = verbsOf(method);
        if (verbs == null) {
          continue;
        }
        for (String verb : verbs) {
          implemented.add(verb + " " + normalize(base + methodSuffix(method)));
        }
      }
    }

    Set<String> declared;
    try (InputStream input = Files.newInputStream(Path.of("../docs/ai-qa-openapi.yaml"))) {
      @SuppressWarnings("unchecked")
      Map<String, Object> spec = (Map<String, Object>) new Yaml().load(input);
      @SuppressWarnings("unchecked")
      Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
      declared = new TreeSet<>();
      String base = spec.get("servers") instanceof java.util.List<?> servers
              && !servers.isEmpty()
              && servers.get(0) instanceof Map<?, ?> server
              && server.get("url") instanceof String url
          ? url
          : "";
      for (Map.Entry<String, Object> entry : paths.entrySet()) {
        if (!(entry.getValue() instanceof Map<?, ?> operations)) {
          continue;
        }
        for (Object key : operations.keySet()) {
          String verb = key.toString().toUpperCase(Locale.ROOT);
          if (Set.of("GET", "POST", "PUT", "DELETE", "PATCH").contains(verb)) {
            declared.add(verb + " " + normalize(base + entry.getKey()));
          }
        }
      }
    }

    Set<String> undeclared = new TreeSet<>(implemented);
    undeclared.removeAll(declared);
    assertThat(undeclared).as("实现存在但契约未声明的路由").isEmpty();

    Set<String> unimplemented = new TreeSet<>(declared);
    unimplemented.removeAll(implemented);
    unimplemented.removeAll(FILTER_PROVIDED);
    assertThat(unimplemented).as("契约声明但实现不存在的操作").isEmpty();

    assertThat(implemented).isNotEmpty();
  }

  /** 方法上的 HTTP 动词；没有映射注解时返回 null。 */
  private static String[] verbsOf(Method method) {
    if (method.isAnnotationPresent(GetMapping.class)) {
      return new String[] {"GET"};
    }
    if (method.isAnnotationPresent(PostMapping.class)) {
      return new String[] {"POST"};
    }
    if (method.isAnnotationPresent(PutMapping.class)) {
      return new String[] {"PUT"};
    }
    if (method.isAnnotationPresent(DeleteMapping.class)) {
      return new String[] {"DELETE"};
    }
    if (method.isAnnotationPresent(PatchMapping.class)) {
      return new String[] {"PATCH"};
    }
    if (method.isAnnotationPresent(RequestMapping.class)) {
      String[] values = method.getAnnotation(RequestMapping.class).method().length == 0
          ? new String[] {"GET"}
          : java.util.Arrays.stream(method.getAnnotation(RequestMapping.class).method())
              .map(Enum::name)
              .toArray(String[]::new);
      return values;
    }
    return null;
  }

  private static String methodSuffix(Method method) {
    for (Class<? extends java.lang.annotation.Annotation> type :
        java.util.List.of(
            GetMapping.class, PostMapping.class, PutMapping.class, DeleteMapping.class,
            PatchMapping.class, RequestMapping.class)) {
      if (method.isAnnotationPresent(type)) {
        try {
          return firstPath((String[]) type.getMethod("value").invoke(
              method.getAnnotation(type)));
        } catch (ReflectiveOperationException failure) {
          return "";
        }
      }
    }
    return "";
  }

  private static String firstPath(String[] values) {
    return values.length == 0 ? "" : values[0];
  }

  private static String normalize(String path) {
    String normalized = path.replaceAll("/{2,}", "/");
    return normalized.length() > 1 && normalized.endsWith("/")
        ? normalized.substring(0, normalized.length() - 1)
        : normalized;
  }
}
