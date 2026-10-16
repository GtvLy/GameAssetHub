package com.zhangchi.gameasset.service.support;

import com.zhangchi.gameasset.vo.AssetCategoryStatVO;
import com.zhangchi.gameasset.vo.AssetCompletionVO;
import com.zhangchi.gameasset.vo.BurndownPointVO;
import com.zhangchi.gameasset.vo.BurndownVO;
import com.zhangchi.gameasset.vo.StatCountVO;
import com.zhangchi.gameasset.vo.TaskTrendVO;
import com.zhangchi.gameasset.vo.TrendPointVO;
import com.zhangchi.gameasset.vo.WorkloadItemVO;
import com.zhangchi.gameasset.vo.WorkloadVO;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统计报表导出测试（模块4 的 {@code /stat/export}）。
 *
 * <p><b>断言的是「把生成的字节重新用 POI 打开、读单元格」</b>，
 * 而不是「字节数大于 0」。后者只能证明「有个文件」，证明不了
 * 「里面是我们的数据」——一个只写了表头、甚至写错工作表的文件
 * 同样能让它通过。这里逐项校验工作表名与关键单元格的<strong>文本</strong>，
 * 中文关键字必须原样出现，这顺带把「编码写错变成乱码」也钉住了。</p>
 *
 * <p>纯逻辑测试：不加载 Spring、不连数据库。</p>
 *
 * @author 张忠驰
 */
@DisplayName("StatExcelWriter 统计报表导出")
class StatExcelWriterTest {

    private final StatExcelWriter writer = new StatExcelWriter();

    private static AssetCompletionVO completion() {
        AssetCompletionVO vo = new AssetCompletionVO();
        vo.setProjectId(1L);
        vo.setAssetTotal(9);
        vo.setAssetApproved(2);
        vo.setAssetPending(2);
        vo.setAssetInProgress(2);
        vo.setAssetRejected(1);
        vo.setAssetDeprecated(2);
        vo.setCompletionRate(22.2);
        vo.setCompletionDefinition("完成率 = 已通过审核的资产数 ÷ 资产总数");

        List<StatCountVO> distribution = new ArrayList<>();
        distribution.add(count("APPROVED", "已通过", 2, 22.2));
        distribution.add(count("PENDING", "待审核", 2, 22.2));
        vo.setStatusDistribution(distribution);
        return vo;
    }

    private static StatCountVO count(String key, String label, int count, double ratio) {
        StatCountVO vo = new StatCountVO();
        vo.setKey(key);
        vo.setLabel(label);
        vo.setCount(count);
        vo.setRatio(ratio);
        return vo;
    }

    private static WorkloadVO workload() {
        WorkloadItemVO member = new WorkloadItemVO();
        member.setUserId(2L);
        member.setNickname("李阳");
        member.setRole("ARTIST");
        member.setRoleLabel("美术");
        member.setTaskTotal(6);
        member.setTaskDone(4);
        member.setTaskCompletionRate(66.7);
        member.setEstimateHours(BigDecimal.valueOf(30));
        member.setActualHours(BigDecimal.valueOf(24.5));
        member.setPoints(21);
        member.setVersionUploaded(3);
        member.setAssetApproved(2);

        WorkloadVO vo = new WorkloadVO();
        vo.setProjectId(1L);
        vo.setMemberCount(1);
        vo.setTaskTotalSum(6);
        vo.setAverageTask(6.0);
        vo.setStdDevTask(0.0);
        vo.setBalanceIndex(1.0);
        vo.setBalanceWarning(false);
        vo.setHint("");
        vo.setBalanceDefinition("负载均衡度 = 1 − 标准差 / 平均值");
        vo.setMembers(new ArrayList<>(List.of(member)));
        return vo;
    }

    private static TaskTrendVO trend() {
        TaskTrendVO vo = new TaskTrendVO();
        vo.setProjectId(1L);
        vo.setDays(3);
        vo.setStartDate(LocalDate.of(2026, 9, 22));
        vo.setEndDate(LocalDate.of(2026, 9, 24));
        vo.setPoints(new ArrayList<>(List.of(
                new TrendPointVO(LocalDate.of(2026, 9, 22), 1, 0),
                new TrendPointVO(LocalDate.of(2026, 9, 23), 0, 0),
                new TrendPointVO(LocalDate.of(2026, 9, 24), 2, 3))));
        vo.setTotalCreated(3);
        vo.setTotalCompleted(3);
        vo.setNetGrowth(0);
        return vo;
    }

    private static BurndownVO burndown() {
        BurndownVO vo = new BurndownVO();
        vo.setProjectId(1L);
        vo.setStartDate(LocalDate.of(2026, 9, 1));
        vo.setEndDate(LocalDate.of(2026, 12, 31));
        vo.setTotalPoints(63);
        vo.setCurrentPoints(45);
        vo.setScheduleStatusLabel("进度超前");
        vo.setIdealLine(new ArrayList<>(List.of(new BurndownPointVO(LocalDate.of(2026, 9, 1), 63))));
        vo.setActualLine(new ArrayList<>());
        return vo;
    }

    private List<AssetCategoryStatVO> categories() {
        AssetCategoryStatVO row = new AssetCategoryStatVO();
        row.setCategoryId(3L);
        row.setCategoryCode("PROP");
        row.setCategoryName("场景道具");
        row.setTotal(3);
        row.setApproved(1);
        row.setPending(1);
        row.setInProgress(1);
        row.setCompletionRate(33.3);
        return new ArrayList<>(List.of(row));
    }

    private byte[] generate() {
        return writer.write("星港计划", completion(), categories(), workload(), trend(),
                burndown(), LocalDateTime.of(2026, 9, 24, 15, 30, 0));
    }

    @Test
    @DisplayName("生成的文件是合法 xlsx（PK 魔数 + 五个工作表齐全）")
    void producesValidWorkbook() throws Exception {
        byte[] bytes = generate();

        assertThat(bytes).isNotEmpty();
        assertThat(bytes[0]).isEqualTo((byte) 'P');
        assertThat(bytes[1]).isEqualTo((byte) 'K');

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(5);
            assertThat(sheetNames(workbook))
                    .containsExactly("概览", "资产状态分布", "分类明细", "成员工作量", "任务趋势");
        }
    }

    @Test
    @DisplayName("概览页写进了项目名、生成时间与完成率的真实数字")
    void overviewContainsRealValues() throws Exception {
        try (Workbook workbook = open(generate())) {
            Sheet sheet = workbook.getSheet("概览");
            assertThat(cellText(sheet, 0, 0)).contains("星港计划");
            assertThat(cellText(sheet, 1, 0)).isEqualTo("生成时间");
            assertThat(cellText(sheet, 1, 1)).isEqualTo("2026-09-24 15:30:00");
            assertThat(numericCell(sheet, 2, 1)).isEqualTo(9.0);
            assertThat(numericCell(sheet, 3, 1)).isEqualTo(2.0);
            assertThat(numericCell(sheet, 4, 1)).isEqualTo(22.2);
        }
    }

    @Test
    @DisplayName("成员工作量页写出真实昵称与工时；数字是数值单元格而不是文本")
    void workloadSheet() throws Exception {
        try (Workbook workbook = open(generate())) {
            Sheet sheet = workbook.getSheet("成员工作量");
            assertThat(cellText(sheet, 0, 0)).isEqualTo("成员");
            assertThat(cellText(sheet, 0, 1)).isEqualTo("角色");
            assertThat(cellText(sheet, 1, 0)).isEqualTo("李阳");
            assertThat(cellText(sheet, 1, 1)).isEqualTo("美术");
            assertThat(numericCell(sheet, 1, 2)).isEqualTo(6.0);
            assertThat(numericCell(sheet, 1, 7)).isEqualTo(24.5);
            assertThat(numericCell(sheet, 1, 9)).isEqualTo(2.0);
        }
    }

    @Test
    @DisplayName("分类明细与任务趋势页同样带数据，且趋势里的空日补的 0 是真正的数值 0")
    void categoryAndTrendSheets() throws Exception {
        try (Workbook workbook = open(generate())) {
            Sheet category = workbook.getSheet("分类明细");
            assertThat(cellText(category, 1, 0)).isEqualTo("PROP");
            assertThat(cellText(category, 1, 1)).isEqualTo("场景道具");
            assertThat(numericCell(category, 1, 6)).isEqualTo(33.3);

            Sheet trendSheet = workbook.getSheet("任务趋势");
            assertThat(cellText(trendSheet, 2, 0)).isEqualTo("2026-09-23");
            assertThat(numericCell(trendSheet, 2, 1)).isZero();
            assertThat(numericCell(trendSheet, 2, 2)).isZero();
            assertThat(numericCell(trendSheet, 3, 2)).isEqualTo(3.0);
        }
    }

    @Test
    @DisplayName("燃尽图与工作量都为空时（新项目）也能导出，不抛异常")
    void toleratesEmptyInputs() throws Exception {
        byte[] bytes = writer.write("空项目", null, null, null, null, null,
                LocalDateTime.of(2026, 9, 24, 9, 0, 0));

        try (Workbook workbook = open(bytes)) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(5);
            assertThat(cellText(workbook.getSheet("成员工作量"), 0, 0)).isEqualTo("成员");
            assertThat(cellText(workbook.getSheet("概览"), 0, 0)).contains("空项目");
        }
    }

    // ==================== 工具 ====================

    private Workbook open(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    private List<String> sheetNames(Workbook workbook) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            names.add(workbook.getSheetName(i));
        }
        return names;
    }

    private String cellText(Sheet sheet, int rowIndex, int columnIndex) {
        Row row = sheet.getRow(rowIndex);
        assertThat(row).as("第 %d 行应存在", rowIndex).isNotNull();
        return row.getCell(columnIndex).getStringCellValue();
    }

    private double numericCell(Sheet sheet, int rowIndex, int columnIndex) {
        Row row = sheet.getRow(rowIndex);
        assertThat(row).as("第 %d 行应存在", rowIndex).isNotNull();
        return row.getCell(columnIndex).getNumericCellValue();
    }
}
