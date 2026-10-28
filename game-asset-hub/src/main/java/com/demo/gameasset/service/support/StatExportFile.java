package com.demo.gameasset.service.support;

/**
 * 导出结果：文件名 + 文件内容。
 *
 * <p>刻意不让 Service 直接往 {@code HttpServletResponse} 里写：
 * 那样 Service 就必须认识 Servlet 类型，单元测试得造一个 Response 假对象，
 * 而且「中文文件名怎么编码」这种 Web 层的琐事会渗到业务层。
 * Service 只负责「产出一个文件名与一堆字节」，写响应头是 Controller 的事。</p>
 *
 * <p>用 record 而不是再加一个 VO：它有两个字段、没有行为、不进 JSON 响应，
 * 生成访问器与构造器之外的样板代码都是噪音。</p>
 *
 * @param fileName 文件名（含扩展名，如 {@code 星港计划-统计报表-20260924.xlsx}）
 * @param content  xlsx 字节内容
 * @author 管理员
 */
public record StatExportFile(String fileName, byte[] content) {
}
