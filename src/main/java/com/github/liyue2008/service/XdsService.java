package com.github.liyue2008.service;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import java.util.concurrent.TimeUnit;

import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.net.InetAddress;
import java.net.UnknownHostException;

@Service
public class XdsService {
    private static final Logger logger = LoggerFactory.getLogger(XdsService.class);
    private static final int REQUEST_TIMEOUT_SECONDS = 10;
    
    @Value("${istiod.host:istiod.istio-system.svc.cluster.local}")
    private String istiodHost;
    
    @Value("${istiod.port:15010}")
    private int istiodPort;
    
    private ManagedChannel channel;
    
    @PostConstruct
    public void init() {
        channel = ManagedChannelBuilder.forAddress(istiodHost, istiodPort)
                .usePlaintext()
                .build();
    }
    
    @PreDestroy
    public void destroy() {
        if (channel != null) {
            channel.shutdown();
            try {
                channel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                logger.error("Error shutting down gRPC channel", e);
            }
        }
    }

    /**
     * 获取服务的路由信息
     * @param serviceName Kubernetes service 名称
     * @param namespace Kubernetes 命名空间
     */
    public void fetchServiceRoutes(String serviceName, String namespace) {
        String fullServiceName = String.format("%s.%s.svc.cluster.local", serviceName, namespace);
        try {
            // 构建 CDS 请求
            DiscoveryRequest request = DiscoveryRequest.newBuilder()
                    .setTypeUrl("type.googleapis.com/envoy.config.cluster.v3.Cluster")
                    .setNode(buildNode())
                    .addResourceNames(fullServiceName)
                    .build();

            DiscoveryResponse response = sendXdsRequestWithRetry(request);
            
            for (com.google.protobuf.Any any : response.getResourcesList()) {
                if (any.is(Cluster.class)) {
                    Cluster cluster = any.unpack(Cluster.class);
                    processCluster(cluster);
                }
            }
            
            // 获取端点信息 (EDS)
            fetchEndpoints(serviceName, namespace);
            
        } catch (Exception e) {
            logger.error("Error fetching service routes for {}.{}", serviceName, namespace, e);
        }
    }
    
    /**
     * 获取服务端点信息
     */
    private void fetchEndpoints(String serviceName, String namespace) {
        String fullServiceName = String.format("%s.%s.svc.cluster.local", serviceName, namespace);
        DiscoveryRequest request = DiscoveryRequest.newBuilder()
                .setTypeUrl("type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment")
                .setNode(buildNode())
                .addResourceNames(fullServiceName)
                .build();
                
        DiscoveryResponse response = sendXdsRequest(request);
        
        for (com.google.protobuf.Any any : response.getResourcesList()) {
            if (any.is(ClusterLoadAssignment.class)) {
                try {
                    ClusterLoadAssignment endpoints = any.unpack(ClusterLoadAssignment.class);
                    processEndpoints(endpoints);
                } catch (Exception e) {
                    logger.error("Error processing endpoints", e);
                }
            }
        }
    }
    
    private io.envoyproxy.envoy.config.core.v3.Node buildNode() {
        String id = String.format("sidecar~%s~%s.%s~%s",
                getLocalIP(),           // 本地IP
                "xds-client",          // pod名称
                "default",             // 命名空间
                "default.svc.cluster.local" // 集群域名
        );
        
        return io.envoyproxy.envoy.config.core.v3.Node.newBuilder()
                .setId(id)
                .setCluster("xds-client")
                .build();
    }
    
    private String getLocalIP() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }
    
    private void processCluster(Cluster cluster) {
        logger.info("Received cluster: {}", cluster.getName());
        // 处理集群配置
    }
    
    private void processEndpoints(ClusterLoadAssignment endpoints) {
        logger.info("Received endpoints for cluster: {}", endpoints.getClusterName());
        endpoints.getEndpointsList().forEach(endpoint -> {
            endpoint.getLbEndpointsList().forEach(lbEndpoint -> {
                String address = lbEndpoint.getEndpoint().getAddress().getSocketAddress().getAddress();
                int port = lbEndpoint.getEndpoint().getAddress().getSocketAddress().getPortValue();
                logger.info("Endpoint: {}:{}", address, port);
            });
        });
    }
    
    private DiscoveryResponse sendXdsRequest(DiscoveryRequest request) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<DiscoveryResponse> responseRef = new AtomicReference<>();
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // 创建 ADS 服务存根
        AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub stub = 
            AggregatedDiscoveryServiceGrpc.newStub(channel);

        // 创建���向流
        StreamObserver<DiscoveryRequest> requestObserver = stub.streamAggregatedResources(
            new StreamObserver<DiscoveryResponse>() {
                @Override
                public void onNext(DiscoveryResponse response) {
                    logger.debug("Received xDS response: {}", response);
                    responseRef.set(response);
                    latch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    logger.error("Error in xDS stream", t);
                    errorRef.set(t);
                    latch.countDown();
                }

                @Override
                public void onCompleted() {
                    logger.debug("xDS stream completed");
                    latch.countDown();
                }
            });

        try {
            // 发送请求
            requestObserver.onNext(request);

            // 等待响应或超时
            if (!latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new RuntimeException("xDS request timed out after " + REQUEST_TIMEOUT_SECONDS + " seconds");
            }

            // 检查是否有错误
            if (errorRef.get() != null) {
                throw new RuntimeException("Error in xDS request", errorRef.get());
            }

            // 获取响应
            DiscoveryResponse response = responseRef.get();
            if (response == null) {
                throw new RuntimeException("No response received from xDS server");
            }

            return response;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("xDS request interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("Error sending xDS request", e);
        } finally {
            // 关闭请求流
            requestObserver.onCompleted();
        }
    }

    // 添加重试机制的包装方法
    private DiscoveryResponse sendXdsRequestWithRetry(DiscoveryRequest request) {
        int maxRetries = 3;
        int retryDelayMs = 1000;

        for (int i = 0; i < maxRetries; i++) {
            try {
                return sendXdsRequest(request);
            } catch (Exception e) {
                if (i == maxRetries - 1) {
                    throw e;
                }
                logger.warn("Retry {} failed, will retry in {} ms", i + 1, retryDelayMs, e);
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
                retryDelayMs *= 2; // 指数退避
            }
        }
        throw new RuntimeException("All retries failed");
    }
}
