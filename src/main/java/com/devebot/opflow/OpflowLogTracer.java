package com.devebot.opflow;

import com.devebot.opflow.supports.OpflowEnvTool;
import com.devebot.opflow.supports.OpflowJsonTool;
import com.devebot.opflow.supports.OpflowObjectTree;
import com.devebot.opflow.supports.OpflowSystemInfo;
import com.devebot.opflow.supports.OpflowTextFormat;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author drupalex
 */
public class OpflowLogTracer {
    private final static Logger LOG = LoggerFactory.getLogger(OpflowLogTracer.class);
    private final static OpflowEnvTool ENVTOOL = OpflowEnvTool.instance;
    private final static String OPFLOW_VERSION = "0.1.x";
    private final static String OPFLOW_INSTANCE_ID = OpflowUUID.getBase64ID();
    private final static String DEFAULT_PARENT_ID_NAME = "_parentId_";
    private final static String DEFAULT_NODE_ID_NAME = "_nodeId_";
    private final static String DEFAULT_NODE_TYPE_NAME = "_nodeType_";

    private final static Set<String> ALWAYS_ENABLED;
    private final static int TRACKING_DEPTH;
    private final static boolean KEEP_ORDER;
    private final static String TAGS_FIELD_NAME;
    private final static String TEXT_FIELD_NAME;
    private final static boolean IS_INTERCEPTOR_ENABLED;
    private final static boolean IS_TRACING_ID_PREDEFINED;
    private final static boolean IS_STRINGIFY_ENABLED;
    private final static boolean IS_TAGS_EMBEDDABLE;
    private final static boolean IS_TEXT_EMBEDDABLE;
    private final static boolean IS_TEMPLATE_APPLIED;

    static {
        ALWAYS_ENABLED = new HashSet<>();
        String[] _levels = ENVTOOL.getEnvironVariable("OPFLOW_ALWAYS_ENABLED", "").split(",");
        for(String _level: _levels) {
            ALWAYS_ENABLED.add(_level.trim());
        }
        
        String treepath = ENVTOOL.getEnvironVariable("OPFLOW_TRACKING_DEPTH", null);
        if (null == treepath) TRACKING_DEPTH = 2;
        else switch (treepath) {
            case "none":
                TRACKING_DEPTH = 0;
                break;
            case "parent":
                TRACKING_DEPTH = 1;
                break;
            case "full":
                TRACKING_DEPTH = 2;
                break;
            default:
                TRACKING_DEPTH = 2;
                break;
        }
        
        KEEP_ORDER = (ENVTOOL.getEnvironVariable("OPFLOW_LOGKEEPORDER", null) == null);
        
        TAGS_FIELD_NAME = ENVTOOL.getEnvironVariable("OPFLOW_TAGS_FIELD_NAME", "_tags_");
        TEXT_FIELD_NAME = ENVTOOL.getEnvironVariable("OPFLOW_TEXT_FIELD_NAME", "_text_");
        
        IS_TRACING_ID_PREDEFINED = "true".equals(ENVTOOL.getEnvironVariable("OPFLOW_TRACING_ID_PREDEFINED", null));
        IS_TAGS_EMBEDDABLE = !"false".equals(ENVTOOL.getEnvironVariable("OPFLOW_TAGS_EMBEDDABLE", null));
        IS_TEXT_EMBEDDABLE = "false".equals(ENVTOOL.getEnvironVariable("OPFLOW_TEXT_EMBEDDABLE", null));
        IS_TEMPLATE_APPLIED = !"false".equals(ENVTOOL.getEnvironVariable("OPFLOW_TEMPLATE_APPLIED", null));
        IS_INTERCEPTOR_ENABLED = !"false".equals(ENVTOOL.getEnvironVariable("OPFLOW_DEBUGLOG", null));
        IS_STRINGIFY_ENABLED = true;
    }
    
    public interface Customizer {
        boolean isMute();
    }
    
    private final Customizer customizer;
    private final OpflowLogTracer parent;
    private final String key;
    private final Object value;
    private final Map<String, Object> fields;
    private final Set<String> frozen;
    private final Set<String> tags;
    private String template;
    
    public final static OpflowLogTracer ROOT = new OpflowLogTracer();
    
    public static String getInstanceId() {
        return ROOT.value.toString();
    }
    
    public OpflowLogTracer() {
        this(null, "instanceId", ENVTOOL.getEnvironVariable("OPFLOW_INSTANCE_ID", OPFLOW_INSTANCE_ID), null);
    }
    
    private OpflowLogTracer(OpflowLogTracer ref, String key, Object value, Customizer customizer) {
        this.customizer = customizer;
        this.parent = ref;
        this.key = key;
        this.value = value;
        this.fields = KEEP_ORDER ? new LinkedHashMap<String, Object>() : new HashMap<String, Object>();
        this.frozen = new HashSet<>();
        this.tags = new HashSet<>();
        this.reset();
    }
    
    public OpflowLogTracer copy() {
        return new OpflowLogTracer(this.parent, this.key, this.value, this.customizer);
    }
    
    public OpflowLogTracer branch(String key, Object value) {
        return branch(key, value, this.customizer);
    }
    
    public OpflowLogTracer branch(String key, Object value, Customizer customizer) {
        return new OpflowLogTracer(this, key, value, customizer);
    }
    
    public boolean ready(Logger logger, int level) {
        if (customizer != null && customizer.isMute()) {
            return false;
        }
        return HAS(logger, level);
    }
    
    @Deprecated
    public boolean ready(Logger logger, String level) {
        if (customizer != null && customizer.isMute()) {
            return false;
        }
        return has(logger, level);
    }
    
    public final OpflowLogTracer clear() {
        if (this.fields.size() > this.frozen.size()) {
            this.fields.keySet().retainAll(this.frozen);
        }
        return this;
    }
    
    public final OpflowLogTracer reset() {
        this.fields.clear();
        this.frozen.clear();
        if (IS_TRACING_ID_PREDEFINED) {
            if (this.parent != null) {
                this.fields.put(DEFAULT_PARENT_ID_NAME, this.parent.value);
            }
            this.fields.put(DEFAULT_NODE_ID_NAME, value);
            this.fields.put(DEFAULT_NODE_TYPE_NAME, key);
        } else {
            if (TRACKING_DEPTH > 0) {
                if (TRACKING_DEPTH == 1) {
                    if (this.parent != null) {
                        this.fields.put(this.parent.key, this.parent.value);
                    }
                } else {
                    OpflowLogTracer ref = this.parent;
                    while(ref != null) {
                        this.fields.put(ref.key, ref.value);
                        ref = ref.parent;
                    }
                }
            }
            this.fields.put(key, value);
        }
        this.frozen.addAll(this.fields.keySet());
        return this;
    }
    
    public OpflowLogTracer put(String key, Object value) {
        this.fields.put(key, value);
        return this;
    }
    
    public Object get(String key) {
        return this.fields.get(key);
    }
    
    public OpflowLogTracer tags(String t) {
        this.tags.add(t);
        return this;
    }
    
    public OpflowLogTracer tags(String ... ts) {
        this.tags.addAll(Arrays.asList(ts));
        return this;
    }
    
    public OpflowLogTracer text(String s) {
        this.template = s;
        return this;
    }
    
    @Override
    public String toString() {
        return stringify();
    }
    
    public String stringify() {
        return stringify(true);
    }
    
    public String stringify(String template) {
        return stringify(template, true);
    }
    
    public String stringify(boolean clear) {
        return stringify(null, clear);
    }
    
    public String stringify(String template, boolean clear) {
        String output = null, text = null;
        Set<String> tagz = null;
        
        if (template == null) {
            template = this.template;
        }
        
        if (IS_TAGS_EMBEDDABLE || IS_INTERCEPTOR_ENABLED) {
            tagz = this.tags;
        }
        
        if (IS_TEXT_EMBEDDABLE || (IS_TEMPLATE_APPLIED && template != null)) {
            text = OpflowTextFormat.format(template, this.fields);
        }
        
        if (IS_STRINGIFY_ENABLED) {
            if (IS_TEMPLATE_APPLIED && text != null) {
                output = text;
            } else {
                if (IS_TAGS_EMBEDDABLE && tagz != null && tagz.size() > 0) {
                    this.fields.put(TAGS_FIELD_NAME, tagz);
                }
                if (IS_TEXT_EMBEDDABLE && text != null) {
                    this.fields.put(TEXT_FIELD_NAME, text);
                }
                output = OpflowJsonTool.toString(fields);
            }
        }
        
        if (IS_INTERCEPTOR_ENABLED && !interceptors.isEmpty()) {
            Map<String, Object> cloned = new HashMap<>();
            cloned.putAll(fields);
            tagz = new HashSet<>(this.tags);
            for(StringifyInterceptor interceptor:interceptors) {
                interceptor.intercept(cloned, tagz);
            }
        }
        this.tags.clear();
        this.template = null;
        if (clear) this.clear();
        return output;
    }
    
    public interface StringifyInterceptor {
        public void intercept(Map<String, Object> logdata, Set<String> tags);
    }
    
    private static List<StringifyInterceptor> interceptors = new ArrayList<StringifyInterceptor>();
    
    public static boolean addInterceptor(StringifyInterceptor interceptor) {
        if (interceptor != null && interceptors.indexOf(interceptor) < 0) {
            interceptors.add(interceptor);
            return true;
        }
        return false;
    }
    
    public static boolean removeInterceptor(StringifyInterceptor interceptor) {
        return interceptors.remove(interceptor);
    }
    
    public static void clearInterceptors() {
        interceptors.clear();
    }
    
    private static String libraryInfo = null;;
    
    public static String getLibraryInfo() {
        if (libraryInfo == null) {
            Map<String, Object> repoInfo = OpflowSystemInfo.getGitInfo();
            String buildVersion = OpflowObjectTree.getOptionValue(repoInfo, "git.build.version", String.class, OPFLOW_VERSION);
            String commitId = OpflowObjectTree.getOptionValue(repoInfo, "git.commit.id.abbrev", String.class, null);
            libraryInfo = new OpflowLogTracer()
                    .put("message", "Opflow Library Information")
                    .put("lib_name", "opflow-java")
                    .put("lib_version", buildVersion)
                    .put("lib_revision", commitId)
                    .put("os_name", System.getProperty("os.name"))
                    .put("os_version", System.getProperty("os.version"))
                    .put("os_arch", System.getProperty("os.arch"))
                    .text("[${instanceId}] Library: ${lib_name}@${lib_version} (${lib_revision}) - ${message}")
                    .stringify();
        }
        return libraryInfo;
    }
    
    private static String getVersionNameFromPOM() {
        try {
            Properties props = new Properties();
            String POM_PROPSFILE = "META-INF/maven/com.devebot.opflow/opflow-core/pom.properties";
            props.load(OpflowLogTracer.class.getClassLoader().getResourceAsStream(POM_PROPSFILE));
            return props.getProperty("version");
        } catch (IOException ioe) {}
        return OPFLOW_VERSION;
    }
    
    private static String getVersionNameFromManifest() {
        try {
            InputStream manifestStream = OpflowLogTracer.class.getClassLoader().getResourceAsStream("META-INF/MANIFEST.MF");
            if (manifestStream != null) {
                Manifest manifest = new Manifest(manifestStream);
                Attributes attributes = manifest.getMainAttributes();
                return attributes.getValue("Implementation-Version");
            }
        } catch (IOException ioe) {}
        return OPFLOW_VERSION;
    }
    
    public static class Level {
        public final static int OFF = Integer.MAX_VALUE;
        public final static int FATAL = 50000;
        public final static int ERROR = 40000;
        public final static int WARN  = 30000;
        public final static int INFO  = 20000;
        public final static int DEBUG = 10000;
        public final static int TRACE = 5000; 
        public final static int ALL = Integer.MIN_VALUE; 
    }
    
    public static boolean HAS(Logger logger, int level) {
        if (logger == null) return false;
        if (level == Level.DEBUG) return logger.isDebugEnabled();
        if (level == Level.TRACE) return logger.isTraceEnabled();
        if (level == Level.INFO) return logger.isInfoEnabled();
        if (level == Level.WARN) return logger.isWarnEnabled();
        if (level == Level.ERROR) return logger.isErrorEnabled();
        return false;
    }
    
    @Deprecated
    public static boolean has(Logger logger, String level) {
        if (logger == null) return false;
        if (ALWAYS_ENABLED.contains("all")) return true;
        if (ALWAYS_ENABLED.contains(level)) return true;
        if ("debug".equalsIgnoreCase(level)) return logger.isDebugEnabled();
        if ("trace".equalsIgnoreCase(level)) return logger.isTraceEnabled();
        if ("info".equalsIgnoreCase(level)) return logger.isInfoEnabled();
        if ("warn".equalsIgnoreCase(level)) return logger.isWarnEnabled();
        if ("error".equalsIgnoreCase(level)) return logger.isErrorEnabled();
        return false;
    }
    
    static {
        if (HAS(LOG, Level.INFO)) LOG.info(getLibraryInfo());
    }
}
