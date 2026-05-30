package com.zlt.aps.utils;

import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;

/**
 * SpEL 表达式解析与国际化消息工具类
 *
 * @author wengpc
 */
public final class SpELi18nUtil {

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    private static volatile MessageSource messageSource;

    private SpELi18nUtil() {
    }

    /**
     * 由 Spring 容器启动时注入 {@link MessageSource}
     *
     * @param context Spring 应用上下文
     */
    public static void setMessageSource(ApplicationContext context) {
        try {
            messageSource = context.getBean(MessageSource.class);
        } catch (Exception ignored) {
            // 未配置 MessageSource 时，使用 key 本身作为消息
        }
    }

    /**
     * 解析 SpEL 表达式，返回字符串结果
     *
     * @param expression SpEL 表达式
     * @param args       方法参数值数组
     * @param method     目标方法
     * @return 解析后的字符串
     */
    public static String parseSpEL(String expression, Object[] args, Method method) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        String[] paramNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length && i < args.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        return PARSER.parseExpression(expression).getValue(context, String.class);
    }

    /**
     * 获取国际化消息并替换占位符参数
     *
     * @param key      国际化 key
     * @param args     占位符参数 SpEL 表达式数组
     * @param joinPoint 切点
     * @param method   目标方法
     * @return 替换后的消息文本
     */
    public static String getI18nMessage(String key, String[] args, ProceedingJoinPoint joinPoint, Method method) {
        String message = resolveMessage(key);
        if (args != null && args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                String value = parseSpEL(args[i], joinPoint.getArgs(), method);
                message = message.replace("{" + i + "}", value != null ? value : "");
            }
        }
        return message;
    }

    private static String resolveMessage(String key) {
        if (messageSource != null) {
            try {
                return messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
            } catch (Exception e) {
                return key;
            }
        }
        return key;
    }
}
