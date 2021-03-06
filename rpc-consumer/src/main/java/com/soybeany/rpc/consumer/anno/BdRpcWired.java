package com.soybeany.rpc.consumer.anno;

import com.soybeany.rpc.consumer.plugin.RpcConsumerPlugin;
import com.soybeany.sync.client.impl.BaseClientSyncerImpl;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 只有{@link RpcConsumerPlugin}启用了enableRpcWired后，才支持此注解
 *
 * @author Soybeany
 * @date 2022/3/10
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BdRpcWired {

    /**
     * 指定需要注入的bean位于哪个syncer
     */
    String syncerId() default BaseClientSyncerImpl.DEFAULT_SYNCER_ID;

}
