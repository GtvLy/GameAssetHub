package com.zhangchi.gameasset.config;

import com.zhangchi.gameasset.interceptor.JwtInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册鉴权拦截器 + CORS。
 *
 * @author 张忠驰
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    /** 免登录白名单：注册与登录本身当然不能要求带令牌 */
    private static final String[] AUTH_WHITE_LIST = {
            "/api/auth/login",
            "/api/auth/register"
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(AUTH_WHITE_LIST)
                .order(1);
    }

    /**
     * 跨域配置。
     * 开发期前端跑在 5175 端口，Vite 走代理时同源、不触发 CORS；
     * 这里保留直连能力（例如后续接 Swagger 或用其他工具调接口）。
     * 注意：allowCredentials(true) 时不能用 allowedOrigins("*")，必须用
     * allowedOriginPatterns，否则 Spring 启动即抛 IllegalArgumentException。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
