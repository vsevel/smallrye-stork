package io.smallrye.stork.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.smallrye.stork.api.LoadBalancer;
import io.smallrye.stork.api.NoServiceInstanceFoundException;
import io.smallrye.stork.api.ServiceInstance;

public class RoundRobinLoadBalancer implements LoadBalancer {

    private final AtomicInteger index = new AtomicInteger();

    @Override
    public ServiceInstance selectServiceInstance(Collection<ServiceInstance> serviceInstances) {
        if (serviceInstances.isEmpty()) {
            throw new NoServiceInstanceFoundException("No services found.");
        }
        // todo do better, cache the list if possible maybe?
        List<ServiceInstance> modifiableList = new ArrayList<>(serviceInstances);
        modifiableList.sort(Comparator.comparingLong(ServiceInstance::getId));
        return select(modifiableList);
    }

    private ServiceInstance select(List<ServiceInstance> instances) {
        if (instances.isEmpty()) {
            return null;
        }

        return instances.get(index.getAndIncrement() % instances.size());
    }
}
