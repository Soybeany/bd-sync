package com.soybeany.rpc.plugin;

import com.soybeany.rpc.model.BdRpc;
import com.soybeany.rpc.model.MethodInfo;
import com.soybeany.rpc.model.ProviderParam;
import com.soybeany.rpc.model.ServerInfo;
import com.soybeany.rpc.utl.ReflectUtils;
import com.soybeany.sync.core.model.Context;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import javax.annotation.PostConstruct;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.soybeany.sync.core.util.RequestUtils.GSON;

/**
 * @author Soybeany
 * @date 2021/10/27
 */
public abstract class BaseRpcProviderPlugin extends BaseRpcClientPlugin {

    @Autowired
    private ApplicationContext appContext;

    private final Map<String, Object> serviceMap = new HashMap<>();

    @Override
    public void onSendSync(Context ctx, Map<String, String> result) {
        ServerInfo info = new ServerInfo();
        info.setAddress("localhost");
        info.setPort(8081);
        info.setContext("");
        info.setAuthorization("123456");
        ProviderParam param = new ProviderParam();
        param.setInfo(info);
        param.setServiceIds(serviceMap.keySet());
        result.put(Constants.KEY_ACTION, Constants.ACTION_REGISTER_PROVIDERS);
        result.put(Constants.KEY_PROVIDER_MAP, GSON.toJson(param));
    }

    @Override
    public void onHandleSync(Context ctx, Map<String, String> param) {

    }

    @Override
    public String onSetupSyncTagToHandle() {
        return Constants.TAG;
    }

    @PostConstruct
    private void onInit() {
        for (String name : appContext.getBeanDefinitionNames()) {
            Object bean = appContext.getBean(name);
            Optional.ofNullable(ReflectUtils.getAnnotation(onSetupScanPkg(), BdRpc.class, bean.getClass()))
                    .ifPresent(bdRpc -> onHandleBean(bdRpc, bean));
        }
    }

    public Object invoke(MethodInfo info) throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Object obj = serviceMap.get(info.getServiceId());
        Method method = obj.getClass().getMethod(info.getMethodName(), toClass(info.getClazzNames()));
        return method.invoke(obj, info.getArgs());
    }

    // ***********************内部方法****************************

    private void onHandleBean(BdRpc bdRpc, Object bean) {
        // todo 去重
        serviceMap.put(getId(bdRpc), bean);
    }

    private Class<?>[] toClass(String[] classNames) throws ClassNotFoundException {
        Class<?>[] classes = new Class[classNames.length];
        for (int i = 0; i < classNames.length; i++) {
            classes[i] = Class.forName(classNames[i]);
        }
        return classes;
    }

}