package com.aio.platform.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.http.HttpRequest;
import io.fabric8.kubernetes.client.http.HttpResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Does a Secret or ConfigMap exist in an env namespace? Existence only: the request asks the API server for
 * {@code PartialObjectMetadata}, so Secret data never reaches the platform.
 *
 * <p>Only envs hosted on the hub can be checked: for workload clusters the platform holds no credentials
 * (Argo CD does), and {@link #canCheck} is false.
 */
@Service
public class NamespaceObjects {
    private static final Logger log = LoggerFactory.getLogger(NamespaceObjects.class);
    private static final String METADATA_ONLY = "application/json;as=PartialObjectMetadata;g=meta.k8s.io;v=v1";

    private final KubernetesClient k8s;
    private final ClusterRegistry clusters;

    public NamespaceObjects(KubernetesClient k8s, ClusterRegistry clusters) {
        this.k8s = k8s;
        this.clusters = clusters;
    }

    public boolean canCheck(String env) {
        return clusters.inCluster(clusters.clusterFor(env));
    }

    /** true / false, or null when it could not be determined (no access, cluster unreachable). */
    public Boolean exists(String env, String namespace, PromotionCheck.ObjectRef ref) {
        if (!canCheck(env)) return null;
        String plural = "secret".equals(ref.kind()) ? "secrets" : "configmaps";
        try {
            String master = k8s.getMasterUrl().toString().replaceAll("/+$", "");
            URI uri = URI.create(master + "/api/v1/namespaces/" + enc(namespace) + "/" + plural + "/" + enc(ref.name()));
            HttpRequest req = k8s.getHttpClient().newHttpRequestBuilder().uri(uri).header("Accept", METADATA_ONLY).build();
            HttpResponse<String> res = k8s.getHttpClient().sendAsync(req, String.class).get(10, TimeUnit.SECONDS);
            if (res.code() == 200) return true;
            if (res.code() == 404) return false;
            log.debug("existence check {} {}/{}: HTTP {}", plural, namespace, ref.name(), res.code());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("existence check {} {}/{} failed: {}", plural, namespace, ref.name(), e.getMessage());
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
