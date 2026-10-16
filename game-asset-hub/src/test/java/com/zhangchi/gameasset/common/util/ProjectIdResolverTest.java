package com.zhangchi.gameasset.common.util;

import com.zhangchi.gameasset.common.annotation.ProjectId;
import com.zhangchi.gameasset.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 切面里 projectId 解析的单元测试。
 *
 * <p>这段逻辑是最该被测试盯住的地方：解析错了，RBAC 会拿<b>另一个项目</b>的
 * 成员关系去校验，从而直接放行越权。特别是
 * {@code /api/assets/{id}} 这类路径里的 {@code id} 是资产ID而不是项目ID，
 * 一旦被误认成项目ID，后端就会拿资产ID当项目ID去查成员表，
 * 结果是「查不到 → 403」看上去正常，但也可能因 ID 巧合而放行。</p>
 *
 * <p>纯逻辑测试：只做反射读注解 + 值解析，不加载 Spring、不连库。</p>
 *
 * @author 张忠驰
 */
@DisplayName("ProjectIdResolver 项目ID 解析")
class ProjectIdResolverTest {

    /** 模拟真实的 Controller 方法签名 */
    @SuppressWarnings("unused")
    public static class FakeController {

        /** 模块2/3 最常见的写法：路径变量直接叫 projectId */
        public void withProjectId(@PathVariable("projectId") Long projectId) {
        }

        /** 简写形式 */
        public void withPid(@PathVariable("pid") Long pid) {
        }

        /** 模块1 的写法：/api/projects/{id} */
        public void withBareId(@PathVariable("id") Long id) {
        }

        /** 资产接口：/api/assets/{id}，这里的 id 是资产ID */
        public void withAssetId(@PathVariable("id") Long id) {
        }

        /** 显式标注，优先级最高 */
        public void withAnnotated(@ProjectId Long anything) {
        }

        /** 同名参数：@ProjectId 标注的那个应当胜出，而不是先命中的 pid */
        public void withBothAnnotatedAndPid(@PathVariable("pid") Long pid,
                                            @ProjectId Long realProjectId) {
        }

        /** 只能靠请求头兜底 */
        public void withRequestOnly(HttpServletRequest request) {
        }

        /** 没有任何可取之处 */
        public void withNothing(Long notAProjectId) {
        }

        /** 一个参数都没有 */
        public void withNoArgs() {
        }
    }

    private static final String PROJECTS_URI = "/api/projects/7";
    private static final String ASSET_URI = "/api/assets/128";

    private Method method(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return FakeController.class.getMethod(name, parameterTypes);
    }

    /**
     * 大多数用例只关心「路径能不能解析出项目」，不涉及请求头兜底。
     * 统一从这里进入，让每个用例不必都写第四个 null。
     */
    private static Long resolveNoHeader(Method m, Object[] args, String uri) {
        return ProjectIdResolver.resolve(m, args, uri, null);
    }

    @Test
    @DisplayName("资产路径 + X-Project-Id：取请求头里的项目，绝不拿资产ID顶替")
    void resolvesFromHeaderOnAssetPath() throws Exception {
        Method m = method("withAssetId", Long.class);
        // 这是生产路径的真实形态：AssetController 不声明 HttpServletRequest 参数，
        // 请求头由切面取出后传进来。少了这条，/api/assets/** 会全部 400
        assertThat(ProjectIdResolver.resolve(m, new Object[]{128L}, ASSET_URI, "55"))
                .as("必须取请求头里的 55，而不是路径里的资产ID 128")
                .isEqualTo(55L);
        assertThat(ProjectIdResolver.resolve(m, new Object[]{128L}, ASSET_URI, "  55  "))
                .as("头值两端空白应被容忍")
                .isEqualTo(55L);
        assertThat(ProjectIdResolver.resolve(m, new Object[]{128L}, "/api/assets/upload/init", "7"))
                .as("上传类接口同样靠请求头定位项目")
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("请求头缺失/非法时仍然 400，不得回退到资产ID")
    void rejectsAssetPathWithoutUsableHeader() throws Exception {
        Method m = method("withAssetId", Long.class);
        assertThatThrownBy(() -> ProjectIdResolver.resolve(m, new Object[]{128L}, ASSET_URI, null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ProjectIdResolver.resolve(m, new Object[]{128L}, ASSET_URI, "abc"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ProjectIdResolver.resolve(m, new Object[]{128L}, ASSET_URI, ""))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("路径里的项目ID优先于请求头：头指向别的项目也不能取错")
    void pathWinsOverHeader() throws Exception {
        Method m = method("withBareId", Long.class);
        assertThat(ProjectIdResolver.resolve(m, new Object[]{7L}, PROJECTS_URI, "99"))
                .as("路径说是项目 7、头说是 99 —— 以路径为准，否则改个头就能越权")
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("@PathVariable(\"projectId\") 直接解析")
    void resolvesProjectIdPathVariable() throws Exception {
        Method m = method("withProjectId", Long.class);
        assertThat(resolveNoHeader(m, new Object[]{42L}, "/api/projects/42/assets"))
                .isEqualTo(42L);
    }

    @Test
    @DisplayName("@PathVariable(\"pid\") 直接解析")
    void resolvesPidPathVariable() throws Exception {
        Method m = method("withPid", Long.class);
        assertThat(resolveNoHeader(m, new Object[]{9L}, "/api/projects/9/tasks"))
                .isEqualTo(9L);
    }

    @Test
    @DisplayName("/api/projects/{id} 里的裸 id 认定为项目ID")
    void resolvesBareIdOnProjectScopedPath() throws Exception {
        Method m = method("withBareId", Long.class);
        assertThat(resolveNoHeader(m, new Object[]{7L}, PROJECTS_URI)).isEqualTo(7L);
        assertThat(resolveNoHeader(m, new Object[]{7L}, "/api/projects/7/overview"))
                .isEqualTo(7L);
        assertThat(resolveNoHeader(m, new Object[]{7L}, "/api/projects/7/members/2"))
                .isEqualTo(7L);
        assertThat(resolveNoHeader(m, new Object[]{7L}, "/api/project/7"))
                .as("单数 project 段同样识别")
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("/api/assets/{id} 里的裸 id 不得被当成项目ID")
    void doesNotTreatAssetIdAsProjectId() throws Exception {
        Method m = method("withAssetId", Long.class);
        // 关键安全用例：资产路径下的 id 必须解析失败，而不是把资产ID当项目ID用
        assertThatThrownBy(() -> resolveNoHeader(m, new Object[]{128L}, ASSET_URI))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无法解析项目ID");
    }

    @Test
    @DisplayName("路径含查询串时仍能正确识别 projects 段")
    void handlesQueryString() throws Exception {
        Method m = method("withAssetId", Long.class);
        assertThatThrownBy(() -> resolveNoHeader(m, new Object[]{128L},
                "/api/assets/128?projectId=7"))
                .isInstanceOf(BizException.class);

        Method projectIdMethod = method("withBareId", Long.class);
        assertThat(resolveNoHeader(projectIdMethod, new Object[]{7L},
                "/api/projects/7?withMembers=true"))
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("@ProjectId 显式标注优先级最高")
    void annotatedParameterWinsOverPathVariables() throws Exception {
        Method m = method("withBothAnnotatedAndPid", Long.class, Long.class);
        // pid = 1 会先被遍历到，但带 @ProjectId 的是第二个参数 99
        assertThat(resolveNoHeader(m, new Object[]{1L, 99L}, "/api/x/1"))
                .isEqualTo(99L);
    }

    @Test
    @DisplayName("@ProjectId 标注的参数为 null 时继续往后找")
    void annotatedParameterFallsThroughWhenNull() throws Exception {
        Method m = method("withBothAnnotatedAndPid", Long.class, Long.class);
        assertThat(resolveNoHeader(m, new Object[]{1L, null}, "/api/projects/1"))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("都没有时用 X-Project-Id 请求头兜底")
    void fallsBackToRequestHeader() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(ProjectIdResolver.HEADER_PROJECT_ID)).thenReturn("55");

        Method m = method("withRequestOnly", HttpServletRequest.class);
        assertThat(resolveNoHeader(m, new Object[]{request}, ASSET_URI))
                .isEqualTo(55L);
    }

    @Test
    @DisplayName("请求头不是数字时视为未提供")
    void ignoresNonNumericHeader() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(ProjectIdResolver.HEADER_PROJECT_ID)).thenReturn("abc");

        Method m = method("withRequestOnly", HttpServletRequest.class);
        assertThatThrownBy(() -> resolveNoHeader(m, new Object[]{request}, ASSET_URI))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("解析不到时抛 400，而不是猜一个值")
    void throwsBadRequestWhenNothingResolvable() throws Exception {
        Method m = method("withNothing", Long.class);
        assertThatThrownBy(() -> resolveNoHeader(m, new Object[]{5L}, ASSET_URI))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(400));

        Method noArgs = method("withNoArgs");
        assertThatThrownBy(() -> resolveNoHeader(noArgs, new Object[0], "/api/auth/me"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无法解析项目ID");
    }

    @Test
    @DisplayName("参数缺位、URI 为 null、方法为 null 都不致崩")
    void handlesDegenerateInputs() throws Exception {
        Method m = method("withProjectId", Long.class);
        // args 短于形参个数
        assertThatThrownBy(() -> resolveNoHeader(m, new Object[0], PROJECTS_URI))
                .isInstanceOf(BizException.class);
        // args 为 null
        assertThatThrownBy(() -> resolveNoHeader(m, null, PROJECTS_URI))
                .isInstanceOf(BizException.class);
        // URI 为 null 时，projectId 命名的参数仍可解析
        assertThat(resolveNoHeader(m, new Object[]{3L}, null)).isEqualTo(3L);
        // 方法为 null
        assertThatThrownBy(() -> resolveNoHeader(null, new Object[0], PROJECTS_URI))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("支持 Integer / String 形式的路径变量值")
    void coercesNumericTypes() throws Exception {
        Method m = method("withProjectId", Long.class);
        assertThat(resolveNoHeader(m, new Object[]{12}, PROJECTS_URI)).isEqualTo(12L);
        assertThat(resolveNoHeader(m, new Object[]{"34"}, PROJECTS_URI)).isEqualTo(34L);
        assertThatThrownBy(() -> resolveNoHeader(m, new Object[]{"abc"}, PROJECTS_URI))
                .isInstanceOf(BizException.class);
    }
}
