package io.smallrye.stork.servicediscovery.kubernetes;

import static io.smallrye.stork.servicediscovery.kubernetes.KubernetesMetadataKey.META_K8S_SERVICE_ID;

import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointPort;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.smallrye.mutiny.Uni;
import io.smallrye.stork.api.Metadata;
import io.smallrye.stork.api.ServiceInstance;
import io.smallrye.stork.impl.CachingServiceDiscovery;
import io.smallrye.stork.impl.DefaultServiceInstance;
import io.smallrye.stork.utils.ServiceInstanceIds;
import io.smallrye.stork.utils.ServiceInstanceUtils;
import io.vertx.core.Vertx;

public class KubernetesServiceDiscovery extends CachingServiceDiscovery {

    public static final String METADATA_NAME = "metadata.name";
    private final KubernetesClient client;
    private final String serviceName;
    private String application;
    private final boolean allNamespaces;
    private final String namespace;
    private final boolean secure;
    private final Vertx vertx;

    private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesServiceDiscovery.class);

    public KubernetesServiceDiscovery(String serviceName, KubernetesServiceDiscoveryProviderConfiguration config, Vertx vertx,
            boolean secure) {
        super(config.getRefreshPeriod());
        Config base = Config.autoConfigure(null);
        this.serviceName = serviceName;
        String masterUrl = config.getK8sHost() == null ? base.getMasterUrl() : config.getK8sHost();
        this.application = config.getApplication() == null ? serviceName : config.getApplication();
        this.namespace = config.getK8sNamespace() == null ? base.getNamespace() : config.getK8sNamespace();

        allNamespaces = namespace != null && namespace.equalsIgnoreCase("all");

        if (namespace == null) {
            throw new IllegalArgumentException("Namespace is not configured for service '" + serviceName
                    + "'. Please provide a namespace. Use 'all' to discover services in all namespaces");
        }

        Config k8sConfig = new ConfigBuilder(base)
                .withMasterUrl(masterUrl)
                .withNamespace(namespace).build();
        client = new DefaultKubernetesClient(k8sConfig);
        this.vertx = vertx;
        this.secure = secure;
    }

    @Override
    public Uni<List<ServiceInstance>> fetchNewServiceInstances(List<ServiceInstance> previousInstances) {
        Uni<Map<Endpoints, List<Pod>>> endpointsUni = Uni.createFrom().emitter(
                emitter -> {
                    vertx.executeBlocking(future -> {
                        Map<Endpoints, List<Pod>> items = new HashMap<>();

                        if (allNamespaces) {
                            List<Endpoints> endpointsList = client.endpoints().inAnyNamespace()
                                    .withField(METADATA_NAME, application).list()
                                    .getItems();
                            for (Endpoints endpoint : endpointsList) {
                                List<Pod> backendPods = new ArrayList<>();
                                List<String> podNames = endpoint.getSubsets().stream()
                                        .flatMap(endpointSubset -> endpointSubset.getAddresses().stream())
                                        .map(address -> address.getTargetRef().getName()).collect(Collectors.toList());
                                podNames.forEach(podName -> backendPods
                                        .addAll(client.pods().inAnyNamespace().withField(METADATA_NAME, podName).list()
                                                .getItems()));
                                items.put(endpoint, backendPods);
                            }
                        } else {
                            List<Endpoints> endpointsList = client.endpoints().inNamespace(namespace)
                                    .withField(METADATA_NAME, application)
                                    .list()
                                    .getItems();
                            for (Endpoints endpoint : endpointsList) {
                                List<Pod> backendPods = new ArrayList<>();
                                List<String> podNames = endpoint.getSubsets().stream()
                                        .flatMap(endpointSubset -> endpointSubset.getAddresses().stream())
                                        .map(address -> address.getTargetRef().getName()).collect(Collectors.toList());
                                backendPods.addAll(podNames.stream()
                                        .map(name -> client.pods().inNamespace(namespace).withName(name))
                                        .map(podPodResource -> podPodResource.get()).collect(Collectors.toList()));
                                items.put(endpoint, backendPods);
                            }
                        }
                        future.complete(items);
                    }, result -> {
                        if (result.succeeded()) {
                            @SuppressWarnings("unchecked")
                            Map<Endpoints, List<Pod>> endpoints = (Map<Endpoints, List<Pod>>) result.result();
                            emitter.complete(endpoints);
                        } else {
                            LOGGER.error("Unable to retrieve the endpoint from the {} service", application,
                                    result.cause());
                            emitter.fail(result.cause());
                        }
                    });
                });
        return endpointsUni.onItem().transform(endpoints -> toStorkServiceInstances(endpoints, previousInstances));
    }

    private List<ServiceInstance> toStorkServiceInstances(Map<Endpoints, List<Pod>> backend,
            List<ServiceInstance> previousInstances) {
        List<ServiceInstance> serviceInstances = new ArrayList<>();
        for (Map.Entry<Endpoints, List<Pod>> entry : backend.entrySet()) {
            Endpoints endPoints = entry.getKey();
            List<Pod> pods = entry.getValue();
            for (EndpointSubset subset : endPoints.getSubsets()) {
                for (EndpointAddress endpointAddress : subset.getAddresses()) {
                    String podName = endpointAddress.getTargetRef().getName();
                    String hostname = endpointAddress.getIp();
                    if (hostname == null) { // should we take the hostName?
                        hostname = endpointAddress.getHostname();
                    }
                    List<EndpointPort> endpointPorts = subset.getPorts();
                    Integer port = 0;
                    if (endpointPorts.size() == 1) {
                        port = endpointPorts.get(0).getPort();
                    }

                    ServiceInstance matching = ServiceInstanceUtils.findMatching(previousInstances, hostname, port);
                    if (matching != null) {
                        serviceInstances.add(matching);
                    } else {
                        Map<String, String> labels = new HashMap<>(endPoints.getMetadata().getLabels() != null
                                ? endPoints.getMetadata().getLabels()
                                : Collections.emptyMap());
                        Optional<Pod> maybePod = pods.stream().filter(pod -> pod.getMetadata().getName().equals(podName))
                                .findFirst();
                        if (maybePod.isPresent()) {
                            Map<String, String> podLabels = maybePod.get().getMetadata().getLabels();
                            for (Map.Entry<String, String> label : podLabels.entrySet()) {
                                labels.putIfAbsent(label.getKey(), label.getValue());
                            }
                        }
                        //TODO add some useful metadata?
                        Metadata<KubernetesMetadataKey> k8sMetadata = Metadata.of(KubernetesMetadataKey.class);
                        serviceInstances.add(new DefaultServiceInstance(ServiceInstanceIds.next(), hostname, port, secure,
                                labels, k8sMetadata.with(META_K8S_SERVICE_ID, hostname)));
                    }
                }
            }

        }
        return serviceInstances;
    }

}
