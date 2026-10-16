package com.zhangchi.gameasset.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 分页结果包装。
 *
 * <p>没有直接返回 MyBatis-Plus 的 {@code IPage}：那个对象带
 * {@code orders}、{@code optimizeCountSql}、{@code searchCount} 等一堆
 * 前端永远用不到的字段，直接序列化出去会让响应体变得难以阅读，
 * 而且把持久层实现暴露到了 API 契约里。</p>
 *
 * @param <T> 记录类型
 * @author 张忠驰
 */
@Data
public class PageResultVO<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 当前页数据 */
    private List<T> records = new ArrayList<>();

    /** 总记录数 */
    private Long total = 0L;

    /** 当前页码，从 1 开始 */
    private Long page = 1L;

    /** 每页条数 */
    private Long size = 20L;

    /** 总页数 */
    private Long pages = 0L;

    /** 是否还有下一页，前端「加载更多」用 */
    private Boolean hasNext = false;

    public static <T> PageResultVO<T> of(List<T> records, long total, long page, long size) {
        PageResultVO<T> result = new PageResultVO<>();
        result.setRecords(records == null ? new ArrayList<>() : records);
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        result.setPages(size <= 0 ? 0L : (total + size - 1) / size);
        result.setHasNext(page * size < total);
        return result;
    }

    /** 空结果（没有任何数据时也要给出正确的分页结构，前端不必判空） */
    public static <T> PageResultVO<T> empty(long page, long size) {
        return of(new ArrayList<>(), 0L, page, size);
    }
}
