package com.demo.gameasset.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 任务趋势折线图上的一个点（每日新增 / 完成）。
 *
 * <p><b>空日必须补 0</b>：直接对 {@code task} 表按日期 {@code GROUP BY}，
 * 没有任何任务进出的那一天根本不会出现在结果里。折线图的类目轴会因此
 * 被压缩——两天的活动会被画成相邻的两个点，看上去像「连续两天都在干活」。
 * 所以服务层会先用日期维表把 [start, end] 每一天铺齐，再用统计结果去填。</p>
 *
 * @author 管理员
 */
@Data
public class TrendPointVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    /** 当日新建的任务数，空日为 0 */
    private Integer created;

    /** 当日完成的任务数（当天有 to_status='DONE' 的日志），空日为 0 */
    private Integer completed;

    public TrendPointVO() {
    }

    public TrendPointVO(LocalDate date, Integer created, Integer completed) {
        this.date = date;
        this.created = created;
        this.completed = completed;
    }
}
