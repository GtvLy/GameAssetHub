package com.zhangchi.gameasset.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关（模块2 的分片残留清理依赖它）。
 *
 * <p>单独一个类而不是把 {@code @EnableScheduling} 挂到启动类上：
 * 后续模块（模块3 的截止提醒、模块4 的报表快照）都会加定时任务，
 * 让「本应用会跑定时任务」这件事有一个明确的落点，
 * 而不是藏在 {@code GameAssetHubApplication} 的注解堆里。</p>
 *
 * @author 张忠驰
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
