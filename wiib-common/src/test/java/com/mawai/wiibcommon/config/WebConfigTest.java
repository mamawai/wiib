package com.mawai.wiibcommon.config;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.resolver.CurrentUserIdArgumentResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @CurrentUserId} 这条防越权链的看门测试。
 * <p>
 * 【为什么值得单独写】三十来个 endpoint 靠 {@code @CurrentUserId Long userId} 从登录态取用户，
 * 一个请求参数都不收，这是全站防越权（IDOR）的地基。而它成立的<b>全部依据</b>就是
 * {@link CurrentUserIdArgumentResolver} 真的被注册进了 MVC 的解析器列表。
 * <p>
 * 一旦解析器缺席，退化方向是<b>坏且静默</b>的：Spring 的兜底解析器会把不带注解的简单类型形参
 * 当查询参数绑定，于是 {@code @CurrentUserId Long userId} 悄悄变成 {@code ?userId=}——
 * 接口照样 200、测试照样绿，只是任何人都能翻任何人的数据。没有任何别的机制发现得了这件事，
 * 所以给它加一根绊线；成本是一次性的三条断言，不是常驻运行时检查。
 * <p>
 * 【本类够不着的那条路，以及为什么不必管】"整个 com.mawai.wiibcommon 包不再被扫"（有人改
 * 三个模块的 scanBasePackages）本类验不到，但那条路<b>是响的不是静默的</b>：同包下的
 * {@code CacheService} 是 {@code @Service}，光 wiib-sim 就有 22 个文件注入它，
 * 包一掉整个上下文起不来。所以只有"动 WebConfig 本身"这两条路才需要绊线，也就是下面两条用例。
 */
class WebConfigTest {

    /** 解析器必须真被加进列表——这行没了，全站 @CurrentUserId 静默退化成 ?userId= */
    @Test
    void CurrentUserId解析器必须被注册() {
        List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();

        new WebConfig(new MessageCatalog()).addArgumentResolvers(resolvers);

        assertThat(resolvers).hasAtLeastOneElementOfType(CurrentUserIdArgumentResolver.class);
    }

    /**
     * 光"方法里加了那行"不够：WebConfig 得是个能被组件扫描捡起来的 WebMvcConfigurer，
     * 谁摘掉 @Configuration 或那个 implements，addArgumentResolvers 就再也不会被调用一次——
     * 而上面那条用例是自己 new 出来手动调的，摘掉它照样绿，所以这条不能省。
     */
    @Test
    void WebConfig必须是能被扫到的WebMvcConfigurer() {
        assertThat(WebConfig.class.isAnnotationPresent(Configuration.class))
                .as("WebConfig 摘了 @Configuration 就不会被注册，参数解析器随之缺席")
                .isTrue();
        assertThat(WebMvcConfigurer.class).isAssignableFrom(WebConfig.class);
    }

    /**
     * 解析器认领形参的判据必须是 {@code @CurrentUserId} 本身。
     * <p>
     * {@code supportsParameter} 若返 false，那个形参就落到 Spring 的兜底解析器手里、
     * 变成可被请求方指定的 {@code ?userId=}——和"解析器没注册"是同一种静默越权。
     * 反例那半边（无注解形参必须<b>不</b>认领）同样重要：认领了就会把 symbol 之类的参数
     * 也塞成当前用户 id。
     */
    @Test
    void 解析器只认领挂了CurrentUserId的形参() throws Exception {
        Method probe = WebConfigTest.class.getDeclaredMethod("探针", Long.class, String.class);
        CurrentUserIdArgumentResolver resolver = new CurrentUserIdArgumentResolver();

        assertThat(resolver.supportsParameter(new MethodParameter(probe, 0))).isTrue();
        assertThat(resolver.supportsParameter(new MethodParameter(probe, 1))).isFalse();
    }

    /** 只为上面那条用例提供两个形参，不会被调用 */
    @SuppressWarnings("unused")
    private void 探针(@CurrentUserId Long userId, String symbol) {
    }
}
