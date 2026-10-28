package com.demo.gameasset.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 演示数据自检：{@code db/data.sql} 里的密码必须是<b>真实可用</b>的 BCrypt 密文。
 *
 * <p>为什么值得一个测试：data.sql 里的密文是手贴进去的一长串字符，
 * 看起来都差不多。一旦有人手抖改了哪怕一个字符（或者图省事写了个假字符串），
 * 表现是「演示账号登录失败」——而端到端验收的第一步就是登录，
 * 这个问题会让整个演示卡在门口，且排查方向很容易被误判成 JWT 或拦截器的问题。</p>
 *
 * <p>这里直接读源码目录下的 data.sql（它同时也在 classpath 的 {@code db/} 下），
 * 抽出所有 60 字符的 BCrypt 密文，断言它们都能用文档中约定的演示密码 123456 验证通过。</p>
 *
 * <p>纯逻辑测试：只做字符串解析与哈希校验，不加载 Spring、不连 MySQL / Redis。</p>
 *
 * @author 管理员
 */
@DisplayName("演示数据 data.sql 的密码密文自检")
class DemoAccountPasswordTest {

    /** 文档中约定的演示密码 */
    private static final String DEMO_RAW_PASSWORD = "123456";

    /** BCrypt 密文形如 $2a$10$ + 53 个 base64 字符，共 60 字符 */
    private static final Pattern BCRYPT_PATTERN = Pattern.compile("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}");

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private String readDataSql() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("db/data.sql")) {
            assertThat(in).as("db/data.sql 应当存在于 classpath（src/main/resources/db/）").isNotNull();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        }
    }

    private List<String> extractHashes(String sql) {
        List<String> hashes = new ArrayList<>();
        Matcher matcher = BCRYPT_PATTERN.matcher(sql);
        while (matcher.find()) {
            hashes.add(matcher.group());
        }
        return hashes;
    }

    @Test
    @DisplayName("data.sql 中每个密码密文都是合法的 60 字符 BCrypt 密文")
    void everyHashIsAWellFormedBcryptHash() throws Exception {
        List<String> hashes = extractHashes(readDataSql());

        assertThat(hashes).as("演示数据至少应包含两个演示账号的密文").hasSizeGreaterThanOrEqualTo(2);
        for (String hash : hashes) {
            assertThat(hash)
                    .as("BCrypt 密文固定 60 字符，写成假字符串会导致登录失败")
                    .hasSize(60)
                    .startsWith("$2a$10$");
        }
    }

    @Test
    @DisplayName("每个密文都能用演示密码 123456 通过校验（登录一定成功）")
    void everyHashMatchesTheDemoPassword() throws Exception {
        List<String> hashes = extractHashes(readDataSql());

        for (String hash : hashes) {
            assertThat(passwordEncoder.matches(DEMO_RAW_PASSWORD, hash))
                    .as("密文 %s 无法用演示密码 %s 校验通过，演示账号将登不进去",
                            hash, DEMO_RAW_PASSWORD)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("同一个密码的两个账号密文不同（BCrypt 每次加盐）")
    void hashesAreSaltedAndThereforeDistinct() throws Exception {
        List<String> hashes = extractHashes(readDataSql());
        assertThat(hashes).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("密文不会把明文密码写进脚本")
    void plaintextPasswordIsNotStored() throws Exception {
        String sql = readDataSql();
        // data.sql 的注释里出现了「123456」是允许的（说明文档），但不能以明文密码形式存入 password 列
        assertThat(extractHashes(sql)).isNotEmpty();
        assertThat(sql).doesNotContain("password) VALUES ('123456'");
    }
}
