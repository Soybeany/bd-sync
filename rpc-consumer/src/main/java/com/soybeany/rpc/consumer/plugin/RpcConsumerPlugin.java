package com.soybeany.rpc.consumer.plugin;

import com.soybeany.cache.v2.core.DataManager;
import com.soybeany.rpc.client.model.RpcMethodInfo;
import com.soybeany.rpc.client.plugin.BaseRpcClientPlugin;
import com.soybeany.rpc.consumer.anno.BdRpcWired;
import com.soybeany.rpc.consumer.api.IRpcBatchInvoker;
import com.soybeany.rpc.consumer.api.IRpcDataManagerProvider;
import com.soybeany.rpc.consumer.api.IRpcServiceProxy;
import com.soybeany.rpc.consumer.exception.RpcPluginNoFallbackException;
import com.soybeany.rpc.consumer.exception.RpcRequestException;
import com.soybeany.rpc.consumer.model.RpcBatchResult;
import com.soybeany.rpc.consumer.model.RpcProxySelector;
import com.soybeany.rpc.core.anno.BdRpc;
import com.soybeany.rpc.core.anno.BdRpcBatch;
import com.soybeany.rpc.core.anno.BdRpcCache;
import com.soybeany.rpc.core.anno.BdRpcSerialize;
import com.soybeany.rpc.core.api.IServerInfoReceiver;
import com.soybeany.rpc.core.exception.RpcPluginException;
import com.soybeany.rpc.core.model.RpcConsumerInput;
import com.soybeany.rpc.core.model.RpcConsumerOutput;
import com.soybeany.rpc.core.model.RpcServerInfo;
import com.soybeany.sync.client.api.IClientPlugin;
import com.soybeany.sync.client.model.SyncClientInfo;
import com.soybeany.sync.client.picker.DataPicker;
import com.soybeany.sync.client.util.RequestUtils;
import com.soybeany.sync.core.exception.SyncRequestException;
import com.soybeany.sync.core.model.SerializeType;
import com.soybeany.sync.core.model.SyncDTO;
import com.soybeany.util.ExceptionUtils;
import com.soybeany.util.Md5Utils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import static com.soybeany.rpc.core.model.BdRpcConstants.*;
import static com.soybeany.sync.core.util.NetUtils.GSON;

/**
 * // todo ???????????????????????????????????????????????????(??????alertId)???????????????????????????alertId???????????????????????????????????????????????????????????????????????????????????????????????????????????????
 *
 * @author Soybeany
 * @date 2021/10/27
 */
@RequiredArgsConstructor
public class RpcConsumerPlugin extends BaseRpcClientPlugin<RpcConsumerInput, RpcConsumerOutput> implements IRpcServiceProxy {

    private static final String RESOURCE_PATTERN = "/**/*.class";
    private static final String GROUP_SEPARATOR = ":";
    private static Map<String, Map<Class<?>, List<InjectInfo>>> FIELDS_TO_INJECT;

    /**
     * ???????????????????????????/?????????
     */
    private final Map<Class<?>, Object> proxies = new HashMap<>();

    /**
     * ?????????????????????????????????
     */
    private final Map<String, DataPicker<RpcServerInfo>> pickers = new HashMap<>();
    private final Map<Method, DataManager<InvokeInfo, Object>> dataManagerMap = new HashMap<>();
    private final Map<Class<?>, Map<String, InfoPart2>> infoPart2Map = new HashMap<>();
    private final Map<Method, RpcMethodInfo.TypeInfo> typeInfoMap = new HashMap<>();
    private final Set<String> serviceIdSet = new HashSet<>();

    private final String syncerId;
    private final Function<String, DataPicker<RpcServerInfo>> dataPickerProvider;
    private final Function<String, Integer> invokeTimeoutSecProvider;
    private final IRpcDataManagerProvider dataManagerProvider;
    private final Set<String> pkgToScan;
    private final boolean enableRpcWired;

    private SyncClientInfo info;
    private ExecutorService batchExecutor;
    private String md5;

    @Override
    public String onSetupSyncTagToHandle() {
        return TAG_C;
    }

    @Override
    public Class<RpcConsumerInput> onGetInputClass() {
        return RpcConsumerInput.class;
    }

    @Override
    public Class<RpcConsumerOutput> onGetOutputClass() {
        return RpcConsumerOutput.class;
    }

    @SuppressWarnings("AlibabaThreadPoolCreation")
    @Override
    public void onStartup(SyncClientInfo info) {
        super.onStartup(info);
        this.info = info;
        //spring???????????????????????????????????????????????????
        ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();
        try {
            Set<Resource> resources = new HashSet<>();
            for (String path : getPostTreatPkgPathsToScan()) {
                String pattern = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + ClassUtils.convertClassNameToResourcePath(path) + RESOURCE_PATTERN;
                Resource[] partResources = resourcePatternResolver.getResources(pattern);
                Collections.addAll(resources, partResources);
            }
            //MetadataReader ????????????
            MetadataReaderFactory readerFactory = new CachingMetadataReaderFactory(resourcePatternResolver);
            for (Resource resource : resources) {
                //?????????????????????
                MetadataReader reader = readerFactory.getMetadataReader(resource);
                //????????????class
                String classname = reader.getClassMetadata().getClassName();
                Class<?> clazz = Class.forName(classname);
                //?????????????????????
                Optional.ofNullable(clazz.getAnnotation(BdRpc.class))
                        .filter(bdRpc -> clazz.isInterface())
                        .ifPresent(bdRpc -> setupServiceImpl(clazz, getId(info.getVersion(), clazz, bdRpc), bdRpc.timeoutInSec()));
            }
        } catch (Exception e) {
            throw new RpcPluginException("???????????????????????????:" + e.getMessage());
        }
        // ?????????????????????
        if (!infoPart2Map.isEmpty()) {
            batchExecutor = Executors.newCachedThreadPool();
        }
    }

    @Override
    public void onApplicationStarted() {
        super.onApplicationStarted();
        if (!enableRpcWired) {
            return;
        }
        // ????????????????????????
        initFieldsToInject();
        // ????????????
        Map<Class<?>, List<InjectInfo>> map = FIELDS_TO_INJECT.remove(syncerId);
        if (null == map) {
            return;
        }
        map.forEach((clazz, injectInfoList) -> {
            Object value = get(clazz);
            try {
                for (InjectInfo info : injectInfoList) {
                    info.field.setAccessible(true);
                    info.field.set(info.obj, value);
                }
            } catch (Exception e) {
                throw new RpcPluginException("????????????????????????:" + ExceptionUtils.getExceptionDetail(e));
            }
        });
    }

    @Override
    public void onAfterStartup(List<IClientPlugin<Object, Object>> appliedPlugins) {
        super.onAfterStartup(appliedPlugins);
    }

    @Override
    public void onShutdown() {
        if (null != batchExecutor) {
            batchExecutor.shutdown();
        }
        super.onShutdown();
    }

    @Override
    public synchronized boolean onBeforeSync(String uid, RpcConsumerOutput output) throws Exception {
        output.setSystem(info.getSystem());
        output.setServiceIds(serviceIdSet);
        output.setMd5(md5);
        return super.onBeforeSync(uid, output);
    }

    @Override
    public synchronized void onAfterSync(String uid, RpcConsumerInput input) throws Exception {
        super.onAfterSync(uid, input);
        if (!input.isUpdated()) {
            return;
        }
        Set<String> keys = new HashSet<>(pickers.keySet());
        Optional.ofNullable(input.getProviderMap()).ifPresent(map -> {
            // ??????????????????????????????????????????
            Map<String, Set<RpcServerInfo>> tmpMap = new HashMap<>();
            map.forEach((id, v) -> v.forEach(info -> {
                String newId = getKeyWithGroup(info.getGroup(), id);
                if (!newId.equals(id)) {
                    tmpMap.computeIfAbsent(newId, s -> new HashSet<>()).add(info);
                }
            }));
            map.putAll(tmpMap);
            // ????????????
            map.forEach((id, v) -> {
                keys.remove(id);
                DataPicker<RpcServerInfo> picker = pickers.computeIfAbsent(id, dataPickerProvider);
                picker.set(new ArrayList<>(v));
            });
        });
        // ?????????????????????
        keys.forEach(pickers::remove);
        // ????????????????????????????????????md5
        md5 = input.getMd5();
    }

    @SuppressWarnings("unchecked")
    @Override
    public synchronized <T> T get(Class<T> interfaceClass) {
        // ?????????????????????
        T instance = (T) proxies.get(interfaceClass);
        if (null != instance) {
            return instance;
        }
        // ???spring????????????
        T impl = getBeanFromContext(interfaceClass);
        if (null != impl) {
            return impl;
        }
        // ?????????????????????
        throw getNotFoundClassImplException(interfaceClass);
    }

    @Override
    public <T> IRpcBatchInvoker<T> getBatch(Class<?> interfaceClass, String methodId) {
        // ????????????
        Map<String, InfoPart2> map = infoPart2Map.get(interfaceClass);
        if (null == map) {
            throw getNotFoundClassImplException(interfaceClass);
        }
        InfoPart2 infoPart = map.get(methodId);
        if (null == infoPart) {
            throw new RpcPluginException("??????????????????methodId(" + methodId + ")?????????");
        }
        return args -> {
            // ????????????
            Map<RpcServerInfo, Future<T>> futureMap = new HashMap<>();
            InvokeInfo info = infoPart.toNewInfo(args);
            DataPicker<RpcServerInfo> picker = getGroupedPicker(info);
            for (RpcServerInfo serverInfo : picker.getAllUsable()) {
                Future<T> future = batchExecutor.submit(() -> onInvoke(new PickerWrapper(picker, serverInfo), info));
                futureMap.put(serverInfo, future);
            }
            // ????????????
            Map<RpcServerInfo, RpcBatchResult<T>> result = new HashMap<>();
            futureMap.forEach((serverInfo, future) -> {
                try {
                    result.put(serverInfo, RpcBatchResult.norm(future.get()));
                } catch (InterruptedException e) {
                    result.put(serverInfo, RpcBatchResult.error(e));
                } catch (ExecutionException e) {
                    result.put(serverInfo, RpcBatchResult.error(e.getCause()));
                }
            });
            return result;
        };
    }

    @Override
    protected Set<String> onSetupPkgPathToScan() {
        return pkgToScan;
    }

    // ***********************????????????****************************

    @SuppressWarnings("AlibabaAvoidManuallyCreateThread")
    private void initFieldsToInject() {
        synchronized (RpcConsumerPlugin.class) {
            if (null != FIELDS_TO_INJECT) {
                return;
            }
            FIELDS_TO_INJECT = new HashMap<>();
        }
        ApplicationContext appContext = info.getAppContext();
        String[] names = appContext.getBeanDefinitionNames();
        Arrays.stream(names).map(appContext::getBean).forEach(bean -> {
            if (bean == this) {
                return;
            }
            ReflectionUtils.doWithFields(bean.getClass(), field -> {
                BdRpcWired reference = field.getAnnotation(BdRpcWired.class);
                if (null == reference) {
                    return;
                }
                FIELDS_TO_INJECT.computeIfAbsent(reference.syncerId(), id -> new HashMap<>())
                        .computeIfAbsent(field.getType(), clazz -> new ArrayList<>())
                        .add(new InjectInfo(bean, field));
            });
        });
    }

    @SuppressWarnings("unchecked")
    private <T> T invoke(InvokeInfo invokeInfo) throws Throwable {
        DataManager<InvokeInfo, Object> manager = dataManagerMap.get(invokeInfo.method);
        // ??????????????????manager????????????????????????????????????????????????
        if (null == manager) {
            return onInvoke(invokeInfo);
        }
        // ?????????????????????
        return (T) manager.getData(invokeInfo);
    }

    private DataManager<InvokeInfo, Object> getNewDataManager(Method method, BdRpcCache cache) {
        boolean hasStorageId = StringUtils.hasLength(cache.storageId());
        String desc = getDesc(method, cache);
        String storageId = hasStorageId ? cache.storageId() : desc;
        return dataManagerProvider.onGetNew(method, cache, this::onInvoke, invokeInfo -> {
            String keyWithGroup = getKeyWithGroup(invokeInfo.group, GSON.toJson(invokeInfo.args));
            return cache.useMd5Key() ? Md5Utils.strToMd5(keyWithGroup) : keyWithGroup;
        }, desc, storageId);
    }

    private String getDesc(Method method, BdRpcCache cache) {
        String desc = cache.desc();
        if (!StringUtils.hasText(desc)) {
            desc = method.getDeclaringClass().getSimpleName() + "-" + method.getName();
        }
        return desc;
    }

    private DataPicker<RpcServerInfo> getGroupedPicker(InvokeInfo invokeInfo) {
        String serviceIdWithGroup = getKeyWithGroup(invokeInfo.group, invokeInfo.serviceId);
        return Optional.ofNullable(pickers.get(serviceIdWithGroup)).orElseThrow(() -> new RpcPluginException("??????serviceId(" + serviceIdWithGroup + ")????????????????????????"));
    }

    private <T> T onInvoke(InvokeInfo invokeInfo) throws Exception {
        try {
            return onInvoke(getGroupedPicker(invokeInfo), invokeInfo);
        } catch (Exception e) {
            // ??????????????????????????????????????????????????????
            if (e instanceof RuntimeException) {
                throw e;
            }
            // ???????????????????????????????????????????????????????????????????????????
            for (Class<?> exceptionType : invokeInfo.method.getExceptionTypes()) {
                if (exceptionType.isInstance(e)) {
                    throw e;
                }
            }
            throw new RuntimeException(e);
        }
    }

    private <T> T onInvoke(DataPicker<RpcServerInfo> picker, InvokeInfo invokeInfo) throws Exception {
        if (null == picker) {
            return invokeMethodOfFallbackImpl(invokeInfo.method, invokeInfo.args, invokeInfo.fallbackImpl, "??????serviceId(" + invokeInfo.serviceId + ")????????????????????????");
        }
        RequestUtils.Config config = new RequestUtils.Config();
        config.getParams().put(KEY_METHOD_INFO, GSON.toJson(new RpcMethodInfo(
                invokeInfo.serviceId, invokeInfo.method, typeInfoMap.get(invokeInfo.method), invokeInfo.args
        )));
        config.setTimeoutSec(invokeInfo.timeoutInSec);
        RequestUtils.Result<RpcServerInfo, SyncDTO> result;
        try {
            result = RequestUtils.request(picker, serverInfo -> {
                config.getHeaders().put(HEADER_AUTHORIZATION, serverInfo.getAuthorization());
                return serverInfo.getInvokeUrl();
            }, config, SyncDTO.class, "??????serviceId(" + invokeInfo.serviceId + ")????????????????????????");
        } catch (SyncRequestException e) {
            return invokeMethodOfFallbackImpl(invokeInfo.method, invokeInfo.args, invokeInfo.fallbackImpl, e.getMessage());
        }
        SyncDTO dto = result.getData();
        if (null == dto) {
            throw new SyncRequestException("??????????????????rpc??????(" + result.getUrl().getInvokeUrl() + ")??????????????????");
        }
        // ???????????????????????????
        if (dto.getIsNorm()) {
            return dto.toData(invokeInfo.method.getGenericReturnType());
        }
        // ?????????????????????????????????
        Exception exception = dto.parseErr();
        if (exception instanceof SyncRequestException) {
            exception = new RpcRequestException(result.getUrl(), (SyncRequestException) exception);
        }
        if (exception instanceof IServerInfoReceiver) {
            ((IServerInfoReceiver) exception).onSetupServerInfo(result.getUrl());
        }
        throw exception;
    }

    @SuppressWarnings("unchecked")
    private <T> T invokeMethodOfFallbackImpl(Method method, Object[] args, Object fallbackImpl, String errMsg) throws Exception {
        if (null == fallbackImpl) {
            throw new RpcPluginNoFallbackException(errMsg);
        } else {
            return (T) method.invoke(fallbackImpl, args);
        }
    }

    private void setupServiceImpl(Class<?> interfaceClass, String serviceId, int referTimeoutInSec) {
        // ??????serviceId
        boolean success = serviceIdSet.add(serviceId);
        if (!success) {
            throw new RpcPluginException("@BdRpc???serviceId(" + serviceId + ")?????????");
        }
        Object fallbackImpl = null;
        // ????????????????????????
        Object impl = getBeanFromContext(interfaceClass);
        if (null != impl && isFallbackImpl(impl)) {
            fallbackImpl = impl;
        }
        // ??????????????????
        int timeoutInSec = (referTimeoutInSec >= 0 ? referTimeoutInSec : invokeTimeoutSecProvider.apply(serviceId));
        InfoPart1 infoPart = new InfoPart1(fallbackImpl, serviceId, timeoutInSec);
        // ?????????????????????
        for (Method method : interfaceClass.getMethods()) {
            // ??????????????????
            handleCacheAnnotation(method);
            // ??????????????????
            handleBatchAnnotation(interfaceClass, method, infoPart);
            // ?????????????????????
            handleSerializeAnnotation(method);
        }
        // ????????????
        Object instance = Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class[]{interfaceClass},
                (proxy, method, args) -> invoke(infoPart.toNewInfo(method, args))
        );
        proxies.put(interfaceClass, instance);
    }

    private void handleCacheAnnotation(Method method) {
        BdRpcCache cache = method.getAnnotation(BdRpcCache.class);
        if (null == cache) {
            return;
        }
        dataManagerMap.put(method, getNewDataManager(method, cache));
    }

    private void handleBatchAnnotation(Class<?> interfaceClass, Method method, InfoPart1 part1) {
        BdRpcBatch batch = method.getAnnotation(BdRpcBatch.class);
        if (null == batch) {
            return;
        }
        InfoPart2 previous = infoPart2Map.computeIfAbsent(interfaceClass, clazz -> new HashMap<>())
                .put(batch.methodId(), new InfoPart2(method, part1));
        if (null != previous) {
            throw new RpcPluginException("??????????????????@BdRpcBatch???methodId(" + batch.methodId() + ")?????????");
        }
    }

    private void handleSerializeAnnotation(Method method) {
        SerializeType returnType = null;
        BdRpcSerialize serialize = method.getAnnotation(BdRpcSerialize.class);
        if (null != serialize) {
            returnType = serialize.value();
        }
        boolean hasParamType = false;
        Annotation[][] paramAnnotations = method.getParameterAnnotations();
        SerializeType[] paramTypes = new SerializeType[paramAnnotations.length];
        for (int i = 0; i < paramAnnotations.length; i++) {
            for (Annotation annotation : paramAnnotations[i]) {
                if (annotation instanceof BdRpcSerialize) {
                    paramTypes[i] = ((BdRpcSerialize) annotation).value();
                    hasParamType = true;
                    break;
                }
            }
        }
        if (null == returnType && !hasParamType) {
            return;
        }
        typeInfoMap.put(method, new RpcMethodInfo.TypeInfo(returnType, hasParamType ? paramTypes : null));
    }

    @SuppressWarnings("unchecked")
    private <T> T getBeanFromContext(Class<?> interfaceClass) {
        try {
            return (T) info.getAppContext().getBean(interfaceClass);
        } catch (NoSuchBeanDefinitionException ignore) {
            return null;
        }
    }

    private String getKeyWithGroup(String group, String key) {
        return StringUtils.hasText(group) ? (group + GROUP_SEPARATOR + key) : key;
    }

    private RpcPluginException getNotFoundClassImplException(Class<?> interfaceClass) {
        return new RpcPluginException("?????????????????????(" + interfaceClass + ")?????????");
    }

    // ***********************?????????****************************

    @RequiredArgsConstructor
    private static class InjectInfo {
        final Object obj;
        final Field field;
    }

    @RequiredArgsConstructor
    private static class InfoPart1 {
        final Object fallbackImpl;
        final String serviceId;
        final int timeoutInSec;

        InvokeInfo toNewInfo(Method method, Object[] args) {
            return new InvokeInfo(method, args, fallbackImpl, serviceId, timeoutInSec);
        }
    }

    private static class InfoPart2 {
        final Method method;
        final Object fallbackImpl;
        final String serviceId;
        final int timeoutInSec;

        InfoPart2(Method method, InfoPart1 part1) {
            this.method = method;
            this.fallbackImpl = part1.fallbackImpl;
            this.serviceId = part1.serviceId;
            this.timeoutInSec = part1.timeoutInSec;
        }

        InvokeInfo toNewInfo(Object[] args) {
            return new InvokeInfo(method, args, fallbackImpl, serviceId, timeoutInSec);
        }
    }

    @RequiredArgsConstructor
    private static class InvokeInfo {
        final Method method;
        final Object[] args;
        final Object fallbackImpl;
        final String serviceId;
        final int timeoutInSec;
        String group;

        {
            group = RpcProxySelector.getAndRemoveGroup();
        }

    }

    @RequiredArgsConstructor
    private static class PickerWrapper implements DataPicker<RpcServerInfo> {

        private final DataPicker<RpcServerInfo> target;
        private final RpcServerInfo info;

        @Override
        public void set(List<RpcServerInfo> list) {
            throw new RpcPluginException("?????????set??????");
        }

        @Override
        public RpcServerInfo getNext() {
            return info;
        }

        @Override
        public List<RpcServerInfo> getAllUsable() {
            return Collections.singletonList(info);
        }

        @Override
        public void onUnusable(RpcServerInfo data) {
            DataPicker.super.onUnusable(data);
            target.onUnusable(data);
        }
    }

}
