package com.soybeany.rpc.consumer.model;

import lombok.AllArgsConstructor;

/**
 * @author Soybeany
 * @date 2022/1/18
 */
@AllArgsConstructor
public class RpcProxySelector<T> {

    private static final ThreadLocal<String> GROUP_HOLDER = new ThreadLocal<>();
    private final T target;

    /**
     * 设置使用的分组
     */
    public static void setGroup(String group) {
        GROUP_HOLDER.set(group);
    }

    /**
     * 获取并移除分组（框架内部用，外部一般不使用）
     */
    public static String getAndRemoveGroup() {
        String group = GROUP_HOLDER.get();
        GROUP_HOLDER.remove();
        return group;
    }

    /**
     * 基于{@link ThreadLocal}的实现，若涉及多线程操作，则需要在目标线程再执行此调用，否则会丢失分组信息
     * <br/>group值对应生产者里group的配置
     * <br/>若group传入null，则为全部分组
     */
    public T get(String group) {
        setGroup(group);
        return target;
    }

}
