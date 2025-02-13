package io.smallrye.stork.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.smallrye.stork.api.config.LoadBalancerConfig;
import io.smallrye.stork.api.config.ServiceConfig;
import io.smallrye.stork.api.config.ServiceDiscoveryConfig;
import io.smallrye.stork.spi.config.ConfigProvider;

/**
 * Stork config provider for tests, allows easily configuring stuff programmatically
 */
public class TestConfigProvider implements ConfigProvider {
    private static final List<ServiceConfig> configs = new ArrayList<>();

    private static int priority = Integer.MAX_VALUE;

    public static void setPriority(int priority) {
        TestConfigProvider.priority = priority;
    }

    public static int getPriority() {
        return priority;
    }

    public static void addServiceConfig(String name, String loadBalancer, String serviceDiscovery,
            Map<String, String> loadBalancerParams, Map<String, String> serviceDiscoveryParams) {
        addServiceConfig(name, loadBalancer, serviceDiscovery, loadBalancerParams, serviceDiscoveryParams, false);
    }

    public static void addServiceConfig(String name, String loadBalancer, String serviceDiscovery,
            Map<String, String> loadBalancerParams, Map<String, String> serviceDiscoveryParams, boolean secure) {
        configs.add(new ServiceConfig() {
            @Override
            public String serviceName() {
                return name;
            }

            @Override
            public boolean secure() {
                return secure;
            }

            @Override
            public LoadBalancerConfig loadBalancer() {
                return loadBalancer == null ? null : new LoadBalancerConfig() {
                    @Override
                    public String type() {
                        return loadBalancer;
                    }

                    @Override
                    public Map<String, String> parameters() {
                        if (loadBalancerParams == null) {
                            return Collections.emptyMap();
                        }
                        return loadBalancerParams;
                    }
                };
            }

            @Override
            public ServiceDiscoveryConfig serviceDiscovery() {
                return new ServiceDiscoveryConfig() {
                    @Override
                    public String type() {
                        return serviceDiscovery;
                    }

                    @Override
                    public Map<String, String> parameters() {
                        if (serviceDiscoveryParams == null) {
                            return Collections.emptyMap();
                        }
                        return serviceDiscoveryParams;
                    }
                };
            }
        });
    }

    public static void clear() {
        configs.clear();
    }

    @Override
    public List<ServiceConfig> getConfigs() {
        return configs;
    }

    @Override
    public int priority() {
        return priority;
    }
}
