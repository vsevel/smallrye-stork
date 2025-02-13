package io.smallrye.stork.loadbalancer.leastresponsetime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.stork.Stork;
import io.smallrye.stork.api.NoServiceInstanceFoundException;
import io.smallrye.stork.api.Service;
import io.smallrye.stork.api.ServiceInstance;
import io.smallrye.stork.test.StorkTestUtils;
import io.smallrye.stork.test.TestConfigProvider;

public class LeastRequestsLoadBalancerTest {
    private static final Logger log = LoggerFactory.getLogger(LeastRequestsLoadBalancerTest.class);

    public static final String FST_SRVC_1 = "localhost:8080";
    public static final String FST_SRVC_2 = "localhost:8081";
    private Stork stork;

    @BeforeEach
    void setUp() {
        TestConfigProvider.clear();
        TestConfigProvider.addServiceConfig("first-service", "least-requests", "static",
                null,
                Map.of("address-list", String.format("%s,%s", FST_SRVC_1, FST_SRVC_2)));
        TestConfigProvider.addServiceConfig("without-instances", "least-requests",
                "empty-services", null, Collections.emptyMap());

        stork = StorkTestUtils.getNewStorkInstance();
    }

    @Test
    public void shouldSelectLessLoaded() {
        Service service = stork.getService("first-service");

        ServiceInstance service1, service2;

        // First selection returns the first service
        ServiceInstance instance = selectInstance(service);
        assertThat(asString(instance)).isEqualTo(FST_SRVC_1);
        assertThat(instance.gatherStatistics()).isTrue();
        service1 = instance;

        // Second selection returns the second service as there is an inflight-call in the first one
        instance = selectInstance(service);
        assertThat(asString(instance)).isEqualTo(FST_SRVC_2);
        assertThat(instance.gatherStatistics()).isTrue();
        service2 = instance;

        // Report termination of the service 2 call
        instance.recordResult(10, null);

        // Next selection still return the second service, as we still have an inflight-call in service 1
        instance = selectInstance(service);
        assertThat(asString(instance)).isEqualTo(FST_SRVC_2);
        assertThat(instance.gatherStatistics()).isTrue();

        service1.recordResult(1000, null);
        // Now select 1 as they have the same amount of inflight
        instance = selectInstance(service);
        assertThat(asString(instance)).isEqualTo(FST_SRVC_1);
        assertThat(instance.gatherStatistics()).isTrue();
    }

    @Test
    public void shouldSelectLessLoadedWhenBothLoaded() {
        Service service = stork.getService("first-service");

        ServiceInstance service1, service2;

        // First selection returns the first service
        ServiceInstance instance = selectInstance(service);
        assertThat(asString(instance)).isEqualTo(FST_SRVC_1);
        assertThat(instance.gatherStatistics()).isTrue();
        service1 = instance;

        // Second selection returns the second service as there is an inflight-call in the first one
        instance = selectInstance(service);
        assertThat(asString(instance)).isEqualTo(FST_SRVC_2);
        assertThat(instance.gatherStatistics()).isTrue();
        service2 = instance;

        ServiceInstance last = service2;
        for (int i = 0; i < 100; i++) {
            ServiceInstance selected = selectInstance(service);
            assertThat(asString(selected)).isNotEqualTo(asString(last));
            last = selected;
        }

        Random random = new Random();

        last = service1;
        // Start reporting
        for (int i = 0; i < 100; i++) {
            if (random.nextInt(10) > 7) {
                // Simulate failures
                last.recordResult(1000, new Exception("boom"));
            } else {
                last.recordResult(1000, null);
            }

            ServiceInstance selected = selectInstance(service);
            assertThat(asString(selected)).isEqualTo(asString(last));
            last = selected == service1 ? service2 : service1;
        }
    }

    @Test
    void shouldPickBothService() {
        Service service = stork.getService("first-service");

        Set<String> instances = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            instances.add(asString(selectInstance(service)));
        }

        assertThat(instances).hasSize(2).contains(FST_SRVC_1, FST_SRVC_2);
    }

    @Test
    void shouldThrowNoServiceInstanceOnNoInstances() throws ExecutionException, InterruptedException {
        Service service = stork.getService("without-instances");

        CompletableFuture<Throwable> result = new CompletableFuture<>();

        service.selectServiceInstance().subscribe().with(v -> log.error("Unexpected successful result: {}", v),
                result::complete);

        await().atMost(Duration.ofSeconds(10)).until(result::isDone);
        assertThat(result.get()).isInstanceOf(NoServiceInstanceFoundException.class);
    }

    @Test
    void shouldAlwaysPickFirstWhenTheCallReturnsImmediately() {
        Service service = stork.getService("first-service");

        Set<String> instances = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            ServiceInstance instance = selectInstance(service);
            instance.recordResult(1, null);
            instances.add(asString(instance));
        }

        assertThat(instances).hasSize(1).contains(FST_SRVC_1);
    }

    private ServiceInstance selectInstance(Service service) {
        return service.selectServiceInstance().await().atMost(Duration.ofSeconds(5));
    }

    private String asString(ServiceInstance serviceInstance) {
        try {
            return String.format("%s:%s", serviceInstance.getHost(), serviceInstance.getPort());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
