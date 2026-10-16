package com.zhangchi.gameasset.service.support;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评论里的 {@code @提及} 解析（文档 5.8：被 @ 的人收到 MENTION 通知）。
 *
 * <p><b>为什么不用一条正则搞定</b>：最自然的写法是 {@code @([\w一-龥]+)}
 * 一次匹配，但中文里人们习惯直接连着写——「{@code @张忠驰这个模型帮忙看下}」。
 * 正则会把「张忠驰这个模型帮忙看下」整段当成一个名字，结果一个人也 @ 不到。
 * 反过来，若允许随意短匹配，候选人是「li」和「liming」时
 * 「{@code @liming}」会被误判成 @ 了 li。</p>
 *
 * <p>因此改用<b>最长前缀匹配</b>：从 {@code @} 后面开始，在候选人名单里找
 * 匹配得最长的那个名字。再补一条边界规则——ASCII 名字后面不能直接跟字母数字
 * （否则 {@code @liming} 在只有 {@code li} 是候选人时会被误认），
 * 而含中文的名字允许直接接正文（否则 {@code @张忠驰这个…} 永远匹配不上）。</p>
 *
 * <p>纯静态、不依赖 Spring 与数据库：候选人名单由调用方给定，
 * 这样「谁能被 @」这条规则（必须是该项目的 ACTIVE 成员）留在 Service 层，
 * 本类只负责「文本里提到了谁」。</p>
 *
 * @author 张忠驰
 */
public final class MentionParser {

    private MentionParser() {
    }

    /**
     * 从评论内容里解析被 @ 的用户ID。
     *
     * @param content    评论正文
     * @param candidates 候选人：可被 @ 的文本 → 用户ID。
     *                   同一个用户可以有多个可匹配文本（登录名、昵称），
     *                   由调用方展开成多条；重复的名字以先放入的为准
     * @return 被提及的用户ID，按在正文中出现的先后去重
     */
    public static List<Long> resolve(String content, Map<String, Long> candidates) {
        if (!StringUtils.hasText(content) || candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }

        // 长名字优先，保证「最长前缀匹配」；长度相同则按文本字典序，避免结果随 Map 顺序漂移
        List<String> keys = new ArrayList<>(candidates.keySet());
        keys.removeIf(key -> !StringUtils.hasText(key));
        keys.sort(Comparator.comparingInt(String::length).reversed()
                .thenComparing(Comparator.naturalOrder()));

        Set<Long> mentioned = new LinkedHashSet<>();
        int cursor = 0;
        while (cursor < content.length()) {
            int at = content.indexOf('@', cursor);
            if (at < 0) {
                break;
            }
            // @ 前面若是字母或数字，说明这是邮箱之类的正文，不是提及
            if (at > 0 && isWordChar(content.charAt(at - 1))) {
                cursor = at + 1;
                continue;
            }
            String matched = longestMatch(content, at + 1, keys);
            if (matched == null) {
                cursor = at + 1;
                continue;
            }
            mentioned.add(candidates.get(matched));
            cursor = at + 1 + matched.length();
        }
        return new ArrayList<>(mentioned);
    }

    /** 在 {@code from} 处找匹配得最长的候选名字；都不匹配返回 null */
    private static String longestMatch(String content, int from, List<String> keysByLengthDesc) {
        for (String key : keysByLengthDesc) {
            int end = from + key.length();
            if (end > content.length()) {
                continue;
            }
            if (!content.regionMatches(true, from, key, 0, key.length())) {
                continue;
            }
            if (boundaryOk(content, end, key)) {
                return key;
            }
        }
        return null;
    }

    /**
     * 匹配到的名字后面是否算边界。
     *
     * <p>ASCII 名字（登录名）后面必须跟非字母数字，否则「@liming」会被
     * 误判成只 @ 了「li」；含中文的名字则允许直接连着正文写。</p>
     */
    private static boolean boundaryOk(String content, int end, String key) {
        if (end >= content.length()) {
            return true;
        }
        if (containsNonAscii(key)) {
            return true;
        }
        char next = content.charAt(end);
        return !isWordChar(next);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean containsNonAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把候选人名单展开成「可匹配文本 → 用户ID」。
     *
     * <p>登录名同时放一份小写形式：{@code @ZhangChi} 与 {@code @zhangchi}
     * 都应该能找到人。匹配本身用的是大小写不敏感的
     * {@code regionMatches}，这里只是为了覆盖「候选人自己就是大写」的情况。</p>
     *
     * <p>重名不覆盖已有映射：两个成员昵称相同时，先放入的（成员列表在前的人）生效，
     * 而不是随机命中其中一个——否则同一条评论在不同次解析里可能 @ 到不同的人。</p>
     */
    public static Map<String, Long> candidateMap(Map<Long, List<String>> namesByUserId) {
        Map<String, Long> candidates = new LinkedHashMap<>();
        if (namesByUserId == null) {
            return candidates;
        }
        namesByUserId.forEach((userId, names) -> {
            if (userId == null || names == null) {
                return;
            }
            for (String name : names) {
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                candidates.putIfAbsent(name.trim(), userId);
                candidates.putIfAbsent(name.trim().toLowerCase(), userId);
            }
        });
        return candidates;
    }

    /** 用户ID列表 → {@code task_comment.mention_ids} 的存储形式（逗号分隔） */
    public static String join(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (Long id : new LinkedHashSet<>(userIds)) {
            if (id == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(id);
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    /** {@code task_comment.mention_ids} → 用户ID列表；空值返回空列表 */
    public static List<Long> split(String mentionIds) {
        List<Long> ids = new ArrayList<>();
        if (!StringUtils.hasText(mentionIds)) {
            return ids;
        }
        for (String part : mentionIds.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.valueOf(trimmed));
            } catch (NumberFormatException ignored) {
                // 脏数据不该让整条评论渲染不出来，跳过即可
            }
        }
        return ids;
    }
}
