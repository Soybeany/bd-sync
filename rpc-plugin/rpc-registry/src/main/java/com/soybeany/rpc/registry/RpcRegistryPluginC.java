package com.soybeany.rpc.registry;

import com.soybeany.rpc.core.model.BdRpcConstants;
import com.soybeany.rpc.core.model.RpcConsumerInput;
import com.soybeany.rpc.core.model.RpcConsumerOutput;
import com.soybeany.rpc.core.model.ServerInfo;

import java.util.Map;
import java.util.Set;

/**
 * 客户端管理插件(C端)
 *
 * @author Soybeany
 * @date 2022/1/17
 */
class RpcRegistryPluginC extends RpcRegistryPlugin<RpcConsumerOutput, RpcConsumerInput> {

    protected RpcRegistryPluginC(Map<String, IServiceManager> serviceManagerMap) {
        super(serviceManagerMap);
    }

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
    protected void onHandleSync(IServiceManager manager, RpcConsumerOutput in, RpcConsumerInput out) {
        Map<String, Set<ServerInfo>> map = out.getProviderMap();
        for (String id : in.getServiceIds()) {
            map.put(id, manager.load(id));
        }
    }
}