package com.yizhaoqi.smartpai.model;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 适用地区归一化。上传时把“中国/全国/中华人民共和国”等写法统一成“全国”，
 * 地方名称去掉省/市/自治区等后缀后作为编码；检索地方问题时自动补上“全国适用”这一层。
 */
public final class JurisdictionNormalizer {

    public static final String NATIONAL = "NATIONAL";
    public static final String NATIONAL_DISPLAY = "全国";

    private static final Set<String> NATIONAL_ALIASES = Set.of(
            "全国", "中国", "中华人民共和国", "国家", "全国适用", "全国范围",
            "national", "nationwide", "china", "all"
    );

    private static final String[] ADMIN_SUFFIXES = {"特别行政区", "自治区", "省", "市"};

    private JurisdictionNormalizer() {
    }

    public static String normalizeDisplay(String raw) {
        String compact = compact(raw);
        if (compact == null) {
            return null;
        }
        return isNational(compact) ? NATIONAL_DISPLAY : stripAdministrativeSuffix(compact);
    }

    public static String normalizeCode(String raw) {
        String display = normalizeDisplay(raw);
        if (display == null) {
            return null;
        }
        if (NATIONAL_DISPLAY.equals(display)) {
            return NATIONAL;
        }
        return stripAdministrativeSuffix(display);
    }

    /**
     * 检索编码：地方问题返回地方编码 + NATIONAL；全国问题只返回 NATIONAL，避免把所有地方资料都拉进来。
     */
    public static Set<String> queryCodes(Collection<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String value : requested) {
            String code = normalizeCode(value);
            if (code != null) {
                codes.add(code);
            }
        }
        if (codes.isEmpty()) {
            return Set.of();
        }
        if (codes.size() == 1 && codes.contains(NATIONAL)) {
            return Set.of(NATIONAL);
        }
        LinkedHashSet<String> expanded = new LinkedHashSet<>(codes);
        expanded.add(NATIONAL);
        return expanded;
    }

    /**
     * 兼容旧 ES 文档只写 jurisdiction 显示值、没有 jurisdictionCode 的情况；
     * 返回用户地区、常见行政区全称和国家别名，供 terms 回退匹配。
     */
    public static Set<String> queryDisplays(Collection<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> displays = new LinkedHashSet<>();
        boolean nationalOnly = true;
        for (String value : requested) {
            String display = normalizeDisplay(value);
            if (display == null) {
                continue;
            }
            displays.add(display);
            if (!NATIONAL_DISPLAY.equals(display)) {
                nationalOnly = false;
                displays.add(display + "市");
                displays.add(display + "省");
            }
        }
        if (displays.isEmpty()) {
            return Set.of();
        }
        if (nationalOnly) {
            displays.clear();
        }
        displays.addAll(Set.of(NATIONAL_DISPLAY, "中国", "中华人民共和国"));
        return displays;
    }

    private static boolean isNational(String compact) {
        return NATIONAL_ALIASES.contains(compact.toLowerCase(Locale.ROOT));
    }

    private static String stripAdministrativeSuffix(String value) {
        for (String suffix : ADMIN_SUFFIXES) {
            if (value.endsWith(suffix) && value.length() > suffix.length() + 1) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        return value;
    }

    private static String compact(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.replaceAll("\\s+", "").trim();
        return value.isEmpty() ? null : value;
    }
}
