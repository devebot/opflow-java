package com.devebot.opflow;

import com.devebot.opflow.exception.OpflowBootstrapException;
import com.devebot.opflow.exception.OpflowInterceptionException;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.devebot.opflow.annotation.OpflowTargetRoutine;

/**
 *
 * @author drupalex
 */
public class OpflowServerlet {
    private final static Logger LOG = LoggerFactory.getLogger(OpflowServerlet.class);
    private final OpflowLogTracer logTracer;
    private final OpflowPromMeasurer measurer;
    
    private final String serverletId;
    private OpflowPubsubHandler configurer;
    private OpflowRpcWorker rpcWorker;
    private OpflowPubsubHandler subscriber;
    private Instantiator instantiator;
    
    private final ListenerDescriptor listenerMap;
    
    private final Map<String, Object> kwargs;
    
    public OpflowServerlet(ListenerDescriptor listeners, Map<String, Object> kwargs) throws OpflowBootstrapException {
        this.kwargs = OpflowUtil.ensureNotNull(kwargs);

        serverletId = OpflowUtil.getOptionField(this.kwargs, "serverletId", true);
        logTracer = OpflowLogTracer.ROOT.branch("serverletId", serverletId);
        
        if (OpflowLogTracer.has(LOG, "info")) LOG.info(logTracer
                .text("Serverlet[${serverletId}].new()")
                .stringify());
        
        if (listeners == null) {
            throw new OpflowBootstrapException("Listener definitions must not be null");
        }
        listenerMap = listeners;
        
        measurer = OpflowPromMeasurer.getInstance();
        
        Map<String, Object> configurerCfg = (Map<String, Object>)this.kwargs.get("configurer");
        Map<String, Object> rpcWorkerCfg = (Map<String, Object>)this.kwargs.get("rpcWorker");
        Map<String, Object> subscriberCfg = (Map<String, Object>)this.kwargs.get("subscriber");
        
        HashSet<String> checkExchange = new HashSet<>();
        HashSet<String> checkQueue = new HashSet<>();
        HashSet<String> checkRecyclebin = new HashSet<>();
        
        if (configurerCfg != null && !Boolean.FALSE.equals(configurerCfg.get("enabled"))) {
            if (configurerCfg.get("exchangeName") == null || configurerCfg.get("routingKey") == null) {
                throw new OpflowBootstrapException("Invalid Configurer connection parameters");
            } 
            if (!checkExchange.add(configurerCfg.get("exchangeName").toString() + configurerCfg.get("routingKey").toString())) {
                throw new OpflowBootstrapException("Duplicated Configurer connection parameters");
            }
            if (configurerCfg.get("subscriberName") != null && !checkQueue.add(configurerCfg.get("subscriberName").toString())) {
                throw new OpflowBootstrapException("Configurer[subscriberName] must not be duplicated");
            }
            if (configurerCfg.get("recyclebinName") != null) checkRecyclebin.add(configurerCfg.get("recyclebinName").toString());
        }

        if (rpcWorkerCfg != null && !Boolean.FALSE.equals(rpcWorkerCfg.get("enabled"))) {
            if (rpcWorkerCfg.get("exchangeName") == null || rpcWorkerCfg.get("routingKey") == null) {
                throw new OpflowBootstrapException("Invalid RpcWorker connection parameters");
            }
            if (!checkExchange.add(rpcWorkerCfg.get("exchangeName").toString() + rpcWorkerCfg.get("routingKey").toString())) {
                throw new OpflowBootstrapException("Duplicated RpcWorker connection parameters");
            }
            if (rpcWorkerCfg.get("operatorName") != null && !checkQueue.add(rpcWorkerCfg.get("operatorName").toString())) {
                throw new OpflowBootstrapException("RpcWorker[operatorName] must not be duplicated");
            }
            if (rpcWorkerCfg.get("responseName") != null && !checkQueue.add(rpcWorkerCfg.get("responseName").toString())) {
                throw new OpflowBootstrapException("RpcWorker[responseName] must not be duplicated");
            }
        }
        
        if (subscriberCfg != null && !Boolean.FALSE.equals(subscriberCfg.get("enabled"))) {
            if (subscriberCfg.get("exchangeName") == null || subscriberCfg.get("routingKey") == null) {
                throw new OpflowBootstrapException("Invalid Subscriber connection parameters");
            }
            if (!checkExchange.add(subscriberCfg.get("exchangeName").toString() + subscriberCfg.get("routingKey").toString())) {
                throw new OpflowBootstrapException("Duplicated Subscriber connection parameters");
            }
            if (subscriberCfg.get("subscriberName") != null && !checkQueue.add(subscriberCfg.get("subscriberName").toString())) {
                throw new OpflowBootstrapException("Subscriber[subscriberName] must not be duplicated");
            }
            if (subscriberCfg.get("recyclebinName") != null) checkRecyclebin.add(subscriberCfg.get("recyclebinName").toString());
        }
        
        checkRecyclebin.retainAll(checkQueue);
        if (!checkRecyclebin.isEmpty()) {
            if (OpflowLogTracer.has(LOG, "error")) LOG.error(logTracer
                .text("duplicated_recyclebin_queue_name").toString());
            throw new OpflowBootstrapException("Invalid recyclebinName (duplicated with some queueNames)");
        }
        
        try {
            if (configurerCfg != null && !Boolean.FALSE.equals(configurerCfg.get("enabled"))) {
                String pubsubHandlerId = OpflowUtil.getLogID();
                configurerCfg.put("pubsubHandlerId", pubsubHandlerId);
                if (OpflowLogTracer.has(LOG, "info")) LOG.info(logTracer
                        .put("pubsubHandlerId", pubsubHandlerId)
                        .text("Serverlet[${serverletId}] creates a new configurer")
                        .stringify());
                configurer = new OpflowPubsubHandler(OpflowUtil.buildMap(new OpflowUtil.MapListener() {
                    @Override
                    public void transform(Map<String, Object> opts) {
                        opts.put("measurer", measurer);
                    }
                }, configurerCfg).toMap());
            }

            if (rpcWorkerCfg != null && !Boolean.FALSE.equals(rpcWorkerCfg.get("enabled"))) {
                String rpcWorkerId = OpflowUtil.getLogID();
                rpcWorkerCfg.put("rpcWorkerId", rpcWorkerId);
                if (OpflowLogTracer.has(LOG, "info")) LOG.info(logTracer
                        .put("rpcWorkerId", rpcWorkerId)
                        .text("Serverlet[${serverletId}] creates a new rpcWorker")
                        .stringify());
                rpcWorker = new OpflowRpcWorker(OpflowUtil.buildMap(new OpflowUtil.MapListener() {
                    @Override
                    public void transform(Map<String, Object> opts) {
                        opts.put("measurer", measurer);
                    }
                }, rpcWorkerCfg).toMap());
                instantiator = new Instantiator(rpcWorker, OpflowUtil.buildMap()
                        .put("instantiatorId", rpcWorkerId).toMap());
            }

            if (subscriberCfg != null && !Boolean.FALSE.equals(subscriberCfg.get("enabled"))) {
                String pubsubHandlerId = OpflowUtil.getLogID();
                subscriberCfg.put("pubsubHandlerId", pubsubHandlerId);
                if (OpflowLogTracer.has(LOG, "info")) LOG.info(logTracer
                        .put("pubsubHandlerId", pubsubHandlerId)
                        .text("Serverlet[${serverletId}] creates a new subscriber")
                        .stringify());
                subscriber = new OpflowPubsubHandler(OpflowUtil.buildMap(new OpflowUtil.MapListener() {
                    @Override
                    public void transform(Map<String, Object> opts) {
                        opts.put("measurer", measurer);
                    }
                }, subscriberCfg).toMap());
            }
        } catch(OpflowBootstrapException exception) {
            this.close();
            throw exception;
        }
        
        if (OpflowLogTracer.has(LOG, "info")) LOG.info(logTracer
                .text("Serverlet[${serverletId}].new() end!")
                .stringify());
        
        measurer.changeComponentInstance("serverletId", serverletId, OpflowPromMeasurer.GaugeAction.INC);
    }
    
    public final void start() {
        if (OpflowLogTracer.has(LOG, "info")) LOG.info(logTracer
                .text("Serverlet[${serverletId}].start()")
                .stringify());
        
        if (configurer != null && listenerMap.getConfigurer() != null) {
            configurer.subscribe(listenerMap.getConfigurer());
        }
        
        if (rpcWorker != null) {
            Map<String, OpflowRpcListener> rpcListeners = listenerMap.getRpcListeners();
            for(Map.Entry<String, OpflowRpcListener> entry:rpcListeners.entrySet()) {
                rpcWorker.process(entry.getKey(), entry.getValue());
            }
        }
        if (instantiator != null) {
            instantiator.process();
        }
        
        if (subscriber != null && listenerMap.getSubscriber() != null) {
            subscriber.subscribe(listenerMap.getSubscriber());
        }
        
        if (OpflowLogTracer.has(LOG, "info")) LOG.info(logTracer
                .text("Serverlet[${serverletId}].start() has completed!")
                .stringify());
    }
    
    public void instantiateType(Class type) {
        instantiateType(type, null);
    }
    
    public void instantiateType(Class type, Object target) {
        if (instantiator != null) {
            instantiator.instantiateType(type, target);
        } else {
            throw new UnsupportedOperationException("instantiator is nulls");
        }
    }
    
    public void instantiateTypes(Class[] types) {
        instantiateTypes(Arrays.asList(types));
    }
    
    public void instantiateTypes(Collection<Class> types) {
        Set<Class> typeSet = new HashSet<>();
        typeSet.addAll(types);
        for (Class type : typeSet) {
            if (!Modifier.isAbstract(type.getModifiers())) {
                instantiateType(type);
            }
        }
    }
    
    public final void close() {
        if (OpflowLogTracer.has(LOG, "info")) LOG.info(logTracer
                .text("Serverlet[${serverletId}].close()")
                .stringify());
        
        if (configurer != null) configurer.close();
        if (rpcWorker != null) rpcWorker.close();
        if (subscriber != null) subscriber.close();
        
        if (OpflowLogTracer.has(LOG, "info")) LOG.info(logTracer
                .text("Serverlet[${serverletId}].close() has completed!")
                .stringify());
    }
    
    public static DescriptorBuilder getDescriptorBuilder() {
        return new DescriptorBuilder();
    }
    
    public static class DescriptorBuilder {
        private DescriptorBuilder() {}
        
        private ListenerDescriptor map = new ListenerDescriptor();
        
        public DescriptorBuilder setConfigurer(OpflowPubsubListener configurer) {
            map.configurer = configurer;
            return this;
        }
        
        public DescriptorBuilder setSubscriber(OpflowPubsubListener subscriber) {
            map.subscriber = subscriber;
            return this;
        }
        
        public DescriptorBuilder addRpcListener(String routineId, OpflowRpcListener listener) {
            map.rpcListeners.put(routineId, listener);
            return this;
        }
        
        public ListenerDescriptor build() {
            return map;
        }
    }
    
    public static class ListenerDescriptor {
        public static final ListenerDescriptor EMPTY = new ListenerDescriptor();
        private OpflowPubsubListener configurer;
        private Map<String, OpflowRpcListener> rpcListeners = new HashMap<>();
        private OpflowPubsubListener subscriber;
        
        private ListenerDescriptor() {}
        
        public OpflowPubsubListener getConfigurer() {
            return configurer;
        }

        public Map<String, OpflowRpcListener> getRpcListeners() {
            Map<String, OpflowRpcListener> cloned = new HashMap<>();
            cloned.putAll(rpcListeners);
            return cloned;
        }

        public OpflowPubsubListener getSubscriber() {
            return subscriber;
        }
    }
    
    public static class Instantiator {
        private static final Logger LOG = LoggerFactory.getLogger(Instantiator.class);
        private final OpflowLogTracer logTracer;
        private final OpflowRpcWorker rpcWorker;
        private final OpflowRpcListener listener;
        private final Set<String> routineIds = new HashSet<>();
        private final Map<String, Method> methodRef = new HashMap<>();
        private final Map<String, Object> targetRef = new HashMap<>();
        private final Map<String, String> methodOfAlias = new HashMap<>();
        private boolean processing = false;
        
        public Instantiator(OpflowRpcWorker worker) throws OpflowBootstrapException {
            this(worker, null);
        }
        
        public Instantiator(OpflowRpcWorker worker, Map<String, Object> options) throws OpflowBootstrapException {
            if (worker == null) {
                throw new OpflowBootstrapException("RpcWorker should not be null");
            }
            options = OpflowUtil.ensureNotNull(options);
            logTracer = OpflowLogTracer.ROOT.branch("instantiatorId", options.getOrDefault("instantiatorId", OpflowUtil.getLogID()));
            rpcWorker = worker;
            listener = new OpflowRpcListener() {
                @Override
                public Boolean processMessage(OpflowMessage message, OpflowRpcResponse response) throws IOException {
                    String requestId = OpflowUtil.getRequestId(message.getInfo());
                    OpflowLogTracer listenerTrail = logTracer.branch("requestId", requestId);
                    String routineId = OpflowUtil.getRoutineId(message.getInfo());
                    String methodId = methodOfAlias.getOrDefault(routineId, routineId);
                    if (OpflowLogTracer.has(LOG, "info")) LOG.info(listenerTrail
                            .put("routineId", routineId)
                            .put("methodId", methodId)
                            .text("Receives new method call")
                            .stringify());
                    Method method = methodRef.get(methodId);
                    Object target = targetRef.get(methodId);
                    try {
                        Method origin = target.getClass().getMethod(method.getName(), method.getParameterTypes());
                        OpflowTargetRoutine routine = extractMethodInfo(origin);
                        if (routine != null && routine.enabled() == false) {
                            throw new UnsupportedOperationException("Method " + origin.toString() + " is disabled");
                        }
                        
                        String json = message.getBodyAsString();
                        if (OpflowLogTracer.has(LOG, "trace")) LOG.trace(listenerTrail
                                .put("arguments", json)
                                .text("Method arguments in json string")
                                .stringify());
                        Object[] args = OpflowJsontool.toObjectArray(json, method.getParameterTypes());
                        
                        Object returnValue = method.invoke(target, args);
                        String result = OpflowJsontool.toString(returnValue);
                        if (OpflowLogTracer.has(LOG, "trace")) LOG.trace(listenerTrail
                                .put("return", OpflowUtil.truncate(result))
                                .text("Return value of method")
                                .stringify());
                        response.emitCompleted(result);
                        
                        if (OpflowLogTracer.has(LOG, "info")) LOG.info(listenerTrail
                            .text("Method call has completed")
                            .stringify());
                    } catch (JsonSyntaxException error) {
                        response.emitFailed(OpflowUtil.buildMap()
                                .put("type", error.getClass().getName())
                                .put("message", error.getMessage())
                                .toString());
                        throw error;
                    } catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException ex) {
                        LOG.error(null, ex);
                        response.emitFailed(OpflowUtil.buildMap()
                                .put("type", ex.getClass().getName())
                                .put("message", ex.getMessage())
                                .toString());
                    } catch (InvocationTargetException ex) {
                        Throwable catched = (Exception) ex.getCause();
                        catched.getStackTrace();
                        response.emitFailed(OpflowUtil.buildMap()
                                .put("exceptionClass", catched.getClass().getName())
                                .put("exceptionPayload", OpflowJsontool.toString(catched))
                                .put("type", catched.getClass().getName())
                                .put("message", catched.getMessage())
                                .toString());
                    } catch (UnsupportedOperationException ex) {
                        ex.getStackTrace();
                        response.emitFailed(OpflowUtil.buildMap()
                                .put("exceptionClass", ex.getClass().getName())
                                .put("exceptionPayload", OpflowJsontool.toString(ex))
                                .put("type", ex.getClass().getName())
                                .put("message", ex.getMessage())
                                .toString());
                    } catch (Exception ex) {
                        response.emitFailed(OpflowUtil.buildMap()
                                .put("type", ex.getClass().getName())
                                .put("message", ex.getMessage())
                                .toString());
                    }
                    return null;
                }
            };
            if (Boolean.TRUE.equals(options.get("autorun"))) {
                process();
            }
        }
        
        public final void process() {
            if (!processing) {
                rpcWorker.process(routineIds, listener);
                processing = true;
            }
        }
        
        public void instantiateType(Class type) {
            instantiateType(type, null);
        }
        
        public void instantiateType(Class type, Object target) {
            if (type == null) {
                throw new OpflowInterceptionException("The [type] parameter must not be null");
            }
            if (Modifier.isAbstract(type.getModifiers()) && target == null) {
                if (OpflowLogTracer.has(LOG, "error")) LOG.error(logTracer
                        .text("Class should not be an abstract type")
                        .stringify());
                throw new OpflowInterceptionException("Class should not be an abstract type");
            }
            try {
                if (target == null) target = type.newInstance();
                for (Method method : type.getDeclaredMethods()) {
                    String methodId = OpflowUtil.getMethodSignature(method);
                    OpflowTargetRoutine routine = extractMethodInfo(method);
                    if (routine != null && routine.alias() != null) {
                        String[] aliases = routine.alias();
                        for(String alias:aliases) {
                            if (methodOfAlias.containsKey(alias)) {
                                throw new OpflowInterceptionException("Alias[" + alias + "]/routineId[" + methodId + "]" + 
                                        " is conflicted with alias of routineId[" + methodOfAlias.get(alias) + "]");
                            }
                            methodOfAlias.put(alias, methodId);
                            if (OpflowLogTracer.has(LOG, "trace")) LOG.trace(logTracer
                                    .put("alias", alias)
                                    .put("routineId", methodId)
                                    .text("link alias to routineId")
                                    .stringify());
                        }
                    }
                }
                routineIds.addAll(methodOfAlias.keySet());
                List<Class<?>> clazzes = OpflowUtil.getAllAncestorTypes(type);
                for(Class clz: clazzes) {
                    Method[] methods = clz.getDeclaredMethods();
                    for (Method method : methods) {
                        String methodId = OpflowUtil.getMethodSignature(method);
                        if (OpflowLogTracer.has(LOG, "trace")) LOG.trace(logTracer
                                .put("routineId", methodId)
                                .put("methodId", methodId)
                                .tags("Attach method to RpcWorker listener")
                                .text("Attach method to RpcWorker listener")
                                .stringify());
                        if (!routineIds.add(methodId) && !method.equals(methodRef.get(methodId))) {
                            throw new OpflowInterceptionException("routineId[" + methodId + "] is conflicted");
                        }
                        methodRef.put(methodId, method);
                        targetRef.put(methodId, target);
                    }
                }
            } catch (InstantiationException except) {
                if (OpflowLogTracer.has(LOG, "error")) LOG.error(logTracer
                        .put("errorType", except.getClass().getName())
                        .put("errorMessage", except.getMessage())
                        .text("Could not instantiate the class")
                        .stringify());
                throw new OpflowInterceptionException("Could not instantiate the class", except);
            } catch (IllegalAccessException except) {
                if (OpflowLogTracer.has(LOG, "error")) LOG.error(logTracer
                        .put("errorType", except.getClass().getName())
                        .put("errorMessage", except.getMessage())
                        .text("Constructor is not accessible")
                        .stringify());
                throw new OpflowInterceptionException("Constructor is not accessible", except);
            } catch (SecurityException except) {
                if (OpflowLogTracer.has(LOG, "error")) LOG.error(logTracer
                        .put("errorType", except.getClass().getName())
                        .put("errorMessage", except.getMessage())
                        .text("Class loaders is not the same or denies access")
                        .stringify());
                throw new OpflowInterceptionException("Class loaders is not the same or denies access", except);
            } catch (Exception except) {
                if (OpflowLogTracer.has(LOG, "error")) LOG.error(logTracer
                        .put("errorType", except.getClass().getName())
                        .put("errorMessage", except.getMessage())
                        .text("Unknown exception")
                        .stringify());
                throw new OpflowInterceptionException("Unknown exception", except);
            }
            process();
        }
        
        private OpflowTargetRoutine extractMethodInfo(Method method) {
            if (method.isAnnotationPresent(OpflowTargetRoutine.class)) {
                Annotation annotation = method.getAnnotation(OpflowTargetRoutine.class);
                OpflowTargetRoutine routine = (OpflowTargetRoutine) annotation;
                return routine;
            }
            return null;
        }
    }
    
    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            measurer.changeComponentInstance("serverlet", serverletId, OpflowPromMeasurer.GaugeAction.DEC);
        }
    }
}
