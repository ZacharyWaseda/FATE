/*
 * Copyright 2019 The FATE Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.webank.ai.fate.eggroll.roll.api.grpc.client;

import com.webank.ai.fate.api.core.BasicMeta;
import com.webank.ai.fate.api.eggroll.storage.KVServiceGrpc;
import com.webank.ai.fate.api.eggroll.storage.Kv;
import com.webank.ai.fate.core.api.grpc.client.GrpcAsyncClientContext;
import com.webank.ai.fate.core.api.grpc.client.GrpcStreamingClientTemplate;
import com.webank.ai.fate.core.constant.MetaConstants;
import com.webank.ai.fate.core.constant.RuntimeConstants;
import com.webank.ai.fate.core.io.StoreInfo;
import com.webank.ai.fate.core.model.DelayedResult;
import com.webank.ai.fate.core.model.impl.SingleDelayedResult;
import com.webank.ai.fate.core.utils.ErrorUtils;
import com.webank.ai.fate.core.utils.TypeConversionUtils;
import com.webank.ai.fate.eggroll.meta.service.dao.generated.model.Node;
import com.webank.ai.fate.eggroll.roll.api.grpc.observer.kv.storage.*;
import com.webank.ai.fate.eggroll.roll.api.grpc.processor.caller.StorageKvPutAllRequestStreamProcessor;
import com.webank.ai.fate.eggroll.roll.factory.RollKvCallModelFactory;
import com.webank.ai.fate.eggroll.roll.factory.RollModelFactory;
import com.webank.ai.fate.eggroll.roll.service.model.OperandBroker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;

@Component
@Scope("prototype")
public class StorageServiceClient {
    private static final Logger LOGGER = LogManager.getLogger();
    @Autowired
    private RollKvCallModelFactory rollKvCallModelFactory;
    @Autowired
    private RollModelFactory rollModelFactory;
    @Autowired
    private ErrorUtils errorUtils;
    @Autowired
    private TypeConversionUtils typeConversionUtils;

    private BasicMeta.Endpoint storageServiceEndpoint = BasicMeta.Endpoint.newBuilder().setHostname("localhost").setPort(7778).build();

    public void put(Kv.Operand operand, StoreInfo storeInfo, Node node) {
        GrpcAsyncClientContext<KVServiceGrpc.KVServiceStub, Kv.Operand, Kv.Empty> context
                = rollKvCallModelFactory.createOperandToEmptyContext();

        context.setLatchInitCount(1)
                .setEndpoint(typeConversionUtils.toEndpoint(node))
                .setFinishTimeout(RuntimeConstants.DEFAULT_WAIT_TIME, RuntimeConstants.DEFAULT_TIMEUNIT)
                .setCalleeStreamingMethodInvoker(KVServiceGrpc.KVServiceStub::put)
                .setCallerStreamObserverClassAndArguments(StorageKvPutResponseObserver.class)
                .setGrpcMetadata(MetaConstants.createMetadataFromStoreInfo(storeInfo));

        GrpcStreamingClientTemplate<KVServiceGrpc.KVServiceStub, Kv.Operand, Kv.Empty> template
                = rollKvCallModelFactory.createOperandToEmptyTemplate();
        template.setGrpcAsyncClientContext(context);

        template.calleeStreamingRpc(operand);
    }

    public Kv.Operand putIfAbsent(Kv.Operand request, StoreInfo storeInfo, Node node) {
        DelayedResult<Kv.Operand> delayedResult = new SingleDelayedResult<>();

        GrpcAsyncClientContext<KVServiceGrpc.KVServiceStub, Kv.Operand, Kv.Operand> context
                = rollKvCallModelFactory.createOperandToOperandContext();

        context.setLatchInitCount(1)
                .setEndpoint(typeConversionUtils.toEndpoint(node))
                .setFinishTimeout(RuntimeConstants.DEFAULT_WAIT_TIME, RuntimeConstants.DEFAULT_TIMEUNIT)
                .setCalleeStreamingMethodInvoker(KVServiceGrpc.KVServiceStub::putIfAbsent)
                .setCallerStreamObserverClassAndArguments(StorageKvOperandToOperandObserver.class, delayedResult)
                .setGrpcMetadata(MetaConstants.createMetadataFromStoreInfo(storeInfo));

        Kv.Operand result = null;

        GrpcStreamingClientTemplate<KVServiceGrpc.KVServiceStub, Kv.Operand, Kv.Operand> template
                = rollKvCallModelFactory.createOperandToOperandTemplate();
        template.setGrpcAsyncClientContext(context);

        try {
            result = template.calleeStreamingRpcWithImmediateDelayedResult(request, delayedResult);
        } catch (InvocationTargetException e) {
            LOGGER.error(errorUtils.getStackTrace(e));
        }

        return result;
    }

    public void putAll(OperandBroker operandBroker, StoreInfo storeInfo, Node node) {
        GrpcAsyncClientContext<KVServiceGrpc.KVServiceStub, Kv.Operand, Kv.Empty> context
                = rollKvCallModelFactory.createOperandToEmptyContext();

        context.setLatchInitCount(1)
                .setEndpoint(typeConversionUtils.toEndpoint(node))
                .setFinishTimeout(RuntimeConstants.DEFAULT_WAIT_TIME, RuntimeConstants.DEFAULT_TIMEUNIT)
                .setCallerStreamingMethodInvoker(KVServiceGrpc.KVServiceStub::putAll)
                .setCallerStreamObserverClassAndArguments(StorageKvPutAllClientResponseStreamObserver.class)
                .setGrpcMetadata(MetaConstants.createMetadataFromStoreInfo(storeInfo))
                .setRequestStreamProcessorClassAndArguments(StorageKvPutAllRequestStreamProcessor.class, operandBroker, node);

        GrpcStreamingClientTemplate<KVServiceGrpc.KVServiceStub, Kv.Operand, Kv.Empty> template
                = rollKvCallModelFactory.createOperandToEmptyTemplate();
        template.setGrpcAsyncClientContext(context);

        template.initCallerStreamingRpc();

        try {
            while (!operandBroker.isClosable()) {
                operandBroker.awaitLatch(RuntimeConstants.DEFAULT_WAIT_TIME, RuntimeConstants.DEFAULT_TIMEUNIT);
                template.processCallerStreamingRpc();
            }
        } catch (Exception e) {
            template.errorCallerStreamingRpc(e);
        }

        template.completeStreamingRpc();
    }

    public Kv.Operand delete(Kv.Operand request, StoreInfo storeInfo, Node node) {
        DelayedResult<Kv.Operand> delayedResult = new SingleDelayedResult<>();

        GrpcAsyncClientContext<KVServiceGrpc.KVServiceStub, Kv.Operand, Kv.Operand> context
                = rollKvCallModelFactory.createOperandToOperandContext();

        context.setLatchInitCount(1)
                .setEndpoint(typeConversionUtils.toEndpoint(node))
                .setFinishTimeout(RuntimeConstants.DEFAULT_WAIT_TIME, RuntimeConstants.DEFAULT_TIMEUNIT)
                .setCalleeStreamingMethodInvoker(KVServiceGrpc.KVServiceStub::delete)
                .setCallerStreamObserverClassAndArguments(StorageKvOperandToOperandObserver.class, delayedResult)
                .setGrpcMetadata(MetaConstants.createMetadataFromStoreInfo(storeInfo));

        Kv.Operand result = null;

        GrpcStreamingClientTemplate<KVServiceGrpc.KVServiceStub, Kv.Operand, Kv.Operand> template
                = rollKvCallModelFactory.createOperandToOperandTemplate();
        template.setGrpcAsyncClientContext(context);

        try {
            result = template.calleeStreamingRpcWithImmediateDelayedResult(request, delayedResult);
        } catch (InvocationTargetException e) {
            LOGGER.error(errorUtils.getStackTrace(e));
        }

        return result;
    }

    public Kv.Operand get(Kv.Operand request, StoreInfo storeInfo, Node node) {
        DelayedResult<Kv.Operand> delayedResult = new SingleDelayedResult<>();

        GrpcAsyncClientContext<KVServiceGrpc.KVServiceStub, Kv.Operand, Kv.Operand> context
                = rollKvCallModelFactory.createOperandToOperandContext();

        context.setLatchInitCount(1)
                .setEndpoint(typeConversionUtils.toEndpoint(node))
                .setFinishTimeout(RuntimeConstants.DEFAULT_WAIT_TIME, RuntimeConstants.DEFAULT_TIMEUNIT)
                .setCalleeStreamingMethodInvoker(KVServiceGrpc.KVServiceStub::get)
                .setCallerStreamObserverClassAndArguments(StorageKvOperandToOperandObserver.class, delayedResult)
                .setGrpcMetadata(MetaConstants.createMetadataFromStoreInfo(storeInfo));

        Kv.Operand result = null;

        GrpcStreamingClientTemplate<KVServiceGrpc.KVServiceStub, Kv.Operand, Kv.Operand> template
                = rollKvCallModelFactory.createOperandToOperandTemplate();
        template.setGrpcAsyncClientContext(context);

        try {
            result = template.calleeStreamingRpcWithImmediateDelayedResult(request, delayedResult);
        } catch (InvocationTargetException e) {
            LOGGER.error(errorUtils.getStackTrace(e));

            throw new RuntimeException(e);
        }

        return result;
    }

    public OperandBroker iterate(Kv.Range request, StoreInfo storeInfo, Node node) {
        GrpcAsyncClientContext<KVServiceGrpc.KVServiceStub, Kv.Range, Kv.Operand> context
                = rollKvCallModelFactory.createRangeToOperandContext();

        OperandBroker result = rollModelFactory.createOperandBroker();

        context.setLatchInitCount(1)
                .setEndpoint(typeConversionUtils.toEndpoint(node))
                .setFinishTimeout(RuntimeConstants.DEFAULT_WAIT_TIME, RuntimeConstants.DEFAULT_TIMEUNIT)
                .setCalleeStreamingMethodInvoker(KVServiceGrpc.KVServiceStub::iterate)
                .setCallerStreamObserverClassAndArguments(StorageKvIterateResponseStreamObserver.class, result)
                .setGrpcMetadata(MetaConstants.createMetadataFromStoreInfo(storeInfo));

        GrpcStreamingClientTemplate<KVServiceGrpc.KVServiceStub, Kv.Range, Kv.Operand> template
                = rollKvCallModelFactory.createRangeToOperandTemplate();
        template.setGrpcAsyncClientContext(context);

        template.calleeStreamingRpc(request);

        return result;
    }

    public OperandBroker iterateStreaming(Kv.Range request, StoreInfo storeInfo, Node node) {
        GrpcAsyncClientContext<KVServiceGrpc.KVServiceStub, Kv.Range, Kv.Operand> context
                = rollKvCallModelFactory.createRangeToOperandContext();

        OperandBroker result = rollModelFactory.createOperandBroker();

        context.setLatchInitCount(1)
                .setEndpoint(typeConversionUtils.toEndpoint(node))
                .setFinishTimeout(RuntimeConstants.DEFAULT_WAIT_TIME, RuntimeConstants.DEFAULT_TIMEUNIT)
                .setCalleeStreamingMethodInvoker(KVServiceGrpc.KVServiceStub::iterate)
                .setCallerStreamObserverClassAndArguments(StorageKvIterateResponseStreamObserver.class, result)
                .setGrpcMetadata(MetaConstants.createMetadataFromStoreInfo(storeInfo));

        GrpcStreamingClientTemplate<KVServiceGrpc.KVServiceStub, Kv.Range, Kv.Operand> template
                = rollKvCallModelFactory.createRangeToOperandTemplate();
        template.setGrpcAsyncClientContext(context);

        template.calleeStreamingRpcNoWait(request);

        return result;
    }

    public void destroy(Kv.Empty request, StoreInfo storeInfo, Node node) {
        GrpcAsyncClientContext<KVServiceGrpc.KVServiceStub, Kv.Empty, Kv.Empty> context
                = rollKvCallModelFactory.createEmptyToEmptyContext();

        context.setLatchInitCount(1)
                .setEndpoint(typeConversionUtils.toEndpoint(node))
                .setFinishTimeout(RuntimeConstants.DEFAULT_WAIT_TIME, RuntimeConstants.DEFAULT_TIMEUNIT)
                .setCalleeStreamingMethodInvoker(KVServiceGrpc.KVServiceStub::destroy)
                .setCallerStreamObserverClassAndArguments(StorageKvDestroyResponseObserver.class)
                .setGrpcMetadata(MetaConstants.createMetadataFromStoreInfo(storeInfo));

        GrpcStreamingClientTemplate<KVServiceGrpc.KVServiceStub, Kv.Empty, Kv.Empty> template
                = rollKvCallModelFactory.createEmptyToEmptyTemplate();
        template.setGrpcAsyncClientContext(context);

        template.calleeStreamingRpc(request);
    }

    public Kv.Count count(Kv.Empty request, StoreInfo storeInfo, Node node) {
        DelayedResult<Kv.Count> delayedResult = new SingleDelayedResult<>();

        GrpcAsyncClientContext<KVServiceGrpc.KVServiceStub, Kv.Empty, Kv.Count> context
                = rollKvCallModelFactory.createEmptyToCountContext();

        context.setLatchInitCount(1)
                .setEndpoint(typeConversionUtils.toEndpoint(node))
                .setFinishTimeout(RuntimeConstants.DEFAULT_WAIT_TIME, RuntimeConstants.DEFAULT_TIMEUNIT)
                .setCalleeStreamingMethodInvoker(KVServiceGrpc.KVServiceStub::count)
                .setCallerStreamObserverClassAndArguments(StorageKvCountResponseObserver.class, delayedResult)
                .setGrpcMetadata(MetaConstants.createMetadataFromStoreInfo(storeInfo));

        GrpcStreamingClientTemplate<KVServiceGrpc.KVServiceStub, Kv.Empty, Kv.Count> template
                = rollKvCallModelFactory.createEmptyToCountTemplate();
        template.setGrpcAsyncClientContext(context);

        Kv.Count result = null;

        try {
            result = template.calleeStreamingRpcWithImmediateDelayedResult(request, delayedResult);
        } catch (InvocationTargetException e) {
            LOGGER.error(errorUtils.getStackTrace(e));

            throw new RuntimeException(e);
        }

        return result;
    }
}
