package dev.aiqa.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultMatcher;

public final class ApiResponseAssertions {

  private ApiResponseAssertions() {}

  public static ResultMatcher successEnvelope() {
    return result -> {
      content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON).match(result);
      jsonPath("$.code").value("OK").match(result);
      jsonPath("$.data").hasJsonPath().match(result);
      jsonPath("$.requestId").value(requestId(result.getResponse().getHeader("X-Request-Id")))
          .match(result);
    };
  }

  public static ResultMatcher problem(String code) {
    return result -> {
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
      jsonPath("$.code").value(code).match(result);
      jsonPath("$.status").value(result.getResponse().getStatus()).match(result);
      jsonPath("$.type").isString().match(result);
      jsonPath("$.title").isString().match(result);
      jsonPath("$.data").doesNotHaveJsonPath().match(result);
      jsonPath("$.requestId").value(requestId(result.getResponse().getHeader("X-Request-Id")))
          .match(result);
    };
  }

  private static String requestId(String value) {
    assertThat(value).as("X-Request-Id").isNotBlank();
    return value;
  }
}
