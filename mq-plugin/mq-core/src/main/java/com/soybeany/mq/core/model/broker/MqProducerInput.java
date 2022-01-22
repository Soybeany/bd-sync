package com.soybeany.mq.core.model.broker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author Soybeany
 * @date 2022/1/19
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MqProducerInput {

    private boolean success;
    private String msg;

}