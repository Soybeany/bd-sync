package com.soybeany.demo;

import com.soybeany.rpc.consumer.BaseRpcServiceProxyImpl;
import com.soybeany.rpc.core.model.ServerInfo;
import com.soybeany.sync.core.picker.DataPicker;
import com.soybeany.sync.core.picker.DataPickerSimpleImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

/**
 * @author Soybeany
 * @date 2021/10/28
 */
@Slf4j
@Component
public class RpcServiceProxyImpl extends BaseRpcServiceProxyImpl {

    @Override
    protected DataPicker<ServerInfo> onGetNewServerPicker(String serviceId) {
        return new DataPickerSimpleImpl<>();
    }

    @Override
    public String[] onSetupPkgPathToScan() {
        return new String[]{"com.soybeany.demo"};
    }

    @Override
    public DataPicker<String> onGetSyncServerPicker() {
        return new DataPickerSimpleImpl<>("http://localhost:8080/bd-api/rpcSync");
    }

    @Override
    public int onSetupSyncIntervalInSec() {
        return 3;
    }

    @PostConstruct
    private void onInit() {
        start();
    }

    @PreDestroy
    private void onDestroy() {
        stop();
    }

}