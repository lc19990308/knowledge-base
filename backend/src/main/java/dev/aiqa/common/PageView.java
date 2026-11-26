package dev.aiqa.common;

import java.util.List;

/**
 * One page of results. {@code nextCursor} is null on the last page, as the published contract
 * states; Map.of cannot carry null, so pages are serialized from this record.
 *
 * <p>{@code total} 是筛选后的记录总数，配合 {@link PageQuery} 的 limit/offset 让客户端能算出页数；
 * 未传 limit 的调用方拿到的 items 就是全部记录，此时 total 等于 items 的长度。
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record PageView<T>(List<T> items, String nextCursor, long total) {

  public static <T> PageView<T> of(List<T> items) {
    return new PageView<>(items, null, items.size());
  }

  public static <T> PageView<T> of(List<T> items, long total) {
    return new PageView<>(items, null, total);
  }
}
