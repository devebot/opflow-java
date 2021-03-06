package com.devebot.opflow.supports;

import java.util.Map;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author drupalex
 */
public class OpflowTextFormat {
    private static final String PLACEHOLDER_START = "\\$\\{";
    private static final String PLACEHOLDER_END = "\\}";

    private static final String PH_STR = PLACEHOLDER_START + "([^}]+)" + PLACEHOLDER_END;
    private static final Pattern PH_PATTERN = Pattern.compile(PH_STR);

    public static String format(String format, final Map<String, Object> params) {
        Matcher m = PH_PATTERN.matcher(format);
        Function<MatchResult, String> replacer = new Function<MatchResult, String>() {
            @Override
            public String apply(MatchResult t) {
                String matched = t.group(1);
                String[] fieldPath = matched.split("\\.");
                Object newObj = OpflowObjectTree.getObjectByPath(params, fieldPath);
                String newVal = (newObj == null) ? "<null>" : newObj.toString();
                newVal = newVal.replaceAll("\\$", ".");
                return newVal;
            }
        };
        StringBuffer result = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(result, replacer.apply(m));
        }
        m.appendTail(result);
        return result.toString();
    }
    
    /*
    public static String format9(String format, final Map<String, Object> params) {
        Matcher m = PH_PATTERN.matcher(format);
        Function<MatchResult, String> replacer = new Function<MatchResult, String>() {
            @Override
            public String apply(MatchResult t) {
                String matched = t.group(1);
                String[] fieldPath = matched.split("\\.");
                Object newObj = OpflowObjectTree.getObjectByPath(params, fieldPath);
                String newVal = (newObj == null) ? "<null>" : newObj.toString();
                newVal = newVal.replaceAll("\\$", ".");
                return newVal;
            }
        };
        return m.replaceAll(replacer);
    }

    public static String format8(String format, final Map<String, Object> params) {
        Matcher m = PH_PATTERN.matcher(format);
        StringBuffer result = new StringBuffer();
        while (m.find()) {
            String[] fieldPath = m.group(1).split("\\.");
            Object newObj = OpflowObjectTree.getObjectByPath(params, fieldPath);
            String newVal = (newObj == null) ? "<null>" : newObj.toString();
            newVal = newVal.replaceAll("\\$", ".");
            m.appendReplacement(result, newVal);
        }
        m.appendTail(result);
        return result.toString();
    }
    */

    public static String formatLegacy(String format, Map<String, Object> params) {
        Matcher m = PH_PATTERN.matcher(format);
        String result = format;
        while (m.find()) {
            String[] fieldPath = m.group(1).split("\\.");
            Object newObj = OpflowObjectTree.getObjectByPath(params, fieldPath);
            String newVal = (newObj == null) ? "<null>" : newObj.toString();
            result = result.replaceFirst(PH_STR, newVal);
        }
        return result;
    }
}
