package com.demo.gameasset.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 燃尽图上的一个点（文档 4.4 燃尽图响应示例的 {@code {date, points}}）。
 *
 * <p>{@code date} 显式用 {@code @JsonFormat} 固定成 {@code yyyy-MM-dd}：
 * 全局 Jackson 配置里没有对 {@code LocalDate} 单独开
 * {@code WRITE_DATES_AS_TIMESTAMPS=false} 的保证，一旦序列化成
 * {@code [2026,9,1]} 这种数组，前端 ECharts 的类目轴会把整条线画歪。</p>
 *
 * @author 管理员
 */
@Data
public class BurndownPointVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    /** 该日期点位上仍未完成的故事点合计 */
    private Integer points;

    public BurndownPointVO() {
    }

    public BurndownPointVO(LocalDate date, Integer points) {
        this.date = date;
        this.points = points;
    }
}
