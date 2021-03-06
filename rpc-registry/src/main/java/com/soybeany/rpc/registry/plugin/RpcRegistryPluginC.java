package com.soybeany.rpc.registry.plugin;

import com.soybeany.rpc.core.model.BdRpcConstants;
import com.soybeany.rpc.core.model.RpcConsumerInput;
import com.soybeany.rpc.core.model.RpcConsumerOutput;
import com.soybeany.rpc.core.model.RpcServerInfo;
import com.soybeany.rpc.registry.api.IRpcStorageManager;
import com.soybeany.sync.server.api.IServerPlugin;
import com.soybeany.util.Md5Utils;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.soybeany.sync.core.util.NetUtils.GSON;

/**
 * 客户端管理插件(C端)
 *
 * @author Soybeany
 * @date 2022/1/17
 */
@RequiredArgsConstructor
public class RpcRegistryPluginC implements IServerPlugin<RpcConsumerOutput, RpcConsumerInput> {

    private final IRpcStorageManager storageManager;

    @Override
    public String onSetupSyncTagToHandle() {
        return BdRpcConstants.TAG_C;
    }

    @Override
    public Class<RpcConsumerOutput> onGetInputClass() {
        return RpcConsumerOutput.class;
    }

    @Override
    public Class<RpcConsumerInput> onGetOutputClass() {
        return RpcConsumerInput.class;
    }

    @Override
    public void onHandleSync(String clientIp, RpcConsumerOutput in, RpcConsumerInput out) {
        Map<String, Set<RpcServerInfo>> map = new LinkedHashMap<>();
        String system = in.getSystem();
        in.getServiceIds().forEach(serviceId -> map.put(serviceId, storageManager.load(system, serviceId)));
        // 当md5不一致时，再返回数据
        String md5 = Md5Utils.strToMd5(GSON.toJson(map));
        if (!md5.equals(in.getMd5())) {
            out.setUpdated(true);
            out.setMd5(md5);
            out.setProviderMap(map);
        }
    }
}
