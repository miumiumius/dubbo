/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.cluster.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.threadlocal.NamedInternalThreadFactory;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Invoke a specific number of invokers concurrently, usually used for demanding real-time operations, but need to waste more service resources.
 *
 * <a href="http://en.wikipedia.org/wiki/Fork_(topology)">Fork</a>
 * 会在运行时通过线程池创建多个线程，并发调用多个服务提供者。只要有一个服务提供者成功返回了结果，doInvoke 方法就会立即结束运行。
 * ForkingClusterInvoker 的应用场景是在一些对实时性要求比较高读操作（注意是读操作，并行写操作可能不安全）下使用，但这将会耗费更多的资源。
 */
public class ForkingClusterInvoker<T> extends AbstractClusterInvoker<T> {

    /**
     * Use {@link NamedInternalThreadFactory} to produce {@link com.alibaba.dubbo.common.threadlocal.InternalThread}
     * which with the use of {@link com.alibaba.dubbo.common.threadlocal.InternalThreadLocal} in {@link RpcContext}.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(
            new NamedInternalThreadFactory("forking-cluster-timer", true));

    public ForkingClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(final Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        try {
            // 检查 invokers 即可用Invoker集合是否为空，如果为空，那么抛出异常
            checkInvokers(invokers, invocation);
            // 保存选择的 Invoker 集合
            final List<Invoker<T>> selected;
            // 得到最大并行数，默认为 Constants.DEFAULT_FORKS = 2
            final int forks = getUrl().getParameter(Constants.FORKS_KEY, Constants.DEFAULT_FORKS);
            // 获得调用超时时间，默认为 DEFAULT_TIMEOUT = 1000 毫秒
            final int timeout = getUrl().getParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
            // 若最大并行书小于等于 0，或者大于 invokers 的数量，直接使用 invokers
            if (forks <= 0 || forks >= invokers.size()) {
                selected = invokers;
            } else {
                // 循环，根据负载均衡机制从 invokers，中选择一个个Invoker ，从而组成 Invoker 集合。
                // 注意，因为增加了排重逻辑，所以不能保证获得的 Invoker 集合的大小，小于最大并行数
                selected = new ArrayList<Invoker<T>>();
                // 循环选出 forks 个 Invoker，并添加到 selected 中
                for (int i = 0; i < forks; i++) {
                    // TODO. Add some comment here, refer chinese version for more details.
                    Invoker<T> invoker = select(loadbalance, invocation, invokers, selected);
                    // 选择 Invoker
                    if (!selected.contains(invoker)) {//Avoid add the same invoker several times.
                        selected.add(invoker);
                    }
                }
            }

            // ----------------------✨ 分割线1 ✨---------------------- //
            // 设置已经调用的 Invoker 集合，到 Context 中
            RpcContext.getContext().setInvokers((List) selected);
            // 异常计数器
            final AtomicInteger count = new AtomicInteger();
            // 创建阻塞队列
            final BlockingQueue<Object> ref = new LinkedBlockingQueue<Object>();
            // 循环 selected 集合，提交线程池，发起 RPC 调用
            for (final Invoker<T> invoker : selected) {
                // 为每个 Invoker 创建一个执行线程
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // 进行远程调用
                            Result result = invoker.invoke(invocation);
                            // 将结果存到阻塞队列中
                            ref.offer(result);
                        } catch (Throwable e) {
                            int value = count.incrementAndGet();
                            // 仅在 value 大于等于 selected.size() 时，才将异常对象放入阻塞队列中

                            // 原因：在并行调用多个服务提供者的情况下，只要有一个服务提供者能够成功返回结果，而其他全部失败。
                            // 此时 ForkingClusterInvoker 仍应该返回成功的结果，而非抛出异常。
                            // 在value >= selected.size()时将异常对象放入阻塞队列中，可以保证异常对象不会出现在正常结果的前面，
                            // 这样可从阻塞队列中优先取出正常的结果。
                            if (value >= selected.size()) {
                                // 将异常对象存入到阻塞队列中
                                ref.offer(e);
                            }
                        }
                    }
                });
            }
            // ----------------------✨ 分割线2 ✨---------------------- //

            try {
                // 从阻塞队列中取出远程调用结果
                Object ret = ref.poll(timeout, TimeUnit.MILLISECONDS);
                // 如果结果类型为 Throwable，则抛出异常
                if (ret instanceof Throwable) {
                    Throwable e = (Throwable) ret;
                    throw new RpcException(e instanceof RpcException ? ((RpcException) e).getCode() : 0, "Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e.getCause() != null ? e.getCause() : e);
                }
                // 返回结果
                return (Result) ret;
            } catch (InterruptedException e) {
                throw new RpcException("Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e);
            }
        } finally {
            // clear attachments which is binding to current thread.
            RpcContext.getContext().clearAttachments();
        }
    }
}
