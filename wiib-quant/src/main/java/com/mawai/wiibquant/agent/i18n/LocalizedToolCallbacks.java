package com.mawai.wiibquant.agent.i18n;

import com.mawai.wiibcommon.enums.AgentLang;
import lombok.RequiredArgsConstructor;
import org.springframework.aop.support.AopUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 按语言出工具描述：{@code @Tool(description=...)} 是编译期常量换不掉，所以自己拼 ToolCallback——
 * 名字与 inputSchema 照旧由注解自动推导（{@link ToolDefinitions#from}，schema 零手写），
 * 只把 description 换成 {@code tool.<工具名>} 那条。
 * <p>
 * 用它替掉建图时的 {@code builder.toolsFromObject(x)}：{@code builder.tools(localized.of(lang, x))}。
 * <p>
 * <b>唯一允许静默回落的地方</b>：catalog 里没有 {@code tool.<工具名>} 就原样保留注解里的描述——
 * 让还没搬进 yml 的工具照常工作，后续批次逐个搬。
 * <p>
 * <b>@ToolParam 的参数描述不跟语言走</b>：它嵌在自动推导的 inputSchema 里，换语言要在 schema 层
 * 逐字段改写，复杂度不值。参数描述是字段级技术说明，全仓统一写英文。
 */
@Component
@RequiredArgsConstructor
public class LocalizedToolCallbacks {

    private final PromptCatalog catalog;

    /**
     * 扫这些对象上的 {@code @Tool} 方法，出一批带当前语言描述的 callback。
     * 扫描口径与 Spring AI 的 MethodToolCallbackProvider 一致：只认 @Tool 标注的用户声明方法
     * （桥接/合成方法排除，否则泛型工具类会出重名工具）。
     */
    public List<ToolCallback> of(AgentLang lang, Object... toolObjects) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Object toolObject : toolObjects) {
            Class<?> type = AopUtils.isAopProxy(toolObject)
                    ? AopUtils.getTargetClass(toolObject) : toolObject.getClass();
            for (Method method : ReflectionUtils.getDeclaredMethods(type)) {
                if (AnnotationUtils.findAnnotation(method, Tool.class) != null
                        && ReflectionUtils.USER_DECLARED_METHODS.matches(method)) {
                    callbacks.add(build(lang, toolObject, method));
                }
            }
        }
        return List.copyOf(callbacks);
    }

    private ToolCallback build(AgentLang lang, Object toolObject, Method method) {
        ToolDefinition annotated = ToolDefinitions.from(method);
        String localized = catalog.find(lang, "tool." + annotated.name());
        ToolDefinition definition = localized == null ? annotated
                : DefaultToolDefinition.builder()
                        .name(annotated.name())
                        .description(localized)
                        .inputSchema(annotated.inputSchema())
                        .build();
        // 除 definition 外逐项照抄框架的装配，否则 returnDirect、结果转换器这些注解属性会悄悄失效
        return MethodToolCallback.builder()
                .toolDefinition(definition)
                .toolMetadata(ToolMetadata.from(method))
                .toolMethod(method)
                .toolObject(toolObject)
                .toolCallResultConverter(ToolUtils.getToolCallResultConverter(method))
                .build();
    }
}
