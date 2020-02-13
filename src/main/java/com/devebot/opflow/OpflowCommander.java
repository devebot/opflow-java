package com.devebot.opflow;

import com.devebot.opflow.OpflowLogTracer.Level;
import com.devebot.opflow.supports.OpflowJsonTool;
import com.devebot.opflow.supports.OpflowObjectTree;
import com.devebot.opflow.annotation.OpflowSourceRoutine;
import com.devebot.opflow.exception.OpflowBootstrapException;
import com.devebot.opflow.exception.OpflowInterceptionException;
import com.devebot.opflow.exception.OpflowRequestFailureException;
import com.devebot.opflow.exception.OpflowRequestTimeoutException;
import com.devebot.opflow.exception.OpflowRpcRegistrationException;
import com.devebot.opflow.exception.OpflowWorkerNotFoundException;
import com.devebot.opflow.supports.OpflowDateTime;
import com.devebot.opflow.supports.OpflowSysInfo;
import io.undertow.server.RoutingHandler;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author drupalex
 */
public class OpflowCommander implements AutoCloseable {
    public final static List<String> SERVICE_BEAN_NAMES = Arrays.asList(new String[] {
        "configurer", "rpcMaster", "publisher"
    });

    public final static List<String> SUPPORT_BEAN_NAMES = Arrays.asList(new String[] {
        "reqExtractor", "restrictor", "rpcWatcher", "promExporter", "restServer"
    });

    public final static List<String> ALL_BEAN_NAMES = OpflowUtil.mergeLists(SERVICE_BEAN_NAMES, SUPPORT_BEAN_NAMES);

    public final static String PARAM_RESERVED_WORKER_ENABLED = "reservedWorkerEnabled";

    private final static Logger LOG = LoggerFactory.getLogger(OpflowCommander.class);

    private final boolean strictMode;
    private final String instanceId;
    private final OpflowLogTracer logTracer;
    private final OpflowPromMeasurer measurer;
    private final OpflowConfig.Loader configLoader;

    private OpflowRestrictorMaster restrictor;
    
    private boolean reservedWorkerEnabled;
    private OpflowPubsubHandler configurer;
    private OpflowRpcMaster rpcMaster;
    private OpflowPubsubHandler publisher;
    private OpflowRpcChecker rpcChecker;
    private OpflowRpcWatcher rpcWatcher;
    private OpflowRestServer restServer;
    private OpflowReqExtractor reqExtractor;

    public OpflowCommander() throws OpflowBootstrapException {
        this(null, null);
    }
    
    public OpflowCommander(OpflowConfig.Loader loader) throws OpflowBootstrapException {
        this(loader, null);
    }

    public OpflowCommander(Map<String, Object> kwargs) throws OpflowBootstrapException {
        this(null, kwargs);
    }

    private OpflowCommander(OpflowConfig.Loader loader, Map<String, Object> kwargs) throws OpflowBootstrapException {
        if (loader != null) {
            configLoader = loader;
        } else {
            configLoader = null;
        }
        if (configLoader != null) {
            kwargs = configLoader.loadConfiguration();
        }
        kwargs = OpflowUtil.ensureNotNull(kwargs);
        strictMode = OpflowUtil.getOptionValue(kwargs, "strictMode", Boolean.class, Boolean.FALSE);
        instanceId = OpflowUtil.getOptionField(kwargs, "instanceId", true);
        logTracer = OpflowLogTracer.ROOT.branch("commanderId", instanceId);

        if (logTracer.ready(LOG, Level.INFO)) LOG.info(logTracer
                .text("Commander[${commanderId}].new()")
                .stringify());

        measurer = OpflowPromMeasurer.getInstance((Map<String, Object>) kwargs.get("promExporter"));
        
        Map<String, Object> restrictorCfg = (Map<String, Object>)kwargs.get("restrictor");
        
        if (restrictorCfg == null || OpflowUtil.isComponentEnabled(restrictorCfg)) {
            restrictor = new OpflowRestrictorMaster(OpflowObjectTree.buildMap(restrictorCfg)
                    .put("instanceId", instanceId)
                    .toMap());
        }
        
        if (restrictor != null) {
            restrictor.block();
        }
        
        this.init(kwargs);
        
        measurer.updateComponentInstance("commander", instanceId, OpflowPromMeasurer.GaugeAction.INC);
        
        if (logTracer.ready(LOG, Level.INFO)) LOG.info(logTracer
                .text("Commander[${commanderId}].new() end!")
                .stringify());
    }
    
    private void init(Map<String, Object> kwargs) throws OpflowBootstrapException {
        if (kwargs.get(PARAM_RESERVED_WORKER_ENABLED) != null && kwargs.get(PARAM_RESERVED_WORKER_ENABLED) instanceof Boolean) {
            reservedWorkerEnabled = (Boolean) kwargs.get(PARAM_RESERVED_WORKER_ENABLED);
        } else {
            reservedWorkerEnabled = true;
        }

        Map<String, Object> reqExtractorCfg = (Map<String, Object>)kwargs.get("reqExtractor");
        Map<String, Object> configurerCfg = (Map<String, Object>)kwargs.get("configurer");
        Map<String, Object> rpcMasterCfg = (Map<String, Object>)kwargs.get("rpcMaster");
        Map<String, Object> publisherCfg = (Map<String, Object>)kwargs.get("publisher");
        Map<String, Object> rpcWatcherCfg = (Map<String, Object>)kwargs.get("rpcWatcher");
        Map<String, Object> restServerCfg = (Map<String, Object>)kwargs.get("restServer");

        HashSet<String> checkExchange = new HashSet<>();

        if (OpflowUtil.isComponentEnabled(configurerCfg)) {
            if (OpflowUtil.isAMQPEntrypointNull(configurerCfg)) {
                throw new OpflowBootstrapException("Invalid Configurer connection parameters");
            }
            if (!checkExchange.add(OpflowUtil.getAMQPEntrypointCode(configurerCfg))) {
                throw new OpflowBootstrapException("Duplicated Configurer connection parameters");
            }
        }

        if (OpflowUtil.isComponentEnabled(rpcMasterCfg)) {
            if (OpflowUtil.isAMQPEntrypointNull(rpcMasterCfg)) {
                throw new OpflowBootstrapException("Invalid RpcMaster connection parameters");
            }
            if (!checkExchange.add(OpflowUtil.getAMQPEntrypointCode(rpcMasterCfg))) {
                throw new OpflowBootstrapException("Duplicated RpcMaster connection parameters");
            }
        }

        if (OpflowUtil.isComponentEnabled(publisherCfg)) {
            if (OpflowUtil.isAMQPEntrypointNull(publisherCfg)) {
                throw new OpflowBootstrapException("Invalid Publisher connection parameters");
            }
            if (!checkExchange.add(OpflowUtil.getAMQPEntrypointCode(publisherCfg))) {
                throw new OpflowBootstrapException("Duplicated Publisher connection parameters");
            }
        }

        try {
            if (reqExtractorCfg == null || OpflowUtil.isComponentEnabled(reqExtractorCfg)) {
                reqExtractor = new OpflowReqExtractor(reqExtractorCfg);
            }

            if (OpflowUtil.isComponentEnabled(configurerCfg)) {
                configurer = new OpflowPubsubHandler(OpflowObjectTree.buildMap(new OpflowObjectTree.Listener() {
                    @Override
                    public void transform(Map<String, Object> opts) {
                        opts.put("instanceId", instanceId);
                        opts.put("measurer", measurer);
                    }
                }, configurerCfg).toMap());
            }
            if (OpflowUtil.isComponentEnabled(rpcMasterCfg)) {
                rpcMaster = new OpflowRpcMaster(OpflowObjectTree.buildMap(new OpflowObjectTree.Listener() {
                    @Override
                    public void transform(Map<String, Object> opts) {
                        opts.put("instanceId", instanceId);
                        opts.put("measurer", measurer);
                    }
                }, rpcMasterCfg).toMap());
            }
            if (OpflowUtil.isComponentEnabled(publisherCfg)) {
                publisher = new OpflowPubsubHandler(OpflowObjectTree.buildMap(new OpflowObjectTree.Listener() {
                    @Override
                    public void transform(Map<String, Object> opts) {
                        opts.put("instanceId", instanceId);
                        opts.put("measurer", measurer);
                    }
                }, publisherCfg).toMap());
            }

            rpcChecker = new OpflowRpcCheckerMaster(restrictor.getValveRestrictor(), rpcMaster);

            rpcWatcher = new OpflowRpcWatcher(rpcChecker, OpflowObjectTree.buildMap(rpcWatcherCfg)
                    .put("instanceId", instanceId)
                    .toMap());

            OpflowInfoCollector infoCollector = new OpflowInfoCollectorMaster(instanceId, measurer, restrictor, rpcMaster, handlers, rpcWatcher);

            OpflowTaskSubmitter taskSubmitter = new OpflowTaskSubmitterMaster(instanceId, measurer, restrictor, rpcMaster, handlers);
            
            restServer = new OpflowRestServer(infoCollector, taskSubmitter, rpcChecker, OpflowObjectTree.buildMap(restServerCfg)
                    .put("instanceId", instanceId)
                    .toMap());
        } catch(OpflowBootstrapException exception) {
            this.close();
            throw exception;
        }
    }
    
    public boolean isReservedWorkerEnabled() {
        return this.reservedWorkerEnabled;
    }
    
    public void setReservedWorkerEnabled(boolean enabled) {
        this.reservedWorkerEnabled = enabled;
    }

    public RoutingHandler getDefaultHandlers() {
        if (restServer != null) {
            return restServer.getDefaultHandlers();
        }
        return null;
    }
    
    public void ping(String query) throws Throwable {
        rpcChecker.send(new OpflowRpcChecker.Ping(query));
    }
    
    public final void serve() {
        serve(null);
    }

    public final void serve(RoutingHandler httpHandlers) {
        if (logTracer.ready(LOG, Level.INFO)) LOG.info(logTracer
                .text("Commander[${commanderId}].serve() begin")
                .stringify());
        
        OpflowUUID.start();
        
        if (rpcWatcher != null) {
            rpcWatcher.start();
        }
        if (restServer != null) {
            if (httpHandlers == null) {
                restServer.serve();
            } else {
                restServer.serve(httpHandlers);
            }
        }
        if (restrictor != null) {
            restrictor.unblock();
        }
        
        if (logTracer.ready(LOG, Level.INFO)) LOG.info(logTracer
                .text("Commander[${commanderId}].serve() end")
                .stringify());
    }

    @Override
    public final void close() {
        if (logTracer.ready(LOG, Level.INFO)) LOG.info(logTracer
                .text("Commander[${commanderId}].close()")
                .stringify());

        if (restrictor != null) {
            restrictor.block();
        }

        if (restServer != null) restServer.close();
        if (rpcWatcher != null) rpcWatcher.close();

        if (publisher != null) publisher.close();
        if (rpcMaster != null) rpcMaster.close();
        if (configurer != null) configurer.close();

        if (restrictor != null) {
            restrictor.close();
        }

        OpflowUUID.release();

        if (logTracer.ready(LOG, Level.INFO)) LOG.info(logTracer
                .text("Commander[${commanderId}].close() has done!")
                .stringify());
    }
    
    private static class OpflowRestrictorMaster extends OpflowRestrictable.Runner implements AutoCloseable {
        private final static Logger LOG = LoggerFactory.getLogger(OpflowRestrictorMaster.class);

        protected final String instanceId;
        protected final OpflowLogTracer logTracer;

        private final OpflowRestrictor.OnOff onoffRestrictor;
        private final OpflowRestrictor.Valve valveRestrictor;
        private final OpflowRestrictor.Pause pauseRestrictor;
        private final OpflowRestrictor.Limit limitRestrictor;

        public OpflowRestrictorMaster(Map<String, Object> options) {
            options = OpflowUtil.ensureNotNull(options);

            instanceId = OpflowUtil.getOptionField(options, "instanceId", true);
            logTracer = OpflowLogTracer.ROOT.branch("restrictorId", instanceId);

            if (logTracer.ready(LOG, Level.INFO)) LOG.info(logTracer
                    .text("Restrictor[${restrictorId}].new()")
                    .stringify());

            onoffRestrictor = new OpflowRestrictor.OnOff(options);
            valveRestrictor = new OpflowRestrictor.Valve(options);
            pauseRestrictor = new OpflowRestrictor.Pause(options);
            limitRestrictor = new OpflowRestrictor.Limit(options);

            super.append(onoffRestrictor.setLogTracer(logTracer));
            super.append(valveRestrictor.setLogTracer(logTracer));
            super.append(pauseRestrictor.setLogTracer(logTracer));
            super.append(limitRestrictor.setLogTracer(logTracer));

            if (logTracer.ready(LOG, Level.INFO)) LOG.info(logTracer
                    .text("Restrictor[${restrictorId}].new() end!")
                    .stringify());
        }

        public String getInstanceId() {
            return instanceId;
        }

        public OpflowRestrictor.Valve getValveRestrictor() {
            return valveRestrictor;
        }

        public boolean isActive() {
            return onoffRestrictor.isActive();
        }

        public void setActive(boolean enabled) {
            onoffRestrictor.setActive(enabled);
        }

        public boolean isBlocked() {
            return valveRestrictor.isBlocked();
        }

        public void block() {
            valveRestrictor.block();
        }

        public void unblock() {
            valveRestrictor.unblock();
        }

        public boolean isPauseEnabled() {
            return pauseRestrictor.isPauseEnabled();
        }

        public long getPauseTimeout() {
            return pauseRestrictor.getPauseTimeout();
        }

        public long getPauseDuration() {
            return pauseRestrictor.getPauseDuration();
        }

        public long getPauseElapsed() {
            return pauseRestrictor.getPauseElapsed();
        }

        public boolean isPaused() {
            return pauseRestrictor.isPaused();
        }

        public Map<String, Object> pause(long duration) {
            return pauseRestrictor.pause(duration);
        }

        public Map<String, Object> unpause() {
            return pauseRestrictor.unpause();
        }

        public int getSemaphoreLimit() {
            return limitRestrictor.getSemaphoreLimit();
        }

        public int getSemaphorePermits() {
            return limitRestrictor.getSemaphorePermits();
        }

        public boolean isSemaphoreEnabled() {
            return limitRestrictor.isSemaphoreEnabled();
        }

        public long getSemaphoreTimeout() {
            return limitRestrictor.getSemaphoreTimeout();
        }

        @Override
        public void close() {
            pauseRestrictor.close();
        }
    }

    private static class OpflowRpcCheckerMaster extends OpflowRpcChecker {

        private final static String DEFAULT_BALL_JSON = OpflowJsonTool.toString(new Object[] { new Ping() });

        private final OpflowRestrictor.Valve restrictor;
        private final OpflowRpcMaster rpcMaster;

        OpflowRpcCheckerMaster(OpflowRestrictor.Valve restrictor, OpflowRpcMaster rpcMaster) throws OpflowBootstrapException {
            this.restrictor = restrictor;
            this.rpcMaster = rpcMaster;
        }

        @Override
        public Pong send(final Ping ping) throws Throwable {
            if (this.restrictor == null) {
                return _send_safe(ping);
            }
            return this.restrictor.filter(new OpflowRestrictor.Action<Pong>() {
                @Override
                public Pong process() throws Throwable {
                    return _send_safe(ping);
                }
            });
        }
        private Pong _send_safe(final Ping ping) throws Throwable {
            Date startTime = new Date();
            String body = (ping == null) ? DEFAULT_BALL_JSON : OpflowJsonTool.toString(new Object[] { ping });
            String requestId = OpflowUUID.getBase64ID();
            String requestTime = OpflowDateTime.toISO8601UTC(startTime);
            OpflowRpcRequest rpcRequest = rpcMaster.request(getSendMethodName(), body, (new OpflowRpcParameter(requestId, requestTime))
                    .setProgressEnabled(false)
                    .setMessageScope("internal"));
            OpflowRpcResult rpcResult = rpcRequest.extractResult(false);
            Date endTime = new Date();

            if (rpcResult.isTimeout()) {
                throw new OpflowRequestTimeoutException("OpflowRpcChecker.send() call is timeout");
            }

            if (rpcResult.isFailed()) {
                Map<String, Object> errorMap = OpflowJsonTool.toObjectMap(rpcResult.getErrorAsString());
                throw rebuildInvokerException(errorMap);
            }

            Pong pong = OpflowJsonTool.toObject(rpcResult.getValueAsString(), Pong.class);
            pong.getParameters().put("requestId", requestId);
            pong.getParameters().put("startTime", OpflowDateTime.toISO8601UTC(startTime));
            pong.getParameters().put("endTime", OpflowDateTime.toISO8601UTC(endTime));
            pong.getParameters().put("elapsedTime", endTime.getTime() - startTime.getTime());
            return pong;
        }
    }

    private static class OpflowTaskSubmitterMaster implements OpflowTaskSubmitter {

        private final String instanceId;
        private final OpflowPromMeasurer measurer;
        private final OpflowLogTracer logTracer;
        private final OpflowRestrictorMaster restrictor;
        private final OpflowRpcMaster rpcMaster;
        private final Map<String, RpcInvocationHandler> handlers;

        public OpflowTaskSubmitterMaster(String instanceId,
                OpflowPromMeasurer measurer,
                OpflowRestrictorMaster restrictor,
                OpflowRpcMaster rpcMaster,
                Map<String, RpcInvocationHandler> mappings) {
            this.instanceId = instanceId;
            this.measurer = measurer;
            this.restrictor = restrictor;
            this.rpcMaster = rpcMaster;
            this.handlers = mappings;
            this.logTracer = OpflowLogTracer.ROOT.branch("taskSubmitterId", instanceId);
        }

        @Override
        public Map<String, Object> pause(long duration) {
            if (logTracer.ready(LOG, Level.INFO)) LOG.info(logTracer
                    .text("OpflowTaskSubmitter[${taskSubmitterId}].pause(true) is invoked")
                    .stringify());
            if (restrictor == null) {
                return OpflowObjectTree.buildMap()
                        .toMap();
            }
            return restrictor.pause(duration);
        }
        
        @Override
        public Map<String, Object> unpause() {
            if (logTracer.ready(LOG, Level.INFO)) LOG.info(logTracer
                    .text("OpflowTaskSubmitter[${taskSubmitterId}].unpause() is invoked")
                    .stringify());
            if (restrictor == null) {
                return OpflowObjectTree.buildMap()
                        .toMap();
            }
            return restrictor.unpause();
        }
        
        @Override
        public Map<String, Object> reset() {
            if (rpcMaster == null) {
                return OpflowObjectTree.buildMap()
                        .toMap();
            }
            if (logTracer.ready(LOG, Level.INFO)) LOG.info(logTracer
                    .text("OpflowTaskSubmitter[${taskSubmitterId}].reset() is invoked")
                    .stringify());
            rpcMaster.close();
            return OpflowObjectTree.buildMap()
                    .toMap();
        }
        
        @Override
        public Map<String, Object> resetRpcInvocationCounter() {
            return OpflowObjectTree.buildMap()
                    .put("measurement", measurer.resetRpcInvocationCounter())
                    .toMap();
        }

        @Override
        public Map<String, Object> activateDetachedWorker(boolean state, Map<String, Object> opts) {
            return activateWorker("DetachedWorker", state, opts);
        }
        
        @Override
        public Map<String, Object> activateReservedWorker(boolean state, Map<String, Object> opts) {
            return activateWorker("ReservedWorker", state, opts);
        }
        
        private Map<String, Object> activateWorker(String type, boolean state, Map<String, Object> opts) {
            String clazz = (String) OpflowUtil.getOptionField(opts, "class", null);
            for(final Map.Entry<String, RpcInvocationHandler> entry : handlers.entrySet()) {
                final String key = entry.getKey();
                final RpcInvocationHandler val = entry.getValue();
                if (clazz != null) {
                    if (clazz.equals(key)) {
                        activateWorkerForRpcInvocation(val, type, state);
                    }
                } else {
                    activateWorkerForRpcInvocation(val, type, state);
                }
            }
            return OpflowObjectTree.buildMap()
                    .put("mappings", OpflowInfoCollectorMaster.renderRpcInvocationHandlers(handlers))
                    .toMap();
        }
        
        private void activateWorkerForRpcInvocation(RpcInvocationHandler val, String type, boolean state) {
            switch (type) {
                case "DetachedWorker":
                    val.setDetachedWorkerActive(state);
                    break;
                case "ReservedWorker":
                    val.setReservedWorkerActive(state);
                    break;
            }
        }
    }

    private static class OpflowInfoCollectorMaster implements OpflowInfoCollector {

        private final String instanceId;
        private final OpflowPromMeasurer measurer;
        private final OpflowRestrictorMaster restrictor;
        private final OpflowRpcWatcher rpcWatcher;
        private final OpflowRpcMaster rpcMaster;
        private final Map<String, RpcInvocationHandler> handlers;
        private final Date startTime;

        public OpflowInfoCollectorMaster(String instanceId,
                OpflowPromMeasurer measurer,
                OpflowRestrictorMaster restrictor,
                OpflowRpcMaster rpcMaster,
                Map<String, RpcInvocationHandler> mappings,
                OpflowRpcWatcher rpcWatcher
        ) {
            this.instanceId = instanceId;
            this.measurer = measurer;
            this.restrictor = restrictor;
            this.rpcWatcher = rpcWatcher;
            this.rpcMaster = rpcMaster;
            this.handlers = mappings;
            this.startTime = new Date();
        }

        @Override
        public Map<String, Object> collect() {
            return collect(new HashMap<String, Boolean>());
        }

        @Override
        public Map<String, Object> collect(String scope) {
            final String label = (scope == null) ? SCOPE_BASIC : scope;
            
            Map<String, Boolean> flags = new HashMap<>();
            flags.put(label, true);
            
            return collect(flags);
        }

        private boolean checkOption(Map<String, Boolean> options, String optionName) {
            Boolean opt = options.get(optionName);
            return opt != null && opt;
        }
        
        @Override
        public Map<String, Object> collect(Map<String, Boolean> options) {
            final Map<String, Boolean> flag = (options != null) ? options : new HashMap<String, Boolean>();
            
            OpflowObjectTree.Builder root = OpflowObjectTree.buildMap();
            
            root.put("commander", OpflowObjectTree.buildMap(new OpflowObjectTree.Listener() {
                @Override
                public void transform(Map<String, Object> opts) {
                    opts.put("instanceId", instanceId);

                    // measurement
                    if (checkOption(flag, SCOPE_FULL)) {
                        if (measurer != null) {
                            OpflowPromMeasurer.RpcInvocationCounter counter = measurer.getRpcInvocationCounter("commander");
                            if (counter != null) {
                                opts.put("measurement", counter.toMap(true, checkOption(flag, "verbose")));
                            }
                        }
                    }

                    // restrictor information
                    if (checkOption(flag, SCOPE_FULL)) {
                        if (restrictor != null) {
                            opts.put("restrictor", OpflowObjectTree.buildMap(new OpflowObjectTree.Listener() {
                                @Override
                                public void transform(Map<String, Object> opt2) {
                                    int availablePermits = restrictor.getSemaphorePermits();
                                    opt2.put("pauseEnabled", restrictor.isPauseEnabled());
                                    opt2.put("pauseTimeout", restrictor.getPauseTimeout());
                                    boolean isPaused = restrictor.isPaused();
                                    opt2.put("pauseStatus", isPaused ? "on" : "off");
                                    if (isPaused) {
                                        opt2.put("pauseElapsed", restrictor.getPauseElapsed());
                                        opt2.put("pauseDuration", restrictor.getPauseDuration());
                                    }
                                    opt2.put("semaphoreLimit", restrictor.getSemaphoreLimit());
                                    opt2.put("semaphoreUsedPermits", restrictor.getSemaphoreLimit() - availablePermits);
                                    opt2.put("semaphoreFreePermits", availablePermits);
                                    opt2.put("semaphoreEnabled", restrictor.isSemaphoreEnabled());
                                    opt2.put("semaphoreTimeout", restrictor.getSemaphoreTimeout());
                                }
                            }).toMap());
                        } else {
                            opts.put("restrictor", OpflowObjectTree.buildMap()
                                    .put("enabled", false)
                                    .toMap());
                        }
                    }
                    
                    // rpcMaster information
                    if (rpcMaster != null) {
                        opts.put("rpcMaster", OpflowObjectTree.buildMap(new OpflowObjectTree.Listener() {
                            @Override
                            public void transform(Map<String, Object> opt2) {
                                OpflowEngine engine = rpcMaster.getEngine();
                                
                                opt2.put("instanceId", rpcMaster.getInstanceId());
                                opt2.put("applicationId", engine.getApplicationId());
                                opt2.put("exchangeName", engine.getExchangeName());

                                if (checkOption(flag, SCOPE_FULL)) {
                                    opt2.put("exchangeDurable", engine.getExchangeDurable());
                                }

                                opt2.put("routingKey", engine.getRoutingKey());

                                if (checkOption(flag, SCOPE_FULL)) {
                                    opt2.put("otherKeys", engine.getOtherKeys());
                                }

                                opt2.put("callbackQueue", rpcMaster.getCallbackName());

                                if (checkOption(flag, SCOPE_FULL)) {
                                    opt2.put("callbackDurable", rpcMaster.getCallbackDurable());
                                    opt2.put("callbackExclusive", rpcMaster.getCallbackExclusive());
                                    opt2.put("callbackAutoDelete", rpcMaster.getCallbackAutoDelete());
                                }

                                opt2.put("request", OpflowObjectTree.buildMap()
                                        .put("expiration", rpcMaster.getExpiration())
                                        .toMap());
                            }
                        }).toMap());
                    }
                    
                    // RPC mappings
                    if (checkOption(flag, SCOPE_FULL)) {
                        opts.put("mappings", renderRpcInvocationHandlers(handlers));
                    }
                    
                    // RpcWatcher information
                    if (checkOption(flag, SCOPE_FULL)) {
                        opts.put("rpcWatcher", OpflowObjectTree.buildMap()
                                .put("enabled", rpcWatcher.isEnabled())
                                .put("interval", rpcWatcher.getInterval())
                                .put("count", rpcWatcher.getCount())
                                .put("congested", rpcWatcher.isCongested())
                                .toMap());
                    }
                }
            }).toMap());

            // start-time & uptime
            if (checkOption(flag, SCOPE_FULL)) {
                Date currentTime = new Date();
                root.put("miscellaneous", OpflowObjectTree.buildMap()
                        .put("threadCount", Thread.activeCount())
                        .put("startTime", OpflowDateTime.toISO8601UTC(startTime))
                        .put("currentTime", OpflowDateTime.toISO8601UTC(currentTime))
                        .put("uptime", OpflowDateTime.printElapsedTime(startTime, currentTime))
                        .toMap());
            }
            
            // git commit information
            if (checkOption(flag, SCOPE_FULL)) {
                root.put("source-code-info", OpflowObjectTree.buildMap()
                        .put("master", OpflowSysInfo.getGitInfo("META-INF/scm/service-master/git-info.json"))
                        .put("opflow", OpflowSysInfo.getGitInfo())
                        .toMap());
            }
            
            return root.toMap();
        }
        
        protected static List<Map<String, Object>> renderRpcInvocationHandlers(Map<String, RpcInvocationHandler> handlers) {
            List<Map<String, Object>> mappingInfos = new ArrayList<>();
            for(final Map.Entry<String, RpcInvocationHandler> entry : handlers.entrySet()) {
                final RpcInvocationHandler val = entry.getValue();
                mappingInfos.add(OpflowObjectTree.buildMap(new OpflowObjectTree.Listener() {
                    @Override
                    public void transform(Map<String, Object> opts) {
                        opts.put("class", entry.getKey());
                        opts.put("methods", val.getMethodNames());
                        opts.put("isReservedWorkerActive", val.isReservedWorkerActive());
                        opts.put("isReservedWorkerAvailable", val.isReservedWorkerAvailable());
                        opts.put("isDetachedWorkerActive", val.isDetachedWorkerActive());
                        if (val.getReservedWorkerClassName() != null) {
                            opts.put("reservedWorkerClassName", val.getReservedWorkerClassName());
                        }
                    }
                }).toMap());
            }
            return mappingInfos;
        }
    }

    private static class RpcInvocationHandler implements InvocationHandler {
        private final OpflowLogTracer logTracer;
        private final OpflowPromMeasurer measurer;
        private final OpflowRestrictorMaster restrictor;
        private final OpflowReqExtractor reqExtractor;
        private final OpflowRpcWatcher rpcWatcher;
        
        private final OpflowRpcMaster rpcMaster;
        private final OpflowPubsubHandler publisher;
        
        private final Class clazz;
        private final Object reservedWorker;
        private final boolean reservedWorkerEnabled;
        private final Map<String, String> aliasOfMethod = new HashMap<>();
        private final Map<String, Boolean> methodIsAsync = new HashMap<>();
        
        private boolean detachedWorkerActive = true;
        private boolean reservedWorkerActive = true;
        
        public RpcInvocationHandler(OpflowLogTracer logTracer, OpflowPromMeasurer measurer,
                OpflowRestrictorMaster restrictor, OpflowReqExtractor reqExtractor, OpflowRpcWatcher rpcWatcher,
                OpflowRpcMaster rpcMaster, OpflowPubsubHandler publisher,
                Class clazz, Object reservedWorker, boolean reservedWorkerEnabled
        ) {
            this.logTracer = logTracer;
            this.measurer = measurer;
            this.restrictor = restrictor;
            this.reqExtractor = reqExtractor;
            this.rpcWatcher = rpcWatcher;
            
            this.rpcMaster = rpcMaster;
            this.publisher = publisher;
            
            this.clazz = clazz;
            this.reservedWorker = reservedWorker;
            this.reservedWorkerEnabled = reservedWorkerEnabled;
            for (Method method : this.clazz.getDeclaredMethods()) {
                String methodId = OpflowUtil.getMethodSignature(method);
                OpflowSourceRoutine routine = extractMethodInfo(method);
                if (routine != null && routine.alias() != null && routine.alias().length() > 0) {
                    String alias = routine.alias();
                    if (aliasOfMethod.containsValue(alias)) {
                        throw new OpflowInterceptionException("Alias[" + alias + "]/routineId[" + methodId + "] is duplicated");
                    }
                    aliasOfMethod.put(methodId, alias);
                    if (logTracer.ready(LOG, Level.TRACE)) LOG.trace(logTracer
                            .put("alias", alias)
                            .put("routineId", methodId)
                            .text("link alias to routineId")
                            .stringify());
                }
                methodIsAsync.put(methodId, (routine != null) && routine.isAsync());
            }
        }

        public Set<String> getMethodNames() {
            return methodIsAsync.keySet();
        }

        public boolean isDetachedWorkerActive() {
            return detachedWorkerActive;
        }

        public void setDetachedWorkerActive(boolean detachedWorkerActive) {
            this.detachedWorkerActive = detachedWorkerActive;
        }

        public boolean isReservedWorkerActive() {
            return reservedWorkerActive;
        }

        public void setReservedWorkerActive(boolean reservedWorkerActive) {
            this.reservedWorkerActive = reservedWorkerActive;
        }

        public boolean isReservedWorkerAvailable() {
            return this.reservedWorker != null && this.reservedWorkerEnabled && this.reservedWorkerActive;
        }

        public String getReservedWorkerClassName() {
            if (this.reservedWorker == null) return null;
            return this.reservedWorker.getClass().getName();
        }
        
        public Integer getReservedWorkerHashCode() {
            if (this.reservedWorker == null) return null;
            return this.reservedWorker.hashCode();
        }
        
        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            if (this.restrictor == null) {
                return _invoke(proxy, method, args);
            }
            return this.restrictor.filter(new OpflowRestrictor.Action<Object>() {
                @Override
                public Object process() throws Throwable {
                    return _invoke(proxy, method, args);
                }
            });
        }
        
        private Object _invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // determine the requestId
            final String requestId;
            if (reqExtractor == null) {
                requestId = OpflowUUID.getBase64ID();
            } else {
                String _requestId = reqExtractor.extractRequestId(args);
                requestId = (_requestId != null) ? _requestId : OpflowUUID.getBase64ID();
            }

            // determine the requestTime
            final String requestTime = OpflowDateTime.getCurrentTimeString();

            // create the logTracer
            final OpflowLogTracer logRequest = logTracer.branch("requestTime", requestTime).branch("requestId", requestId);

            // get the method signature
            String methodId = OpflowUtil.getMethodSignature(method);
            
            // convert the method signature to routineId
            String routineId = aliasOfMethod.getOrDefault(methodId, methodId);

            Boolean isAsync = methodIsAsync.getOrDefault(methodId, false);
            if (logRequest.ready(LOG, Level.INFO)) LOG.info(logRequest
                    .put("methodId", methodId)
                    .put("routineId", routineId)
                    .put("isAsync", isAsync)
                    .text("Request[${requestId}][${requestTime}] - RpcInvocationHandler.invoke() - method[${routineId}] is async: ${isAsync}")
                    .stringify());

            if (args == null) args = new Object[0];
            String body = OpflowJsonTool.toString(args);

            if (logRequest.ready(LOG, Level.TRACE)) LOG.trace(logRequest
                    .put("args", args)
                    .put("body", body)
                    .text("Request[${requestId}][${requestTime}] - RpcInvocationHandler.invoke() details")
                    .stringify());

            if (this.publisher != null && isAsync && void.class.equals(method.getReturnType())) {
                if (logRequest.ready(LOG, Level.DEBUG)) LOG.trace(logRequest
                        .text("Request[${requestId}][${requestTime}] - RpcInvocationHandler.invoke() dispatch the call to the publisher")
                        .stringify());
                measurer.countRpcInvocation("commander", "publisher", routineId, "begin");
                this.publisher.publish(body, OpflowObjectTree.buildMap(false)
                        .put("requestId", requestId)
                        .put("requestTime", requestTime)
                        .put("routineId", routineId)
                        .toMap());
                return null;
            } else {
                if (logRequest.ready(LOG, Level.DEBUG)) LOG.trace(logRequest
                        .text("Request[${requestId}][${requestTime}] - RpcInvocationHandler.invoke() dispatch the call to the rpcMaster")
                        .stringify());
                measurer.countRpcInvocation("commander", "master", routineId, "begin");
            }
            
            // rpc switching
            if (rpcWatcher.isCongested() || !detachedWorkerActive) {
                if (this.isReservedWorkerAvailable()) {
                    if (logRequest.ready(LOG, Level.DEBUG)) LOG.trace(logRequest
                            .text("Request[${requestId}][${requestTime}] - RpcInvocationHandler.invoke() retains the reservedWorker")
                            .stringify());
                    measurer.countRpcInvocation("commander", "reserved_worker", routineId, "retain");
                    return method.invoke(this.reservedWorker, args);
                }
            }

            if (!detachedWorkerActive) {
                throw new OpflowWorkerNotFoundException("both reserved worker and detached worker are deactivated");
            }

            OpflowRpcRequest rpcSession = rpcMaster.request(routineId, body, (new OpflowRpcParameter(requestId, requestTime))
                    .setProgressEnabled(false));
            OpflowRpcResult rpcResult = rpcSession.extractResult(false);

            if (rpcResult.isTimeout()) {
                rpcWatcher.setCongested(true);
                if (this.isReservedWorkerAvailable()) {
                    if (logRequest.ready(LOG, Level.DEBUG)) LOG.trace(logRequest
                            .text("Request[${requestId}][${requestTime}] - RpcInvocationHandler.invoke() rescues by the reservedWorker")
                            .stringify());
                    measurer.countRpcInvocation("commander", "reserved_worker", routineId, "rescue");
                    return method.invoke(this.reservedWorker, args);
                }
                if (logRequest.ready(LOG, Level.DEBUG)) LOG.trace(logRequest
                        .text("Request[${requestId}][${requestTime}] - RpcInvocationHandler.invoke() is timeout")
                        .stringify());
                measurer.countRpcInvocation("commander", "detached_worker", routineId, "timeout");
                throw new OpflowRequestTimeoutException();
            }

            if (rpcResult.isFailed()) {
                measurer.countRpcInvocation("commander", "detached_worker", routineId, "failed");
                if (logRequest.ready(LOG, Level.DEBUG)) LOG.trace(logRequest
                        .text("Request[${requestId}][${requestTime}] - RpcInvocationHandler.invoke() has failed")
                        .stringify());
                Map<String, Object> errorMap = OpflowJsonTool.toObjectMap(rpcResult.getErrorAsString());
                throw rebuildInvokerException(errorMap);
            }

            if (logRequest.ready(LOG, Level.DEBUG)) LOG.trace(logRequest
                    .put("returnType", method.getReturnType().getName())
                    .put("returnValue", rpcResult.getValueAsString())
                    .text("Request[${requestId}][${requestTime}] - RpcInvocationHandler.invoke() return the output")
                    .stringify());

            measurer.countRpcInvocation("commander", "detached_worker", routineId, "ok");

            if (method.getReturnType() == void.class) return null;

            return OpflowJsonTool.toObject(rpcResult.getValueAsString(), method.getReturnType());
        }
        
        private static OpflowSourceRoutine extractMethodInfo(Method method) {
            if (!method.isAnnotationPresent(OpflowSourceRoutine.class)) return null;
            Annotation annotation = method.getAnnotation(OpflowSourceRoutine.class);
            OpflowSourceRoutine routine = (OpflowSourceRoutine) annotation;
            return routine;
        }
    }

    private static Throwable rebuildInvokerException(Map<String, Object> errorMap) {
        Object exceptionName = errorMap.get("exceptionClass");
        Object exceptionPayload = errorMap.get("exceptionPayload");
        if (exceptionName != null && exceptionPayload != null) {
            try {
                Class exceptionClass = Class.forName(exceptionName.toString());
                return (Throwable) OpflowJsonTool.toObject(exceptionPayload.toString(), exceptionClass);
            } catch (ClassNotFoundException ex) {
                return rebuildFailureException(errorMap);
            }
        }
        return rebuildFailureException(errorMap);
    }

    private static Throwable rebuildFailureException(Map<String, Object> errorMap) {
        if (errorMap.get("message") != null) {
            return new OpflowRequestFailureException(errorMap.get("message").toString());
        }
        return new OpflowRequestFailureException();
    }

    private final Map<String, RpcInvocationHandler> handlers = new LinkedHashMap<>();

    private <T> RpcInvocationHandler getInvocationHandler(Class<T> clazz, T bean) {
        validateType(clazz);
        String clazzName = clazz.getName();
        if (logTracer.ready(LOG, Level.DEBUG)) LOG.debug(logTracer
                .put("className", clazzName)
                .text("getInvocationHandler() get InvocationHandler by type")
                .stringify());
        if (!handlers.containsKey(clazzName)) {
            if (logTracer.ready(LOG, Level.DEBUG)) LOG.debug(logTracer
                    .put("className", clazzName)
                    .text("getInvocationHandler() InvocationHandler not found, create new one")
                    .stringify());
            handlers.put(clazzName, new RpcInvocationHandler(logTracer, measurer, restrictor, reqExtractor, rpcWatcher, 
                    rpcMaster, publisher, clazz, bean, reservedWorkerEnabled));
        } else {
            if (strictMode) {
                throw new OpflowRpcRegistrationException("Class [" + clazzName + "] has already registered");
            }
        }
        return handlers.get(clazzName);
    }

    private void removeInvocationHandler(Class clazz) {
        if (clazz == null) return;
        String clazzName = clazz.getName();
        handlers.remove(clazzName);
    }

    private boolean validateType(Class type) {
        boolean ok = true;
        if (OpflowUtil.isGenericDeclaration(type.toGenericString())) {
            ok = false;
            if (logTracer.ready(LOG, Level.DEBUG)) LOG.debug(logTracer
                    .put("typeString", type.toGenericString())
                    .text("generic types are unsupported")
                    .stringify());
        }
        Method[] methods = type.getDeclaredMethods();
        for(Method method:methods) {
            if (OpflowUtil.isGenericDeclaration(method.toGenericString())) {
                ok = false;
                if (logTracer.ready(LOG, Level.DEBUG)) LOG.debug(logTracer
                        .put("methodString", method.toGenericString())
                        .text("generic methods are unsupported")
                        .stringify());
            }
        }
        if (!ok) {
            throw new OpflowInterceptionException("Generic type/method is unsupported");
        }
        return ok;
    }

    public <T> T registerType(Class<T> type) {
        return registerType(type, null);
    }

    public <T> T registerType(Class<T> type, T bean) {
        if (type == null) {
            throw new OpflowInterceptionException("The [type] parameter must not be null");
        }
        if (OpflowRpcChecker.class.equals(type)) {
            throw new OpflowInterceptionException("Can not register the OpflowRpcChecker type");
        }
        try {
            if (logTracer.ready(LOG, Level.DEBUG)) LOG.debug(logTracer
                    .put("className", type.getName())
                    .put("classLoaderName", type.getClassLoader().getClass().getName())
                    .text("registerType() calls newProxyInstance()")
                    .stringify());
            T t = (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[] {type}, getInvocationHandler(type, bean));
            if (logTracer.ready(LOG, Level.DEBUG)) LOG.debug(logTracer
                    .put("className", type.getName())
                    .text("newProxyInstance() has completed")
                    .stringify());
            return t;
        } catch (IllegalArgumentException exception) {
            if (logTracer.ready(LOG, Level.ERROR)) LOG.error(logTracer
                    .put("exceptionClass", exception.getClass().getName())
                    .put("exceptionMessage", exception.getMessage())
                    .text("newProxyInstance() has failed")
                    .stringify());
            throw new OpflowInterceptionException(exception);
        }
    }

    public <T> void unregisterType(Class<T> type) {
        removeInvocationHandler(type);
    }

    public Map<String, Object> getRpcInvocationCounter() {
        return measurer.getRpcInvocationCounter("commander").toMap(false);
    }

    public void resetRpcInvocationCounter() {
        measurer.resetRpcInvocationCounter();
    }

    @Override
    protected void finalize() throws Throwable {
        measurer.updateComponentInstance("commander", instanceId, OpflowPromMeasurer.GaugeAction.DEC);
    }
}
