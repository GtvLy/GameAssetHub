package com.demo.gameasset.service.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @提及} 解析测试（文档 5.8 的 MENTION 通知）。
 *
 * <p>盯住两个方向相反的错误：</p>
 * <ul>
 *   <li><b>漏</b>：中文里连着写「{@code @管理员这个模型帮忙看下}」时，
 *       若按「非空白字符」切词，整段会被当成一个名字，一个人也 @ 不到；</li>
 *   <li><b>错</b>：候选人有 {@code li} 时，{@code @liming} 会被误判成 @ 了 li，
 *       给不相干的人推通知。</li>
 * </ul>
 *
 * <p>纯逻辑测试：候选人名单由测试给定，不查库也不加载 Spring。</p>
 *
 * @author 管理员
 */
@DisplayName("MentionParser @提及解析")
class MentionParserTest {

    /** 星港计划的成员：登录名 + 中文昵称 */
    private static Map<String, Long> candidates() {
        Map<Long, List<String>> names = new LinkedHashMap<>();
        names.put(1L, List.of("demo", "管理员"));
        names.put(2L, List.of("liyang", "李阳"));
        names.put(4L, List.of("chenjing", "陈静"));
        return MentionParser.candidateMap(names);
    }

    @Test
    @DisplayName("登录名：@liyang 后面跟空格")
    void resolvesLoginName() {
        assertThat(MentionParser.resolve("@liyang 麻烦看下这个模型", candidates()))
                .containsExactly(2L);
    }

    @Test
    @DisplayName("中文昵称：@李阳 后面直接接正文也要能匹配上（中文没有天然分词）")
    void resolvesChineseNicknameFollowedByText() {
        assertThat(MentionParser.resolve("@李阳这个模型帮忙看下", candidates()))
                .containsExactly(2L);
    }

    @Test
    @DisplayName("登录名大小写不敏感：@Demo 也能找到人")
    void resolvesLoginNameCaseInsensitively() {
        assertThat(MentionParser.resolve("@Demo 看一下", candidates()))
                .containsExactly(1L);
    }

    @Test
    @DisplayName("候选人有 li 时，@liming 不会被误判成 @li")
    void doesNotTruncateAsciiNames() {
        Map<Long, List<String>> names = new LinkedHashMap<>();
        names.put(2L, List.of("li"));

        assertThat(MentionParser.resolve("@liming 看一下", MentionParser.candidateMap(names)))
                .as("ASCII 名字后面紧跟字母时不算匹配，否则会给 li 发一条莫名其妙的通知")
                .isEmpty();
    }

    @Test
    @DisplayName("候选人同时有 li 与 liming 时，取最长的那个（liming）")
    void prefersLongestMatch() {
        Map<Long, List<String>> names = new LinkedHashMap<>();
        names.put(2L, List.of("li"));
        names.put(5L, List.of("liming"));

        assertThat(MentionParser.resolve("@liming 看一下", MentionParser.candidateMap(names)))
                .containsExactly(5L);
    }

    @Test
    @DisplayName("邮箱里的 @ 不算提及")
    void ignoresAtSignInsideWords() {
        assertThat(MentionParser.resolve("发我邮箱 demo@liyang.com", candidates()))
                .as("@ 前面是字母，属于正文而不是提及")
                .isEmpty();
    }

    @Test
    @DisplayName("非项目成员不能被 @ 到（候选人名单里没有他）")
    void cannotMentionNonMember() {
        assertThat(MentionParser.resolve("@wangong 看一下", candidates())).isEmpty();
    }

    @Test
    @DisplayName("同一个人被写两次只算一次，且按出现顺序去重")
    void deduplicatesMentions() {
        assertThat(MentionParser.resolve("@李阳 @liyang @陈静", candidates()))
                .containsExactly(2L, 4L);
    }

    @Test
    @DisplayName("没有 @ 或内容为空时返回空列表，不抛异常")
    void toleratesNoMention() {
        assertThat(MentionParser.resolve("就是普通的一句话", candidates())).isEmpty();
        assertThat(MentionParser.resolve(null, candidates())).isEmpty();
        assertThat(MentionParser.resolve("@李阳", Map.of())).isEmpty();
    }

    @Test
    @DisplayName("只有 @ 没跟任何名字时跳过，继续找后面的提及")
    void skipsBareAtSign() {
        assertThat(MentionParser.resolve("这个 @ 符号 @李阳 看一下", candidates()))
                .containsExactly(2L);
    }

    @Test
    @DisplayName("候选人为空（项目里没有 ACTIVE 成员）时不会把任何人 @ 出来")
    void emptyCandidatesMentionNobody() {
        assertThat(MentionParser.resolve("@李阳 看一下", Map.of())).isEmpty();
    }

    // ==================== 存储形式的互转 ====================

    @Test
    @DisplayName("mention_ids 的拼接与解析是互逆的；空值返回 null / 空列表")
    void joinAndSplitRoundTrip() {
        assertThat(MentionParser.join(List.of(2L, 4L))).isEqualTo("2,4");
        assertThat(MentionParser.split("2,4")).containsExactly(2L, 4L);
        assertThat(MentionParser.join(List.of())).isNull();
        assertThat(MentionParser.join(null)).isNull();
        assertThat(MentionParser.split(null)).isEmpty();
        assertThat(MentionParser.split("  ")).isEmpty();
    }

    @Test
    @DisplayName("mention_ids 里的脏数据被跳过，而不是让整条评论渲染不出来")
    void splitSkipsGarbage() {
        assertThat(MentionParser.split("2,abc,,4")).containsExactly(2L, 4L);
    }
}
