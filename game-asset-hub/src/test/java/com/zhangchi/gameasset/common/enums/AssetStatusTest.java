package com.zhangchi.gameasset.common.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资产状态流转白名单（文档 5.3 {@code STATUS_FLOW}）。
 *
 * <p>这是模块2 最该被穷举测试的一块：状态机的错误不会抛异常，
 * 只会让某个不该发生的组合悄悄发生（例如 {@code APPROVED → PENDING}
 * 让已通过的资产回到待审核队列）。这里把 5×5 = 25 种组合<b>全部</b>列出，
 * 每一格都有明确期望值，改动白名单时会立刻看到哪一格被改变了。</p>
 *
 * <p>纯逻辑测试：不加载 Spring、不连 MySQL / Redis。</p>
 *
 * @author 张忠驰
 */
@DisplayName("AssetStatus 资产状态流转白名单")
class AssetStatusTest {

    @Nested
    @DisplayName("合法流转（文档 5.3 的每一条箭头）")
    class LegalTransitions {

        @ParameterizedTest(name = "{0} → {1} 应当允许")
        @CsvSource({
                // IN_PROGRESS 提交审核 / 直接放弃
                "IN_PROGRESS, PENDING",
                "IN_PROGRESS, DEPRECATED",
                // PENDING 的两种审核结论
                "PENDING, APPROVED",
                "PENDING, REJECTED",
                // REJECTED 可重新提交、可退回继续改、也可放弃
                "REJECTED, PENDING",
                "REJECTED, IN_PROGRESS",
                "REJECTED, DEPRECATED",
                // APPROVED 可废弃，也可退回继续修改
                "APPROVED, DEPRECATED",
                "APPROVED, IN_PROGRESS",
                // DEPRECATED 可重新启用
                "DEPRECATED, IN_PROGRESS"
        })
        void allowsWhitelistedTransitions(String from, String to) {
            assertThat(AssetStatus.canTransfer(from, to))
                    .as("%s → %s 在文档 5.3 的白名单内", from, to)
                    .isTrue();
        }

        @Test
        @DisplayName("白名单共 10 条边，一条不多一条不少")
        void whitelistHasExactlyTenEdges() {
            int edges = 0;
            for (AssetStatus from : AssetStatus.values()) {
                edges += AssetStatus.nextStatuses(from.name()).size();
            }
            // 2 (IN_PROGRESS) + 2 (PENDING) + 2 (APPROVED) + 3 (REJECTED) + 1 (DEPRECATED)
            assertThat(edges).as("文档 5.3 明确列出的合法流转共 10 条").isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("非法流转（文档 9 节点名的 APPROVED → PENDING 等）")
    class IllegalTransitions {

        @ParameterizedTest(name = "{0} → {1} 应当拒绝")
        @CsvSource({
                // 文档 9 节点名的用例：已通过的资产不能回到待审核队列，
                // 否则「审核通过」这道关就能被绕过
                "APPROVED, PENDING",
                // 不能跳过审核直接通过
                "IN_PROGRESS, APPROVED",
                // 不能在没提交审核的情况下被打回
                "IN_PROGRESS, REJECTED",
                // 待审核中的资产不能被直接废弃（必须先有审核结论）
                "PENDING, DEPRECATED",
                // 待审核中的资产不能自己退回制作中
                "PENDING, IN_PROGRESS",
                // 废弃的资产不能再被审核通过或打回
                "DEPRECATED, APPROVED",
                "DEPRECATED, REJECTED",
                "DEPRECATED, PENDING",
                // 已通过/已打回的资产之间不能互相跳
                "APPROVED, REJECTED",
                "REJECTED, APPROVED"
        })
        void rejectsNonWhitelistedTransitions(String from, String to) {
            assertThat(AssetStatus.canTransfer(from, to))
                    .as("%s → %s 不在白名单内，必须拒绝", from, to)
                    .isFalse();
        }

        @Test
        @DisplayName("五五二十五种组合逐一核对：只有 10 种被允许，其余 15 种一律拒绝")
        void everyCombinationIsAccountedFor() {
            int allowed = 0;
            int denied = 0;
            for (AssetStatus from : AssetStatus.values()) {
                for (AssetStatus to : AssetStatus.values()) {
                    if (AssetStatus.canTransfer(from.name(), to.name())) {
                        allowed++;
                    } else {
                        denied++;
                    }
                }
            }
            assertThat(allowed).as("允许的组合数").isEqualTo(10);
            assertThat(denied).as("25 - 10 = 15 种组合被拒绝，其中 5 种是自流转").isEqualTo(15);
        }

        @Test
        @DisplayName("自身到自身不算流转：重复点两次「提交审核」不应写出两条历史")
        void rejectsSelfTransitionForEveryStatus() {
            for (AssetStatus status : AssetStatus.values()) {
                assertThat(AssetStatus.canTransfer(status.name(), status.name()))
                        .as("%s → %s 必须拒绝", status, status)
                        .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("边界与非法输入：默认拒绝")
    class Boundaries {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "DELETED", "IN PROGRESS", "ARCHIVED", "PENDINGX", "0"})
        @DisplayName("未知/空/畸形状态一律拒绝，不存在「拿不准就放行」")
        void rejectsUnknownInput(String weird) {
            assertThat(AssetStatus.canTransfer(weird, "PENDING")).isFalse();
            assertThat(AssetStatus.canTransfer("PENDING", weird)).isFalse();
        }

        @Test
        @DisplayName("从 null 状态出发的任何流转都拒绝（脏数据的兜底）")
        void rejectsNullSource() {
            for (AssetStatus to : AssetStatus.values()) {
                assertThat(AssetStatus.canTransfer(null, to.name())).isFalse();
            }
        }

        @Test
        @DisplayName("nextStatuses：未知状态返回空集合而不是 null")
        void nextStatusesOfUnknownStatusIsEmpty() {
            assertThat(AssetStatus.nextStatuses(null)).isEmpty();
            assertThat(AssetStatus.nextStatuses("NOT_A_STATUS")).isEmpty();
        }

        @ParameterizedTest
        @CsvSource({
                "IN_PROGRESS, 2",
                "PENDING, 2",
                "APPROVED, 2",
                "REJECTED, 3",
                "DEPRECATED, 1"
        })
        @DisplayName("各状态的可选下一步数量与文档一致")
        void nextStatusesCountMatchesDocument(String from, int expected) {
            assertThat(AssetStatus.nextStatuses(from)).hasSize(expected);
        }

        @Test
        @DisplayName("nextStatuses 保持稳定顺序，前端按钮不会每次刷新都换位置")
        void nextStatusesKeepsStableOrder() {
            assertThat(AssetStatus.nextStatuses("IN_PROGRESS"))
                    .containsExactly("PENDING", "DEPRECATED");
            assertThat(AssetStatus.nextStatuses("PENDING"))
                    .containsExactly("APPROVED", "REJECTED");
            assertThat(AssetStatus.nextStatuses("REJECTED"))
                    .containsExactly("PENDING", "IN_PROGRESS", "DEPRECATED");
        }
    }

    @Nested
    @DisplayName("状态解析与展示")
    class Resolution {

        @ParameterizedTest
        @CsvSource({
                "IN_PROGRESS, 制作中",
                "PENDING, 待审核",
                "APPROVED, 已通过",
                "REJECTED, 已打回",
                "DEPRECATED, 已废弃"
        })
        void labelsMatchDdlComments(String status, String label) {
            assertThat(AssetStatus.label(status)).isEqualTo(label);
        }

        @Test
        @DisplayName("小写与前后空白都能识别（数据库里的值不该带空白，但也不该因此崩掉）")
        void ofIsCaseAndWhitespaceInsensitive() {
            assertThat(AssetStatus.of("approved")).isEqualTo(AssetStatus.APPROVED);
            assertThat(AssetStatus.of("  PENDING  ")).isEqualTo(AssetStatus.PENDING);
        }

        @Test
        @DisplayName("isValid 只认五个合法值")
        void isValidAcceptsOnlyFiveStatuses() {
            assertThat(AssetStatus.all()).hasSize(5);
            assertThat(AssetStatus.isValid("APPROVED")).isTrue();
            assertThat(AssetStatus.isValid("DELETED")).isFalse();
            assertThat(AssetStatus.isValid(null)).isFalse();
        }

        @Test
        @DisplayName("未知状态的标签原样返回，便于在界面上认出脏数据")
        void labelOfUnknownStatusFallsBackToRawValue() {
            assertThat(AssetStatus.label("SOMETHING_ELSE")).isEqualTo("SOMETHING_ELSE");
            assertThat(AssetStatus.label(null)).isEqualTo("null");
        }
    }
}
