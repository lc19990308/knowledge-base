package dev.aiqa.common;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 列表接口的 offset/limit 分页参数。
 *
 * <p>{@code limit} 缺省表示返回全部记录，与引入分页之前的响应保持一致；显式传入时限制在
 * 1..{@value #MAX_LIMIT} 之间，{@code offset} 不能为负数，非法值按 400 INVALID_REQUEST 处理。
 */
public record PageQuery(int offset, Integer limit) {

  /** 单页条数上限，防止客户端一次取走过多数据。 */
  public static final int MAX_LIMIT = 200;

  public static PageQuery of(Integer limit, int offset) {
    if (offset < 0) {
      throw badRequest("offset 不能小于 0");
    }
    if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
      throw badRequest("limit 需在 1 到 " + MAX_LIMIT + " 之间");
    }
    return new PageQuery(offset, limit);
  }

  /** 按 offset/limit 截取一页；offset 超出范围时返回空列表。 */
  public <T> List<T> slice(List<T> all) {
    if (offset >= all.size()) {
      return List.of();
    }
    int end = limit == null ? all.size() : Math.min(all.size(), offset + limit);
    return List.copyOf(all.subList(offset, end));
  }

  private static ApiException badRequest(String detail) {
    return new ApiException(ErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST, detail);
  }
}
