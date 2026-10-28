package com.demo.gameasset.service.support;

import com.demo.gameasset.vo.AssetCategoryStatVO;
import com.demo.gameasset.vo.AssetCompletionVO;
import com.demo.gameasset.vo.BurndownVO;
import com.demo.gameasset.vo.StatCountVO;
import com.demo.gameasset.vo.TaskTrendVO;
import com.demo.gameasset.vo.TrendPointVO;
import com.demo.gameasset.vo.WorkloadItemVO;
import com.demo.gameasset.vo.WorkloadVO;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 统计报表 xlsx 生成（模块4 的 {@code /stat/export}）。
 *
 * <p>用 Apache POI 的 {@link XSSFWorkbook} 手写五个工作表：概览、资产状态分布、
 * 分类明细、成员工作量、任务趋势。之所以不用「模板文件 + 填充」：
 * 指标口径会随模块演进，模板文件与代码分处两地，
 * 改了字段忘了改模板的表现是「导出的表头还是上个月的」。</p>
 *
 * <p>纯计算 + 纯内存：不依赖 Spring 上下文（虽然打了 {@code @Component}
 * 以便注入），入参就是已经算好的 VO，出参是字节数组。
 * 因此单测可以直接「生成 → 用 POI 打开 → 断言单元格」，
 * 不需要连数据库，也不需要真的发一次 HTTP。</p>
 *
 * <p>数字列写成真正的数值而不是字符串：导出的表是要被 Excel 再加工的
 * （排序、求平均），一列「看起来是数字的文本」会让这些操作静默失效。</p>
 *
 * @author 管理员
 */
@Component
public class StatExcelWriter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 生成统计报表。
     *
     * @param projectName 项目名（写进概览页与文件名）
     * @param completion  资产完成率与状态分布
     * @param categories  分类明细
     * @param workload    成员工作量
     * @param trend       任务趋势
     * @param burndown    燃尽图（可为 null：计划日期不完整时也允许导出）
     * @param generatedAt 生成时刻
     */
    public byte[] write(String projectName,
                        AssetCompletionVO completion,
                        List<AssetCategoryStatVO> categories,
                        WorkloadVO workload,
                        TaskTrendVO trend,
                        BurndownVO burndown,
                        LocalDateTime generatedAt) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            CellStyle header = headerStyle(workbook);

            writeOverview(workbook, projectName, completion, workload, burndown, generatedAt);
            writeStatusDistribution(workbook, header, completion);
            writeCategories(workbook, header, categories);
            writeWorkload(workbook, header, workload);
            writeTrend(workbook, header, trend);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            // 写进内存还失败基本只可能是 OOM 级别的意外，包成非受检异常交给全局处理器
            throw new UncheckedIOException("生成统计报表失败", e);
        }
    }

    // ==================== 各工作表 ====================

    private void writeOverview(Workbook wb, String projectName,
                               AssetCompletionVO completion, WorkloadVO workload,
                               BurndownVO burndown, LocalDateTime generatedAt) {
        Sheet sheet = wb.createSheet("概览");
        int r = 0;
        r = title(sheet, r, projectName + " 统计报表");
        r = kv(sheet, r, "生成时间", generatedAt == null ? "—" : generatedAt.format(DATE_TIME));
        r = kv(sheet, r, "资产总数", completion == null ? 0 : nz(completion.getAssetTotal()));
        r = kv(sheet, r, "已通过审核", completion == null ? 0 : nz(completion.getAssetApproved()));
        r = kv(sheet, r, "资产完成率(%)", completion == null ? 0.0 : nz(completion.getCompletionRate()));
        r = kv(sheet, r, "完成率口径",
                completion == null ? "—" : completion.getCompletionDefinition());
        r++;
        r = kv(sheet, r, "成员数", workload == null ? 0 : nz(workload.getMemberCount()));
        r = kv(sheet, r, "任务数合计", workload == null ? 0 : nz(workload.getTaskTotalSum()));
        r = kv(sheet, r, "人均任务数", workload == null ? 0.0 : nz(workload.getAverageTask()));
        r = kv(sheet, r, "负载均衡度", workload == null ? 1.0 : nz(workload.getBalanceIndex()));
        r = kv(sheet, r, "均衡度口径", workload == null ? "—" : workload.getBalanceDefinition());
        if (workload != null && Boolean.TRUE.equals(workload.getBalanceWarning())) {
            r = kv(sheet, r, "均衡提示", workload.getHint());
        }
        if (burndown != null) {
            r++;
            r = kv(sheet, r, "计划区间", text(burndown.getStartDate()) + " ~ " + text(burndown.getEndDate()));
            r = kv(sheet, r, "总故事点", nz(burndown.getTotalPoints()));
            r = kv(sheet, r, "当前剩余故事点", nz(burndown.getCurrentPoints()));
            r = kv(sheet, r, "进度判定", burndown.getScheduleStatusLabel());
        }
        sheet.setColumnWidth(0, 22 * 256);
        sheet.setColumnWidth(1, 60 * 256);
    }

    private void writeStatusDistribution(Workbook wb, CellStyle header, AssetCompletionVO completion) {
        Sheet sheet = wb.createSheet("资产状态分布");
        String[] cols = {"状态", "数量", "占比(%)"};
        headerRow(sheet, header, cols);
        List<StatCountVO> rows = completion == null ? List.of() : completion.getStatusDistribution();
        int r = 1;
        for (StatCountVO item : rows) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(item.getLabel() == null ? item.getKey() : item.getLabel());
            row.createCell(1).setCellValue(nz(item.getCount()));
            row.createCell(2).setCellValue(item.getRatio() == null ? 0.0 : item.getRatio().doubleValue());
        }
        autoWidth(sheet, cols.length);
    }

    private void writeCategories(Workbook wb, CellStyle header, List<AssetCategoryStatVO> categories) {
        Sheet sheet = wb.createSheet("分类明细");
        String[] cols = {"分类编码", "分类名称", "资产总数", "已通过", "待审核", "制作中", "完成率(%)"};
        headerRow(sheet, header, cols);
        int r = 1;
        if (categories != null) {
            for (AssetCategoryStatVO item : categories) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(text(item.getCategoryCode()));
                row.createCell(1).setCellValue(text(item.getCategoryName()));
                row.createCell(2).setCellValue(nz(item.getTotal()));
                row.createCell(3).setCellValue(nz(item.getApproved()));
                row.createCell(4).setCellValue(nz(item.getPending()));
                row.createCell(5).setCellValue(nz(item.getInProgress()));
                row.createCell(6).setCellValue(item.getCompletionRate() == null ? 0.0 : item.getCompletionRate());
            }
        }
        autoWidth(sheet, cols.length);
    }

    private void writeWorkload(Workbook wb, CellStyle header, WorkloadVO workload) {
        Sheet sheet = wb.createSheet("成员工作量");
        String[] cols = {"成员", "角色", "任务数", "已完成", "完成率(%)", "故事点",
                "预估工时", "实际工时", "上传版本", "通过资产"};
        headerRow(sheet, header, cols);
        int r = 1;
        if (workload != null && workload.getMembers() != null) {
            for (WorkloadItemVO m : workload.getMembers()) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(text(m.getNickname()));
                row.createCell(1).setCellValue(text(m.getRoleLabel()));
                row.createCell(2).setCellValue(nz(m.getTaskTotal()));
                row.createCell(3).setCellValue(nz(m.getTaskDone()));
                row.createCell(4).setCellValue(m.getTaskCompletionRate() == null ? 0.0 : m.getTaskCompletionRate());
                row.createCell(5).setCellValue(nz(m.getPoints()));
                row.createCell(6).setCellValue(decimal(m.getEstimateHours()));
                row.createCell(7).setCellValue(decimal(m.getActualHours()));
                row.createCell(8).setCellValue(nz(m.getVersionUploaded()));
                row.createCell(9).setCellValue(nz(m.getAssetApproved()));
            }
        }
        autoWidth(sheet, cols.length);
    }

    private void writeTrend(Workbook wb, CellStyle header, TaskTrendVO trend) {
        Sheet sheet = wb.createSheet("任务趋势");
        String[] cols = {"日期", "新增任务", "完成任务"};
        headerRow(sheet, header, cols);
        int r = 1;
        if (trend != null && trend.getPoints() != null) {
            for (TrendPointVO point : trend.getPoints()) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(text(point.getDate()));
                row.createCell(1).setCellValue(nz(point.getCreated()));
                row.createCell(2).setCellValue(nz(point.getCompleted()));
            }
        }
        autoWidth(sheet, cols.length);
    }

    // ==================== 样式与工具 ====================

    private int title(Sheet sheet, int rowIndex, String text) {
        Row row = sheet.createRow(rowIndex);
        Cell cell = row.createCell(0);
        cell.setCellValue(text);
        Font font = sheet.getWorkbook().createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        CellStyle style = sheet.getWorkbook().createCellStyle();
        style.setFont(font);
        cell.setCellStyle(style);
        return rowIndex + 1;
    }

    private int kv(Sheet sheet, int rowIndex, String key, Object value) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(key);
        Cell cell = row.createCell(1);
        if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof LocalDate date) {
            cell.setCellValue(date.format(DATE));
        } else {
            cell.setCellValue(value == null ? "" : String.valueOf(value));
        }
        return rowIndex + 1;
    }

    private void headerRow(Sheet sheet, CellStyle style, String[] columns) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < columns.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(columns[i]);
            cell.setCellStyle(style);
        }
        sheet.createFreezePane(0, 1);
    }

    private CellStyle headerStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private void autoWidth(Sheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            sheet.setColumnWidth(i, 16 * 256);
        }
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private double nz(Double value) {
        return value == null ? 0.0 : value;
    }

    private double decimal(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    private String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof LocalDate date) {
            return date.format(DATE);
        }
        return String.valueOf(value);
    }
}
