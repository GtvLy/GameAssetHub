package com.demo.gameasset.service.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.demo.gameasset.common.constant.RedisKeys;
import com.demo.gameasset.entity.Asset;
import com.demo.gameasset.mapper.AssetMapper;
import com.demo.gameasset.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 资产编号生成器（文档 3.3 第 1 点）。
 *
 * <p>格式 {@code {分类code}_{序号}}，如 {@code CHR_SM_0001}。
 * 序号在<b>项目 + 分类</b>维度自增，靠 Redis {@code INCR} 保证原子性。</p>
 *
 * <p><b>为什么不用数据库自增主键当编号</b>：文档说得很清楚——
 * 编号要被团队成员口述引用（「你看下 CHR_SM_0003 那个箱子」），
 * 而自增主键既不由分类决定、又会被跨项目穿插，口述时无法自证。</p>
 *
 * <p><b>Redis 挂了怎么办</b>：不抛错、也不假装成功，而是退化为查库兜底
 * （数一遍该分类下已有的编号，+1）。兜底结果可能与他人并发重复，
 * 但调用方还有「查重 + 重试」和数据库 {@code uk_project_asset_no} 唯一约束
 * 两道保险。编号生成失败不该让「新建资产」这个核心动作直接不可用。</p>
 *
 * @author 管理员
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetNoGenerator {

    /** 序号位数：{@code %04d}，超过 9999 时自然进位到 5 位，不会截断 */
    private static final String SEQ_FORMAT = "%04d";

    private final RedisCacheService redisCacheService;
    private final AssetMapper assetMapper;

    /**
     * 生成下一个资产编号。
     *
     * <p>本方法只保证「生成一个符合格式的号」，不保证全局唯一——
     * 唯一性由调用方的查重重试与数据库唯一约束共同保证。
     * 把这一步单独拆出来，是为了让「格式与序号来源」可以被单元测试直接验证。</p>
     */
    public String next(Long projectId, String categoryCode) {
        Long seq = redisCacheService.increment(RedisKeys.assetNoSeq(projectId, categoryCode));
        if (seq == null) {
            log.warn("[资产编号] Redis 自增不可用，退化为查库兜底 projectId={} code={}", projectId, categoryCode);
            seq = fallbackSeq(projectId, categoryCode);
        }
        return format(categoryCode, seq);
    }

    /**
     * 拼接编号。抽成静态方法便于测试格式本身，不必构造整个组件。
     *
     * @param categoryCode 分类编码，如 {@code CHR_SM}
     * @param seq          序号，从 1 开始
     */
    public static String format(String categoryCode, long seq) {
        return categoryCode + "_" + String.format(Locale.ROOT, SEQ_FORMAT, Math.max(seq, 1));
    }

    /**
     * 查库兜底：数出该分类下已有的编号数量 + 1。
     *
     * <p>用 {@code likeRight}（{@code asset_no LIKE 'CHR_SM_%'}）而不是精确匹配，
     * 因为这里要的是「这一类已经用了多少个号」。MyBatis-Plus 会为带
     * {@code @TableLogic} 的实体自动追加 {@code deleted = 0}。</p>
     */
    private long fallbackSeq(Long projectId, String categoryCode) {
        Long count = assetMapper.selectCount(
                Wrappers.<Asset>lambdaQuery()
                        .eq(Asset::getProjectId, projectId)
                        .likeRight(Asset::getAssetNo, categoryCode + "_"));
        return (count == null ? 0L : count) + 1;
    }
}
