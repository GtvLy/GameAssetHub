package com.zhangchi.gameasset.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 配置。
 *
 * <p>两个目的：</p>
 * <ol>
 *   <li>HTTP 层：{@code LocalDate} 输出 {@code yyyy-MM-dd}、
 *       {@code LocalDateTime} 输出 {@code yyyy-MM-dd HH:mm:ss}，
 *       与接口文档示例一致（默认的 ISO 数组格式前端很难用）；</li>
 *   <li>缓存层：给 {@code RedisCacheService} 提供一个注册了 JavaTimeModule 的
 *       {@link ObjectMapper}，否则往 Redis 写含时间字段的 VO 会直接抛
 *       {@code InvalidDefinitionException: Java 8 date/time type not supported}。</li>
 * </ol>
 *
 * @author 张忠驰
 */
@Configuration
public class JacksonConfig {

    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    public static final String DATE_PATTERN = "yyyy-MM-dd";

    public static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
    public static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern(DATE_PATTERN);

    /**
     * HTTP 序列化定制（Spring MVC 使用）。
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            builder.simpleDateFormat(DATE_TIME_PATTERN);
            builder.serializers(new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
            builder.serializers(new LocalDateSerializer(DATE_FORMATTER));
            builder.deserializers(new LocalDateTimeDeserializer(DATE_TIME_FORMATTER));
            builder.deserializers(new LocalDateDeserializer(DATE_FORMATTER));
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        };
    }

    /**
     * 缓存/工具用 ObjectMapper（与 HTTP 层共用同一套时间格式，避免两处格式不一致）。
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        JavaTimeModule javaTimeModule = new JavaTimeModule();
        javaTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
        javaTimeModule.addSerializer(LocalDate.class, new LocalDateSerializer(DATE_FORMATTER));
        javaTimeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME_FORMATTER));
        javaTimeModule.addDeserializer(LocalDate.class, new LocalDateDeserializer(DATE_FORMATTER));

        return new ObjectMapper()
                .registerModule(javaTimeModule)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }
}
