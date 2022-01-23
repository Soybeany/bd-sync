package com.soybeany.sync.core.api;

import com.soybeany.sync.core.model.SyncClientInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Soybeany
 * @date 2021/10/27
 */
public interface IClientPlugin<Input, Output> extends IBasePlugin<Input, Output> {

    Logger LOGGER = LoggerFactory.getLogger(IClientPlugin.class);

    /**
     * 应用启动时的回调
     *
     * @param info 同步客户端的信息
     */
    default void onStartup(SyncClientInfo info) {
    }

    /**
     * 应用关闭时的回调
     */
    default void onShutdown() {
    }

    /**
     * 同步前的回调(需使用synchronized)
     *
     * @param uid    用于关联输入/输出的唯一标识
     * @param output 待传输至服务器的数据
     * @return 是否继续同步
     */
    default boolean onBeforeSync(String uid, Output output) throws Exception {
        return true;
    }

    /**
     * 同步结束的回调(必须先配置{@link #onSetupSyncTagToHandle}, 需使用synchronized)
     *
     * @param uid   用于关联输入/输出的唯一标识
     * @param input 入参
     */
    default void onAfterSync(String uid, Input input) throws Exception {
    }

    /**
     * 同步过程中出现异常时的回调(需使用synchronized)
     *
     * @param uid 用于关联输入/输出的唯一标识
     * @param e   异常
     */
    default void onSyncException(String uid, Exception e) throws Exception {
        LOGGER.warn(e.getMessage());
    }

}
