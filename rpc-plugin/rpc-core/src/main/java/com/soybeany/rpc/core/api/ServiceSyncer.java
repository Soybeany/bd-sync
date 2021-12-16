package com.soybeany.rpc.core.api;

import com.soybeany.sync.core.model.SyncDTO;

import javax.servlet.http.HttpServletRequest;

/**
 * @author Soybeany
 * @date 2021/12/16
 */
public interface ServiceSyncer {

    SyncDTO sync(HttpServletRequest request);

}
