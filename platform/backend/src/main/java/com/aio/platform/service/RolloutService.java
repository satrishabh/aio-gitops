package com.aio.platform.service;

import com.aio.platform.domain.Project;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Blue-green for prod via Argo Rollouts. The chart renders a Rollout (instead of a Deployment) when
 * {@code blueGreen.enabled}; a new image starts as the "preview" next to the live version and only takes
 * traffic when promoted here. Abort switches back to the previous (still running) version.
 */
@Service
public class RolloutService {
    private static final Logger log = LoggerFactory.getLogger(RolloutService.class);
    public static final String ENV = "prod";
    static final ResourceDefinitionContext ROLLOUT = new ResourceDefinitionContext.Builder()
            .withGroup("argoproj.io").withVersion("v1alpha1").withPlural("rollouts").withKind("Rollout")
            .withNamespaced(true).build();
    private static final Duration CRD_CACHE = Duration.ofMinutes(5);

    private final KubernetesClient k8s;
    private volatile Boolean installed;
    private volatile Instant checkedAt = Instant.EPOCH;

    public RolloutService(KubernetesClient k8s) {
        this.k8s = k8s;
    }

    /** True once the Argo Rollouts CRD exists; until then prod keeps plain rolling Deployments (never broken). */
    public boolean installed() {
        if (installed != null && checkedAt.plus(CRD_CACHE).isAfter(Instant.now())) return installed;
        try {
            installed = k8s.apiextensions().v1().customResourceDefinitions().withName("rollouts.argoproj.io").get() != null;
        } catch (RuntimeException e) {
            log.debug("rollouts CRD check failed: {}", e.getMessage());
            installed = installed != null && installed; // keep last known value on transient errors
        }
        checkedAt = Instant.now();
        return installed;
    }

    /** View of a Rollout for the portal: what is live, what is waiting in preview, and whether it can be promoted. */
    public record Status(boolean enabled, String phase, String activeImage, String previewImage,
                         boolean awaitingPromotion, boolean aborted, String message) {
        static final Status OFF = new Status(false, null, null, null, false, false, null);
    }

    public Status status(Project p) {
        if (!installed()) return Status.OFF;
        try {
            GenericKubernetesResource r = k8s.genericKubernetesResources(ROLLOUT).inNamespace(ENV).withName(p.name).get();
            if (r == null) return Status.OFF;
            Raw raw = parse(r.getAdditionalProperties());
            String active = imageOf(raw.activeHash()), preview = raw.split() ? imageOf(raw.previewHash()) : null;
            return new Status(true, raw.phase(), active == null ? raw.specTag() : active, preview,
                    raw.paused() && raw.split() && !raw.aborted(), raw.aborted(), raw.message());
        } catch (RuntimeException e) {
            log.debug("rollout read {} failed: {}", p.name, e.getMessage());
            return Status.OFF;
        }
    }

    /** Image tag of the ReplicaSet Argo Rollouts labelled with this pod-template hash. */
    private String imageOf(String hash) {
        if (hash == null) return null;
        var rs = k8s.apps().replicaSets().inNamespace(ENV).withLabel("rollouts-pod-template-hash", hash).list().getItems();
        if (rs.isEmpty()) return null;
        var c = rs.get(0).getSpec().getTemplate().getSpec().getContainers();
        return c.isEmpty() ? null : tag(c.get(0).getImage());
    }

    record Raw(String phase, String specTag, String activeHash, String previewHash, boolean paused,
               boolean aborted, String message) {
        boolean split() { return previewHash != null && !previewHash.equals(activeHash); }
    }

    static Raw parse(Map<String, Object> props) {
        Map<String, Object> spec = map(props.get("spec"));
        Map<String, Object> st = map(props.get("status"));
        Map<String, Object> bg = map(st.get("blueGreen"));
        String image = null;
        Object containers = map(map(spec.get("template")).get("spec")).get("containers");
        if (containers instanceof List<?> l && !l.isEmpty()) image = str(map(l.get(0)).get("image"));
        boolean paused = st.get("pauseConditions") instanceof List<?> pc && !pc.isEmpty();
        return new Raw(str(st.get("phase")), tag(image), str(bg.get("activeSelector")), str(bg.get("previewSelector")),
                paused, Boolean.TRUE.equals(st.get("abort")), str(st.get("message")));
    }

    /** Switch traffic to the preview version (same as "kubectl argo rollouts promote"). */
    public void promote(Project p) {
        Map<String, Object> clear = new java.util.HashMap<>();
        clear.put("pauseConditions", null);   // merge-patch null removes the field
        patchStatus(p, clear);
        patchSpec(p, Map.of("paused", false));
        log.info("rollout {} promoted", p.name);
    }

    /** Send traffic back to the previous version and scale the preview down. */
    public void abort(Project p) {
        patchStatus(p, Map.of("abort", true));
        log.info("rollout {} aborted", p.name);
    }

    /** After an abort, accept the current spec again (Argo Rollouts needs this to retry the same version). */
    public void retry(Project p) {
        patchStatus(p, Map.of("abort", false));
    }

    private void patchStatus(Project p, Map<String, Object> status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        k8s.genericKubernetesResources(ROLLOUT).inNamespace(ENV).withName(p.name).subresource("status")
                .patch(PatchContext.of(PatchType.JSON_MERGE), k8s.getKubernetesSerialization().asJson(body));
    }

    private void patchSpec(Project p, Map<String, Object> spec) {
        k8s.genericKubernetesResources(ROLLOUT).inNamespace(ENV).withName(p.name)
                .patch(PatchContext.of(PatchType.JSON_MERGE), k8s.getKubernetesSerialization().asJson(Map.of("spec", spec)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static String tag(String image) {
        if (image == null) return null;
        int i = image.lastIndexOf(':');
        return i < 0 || image.indexOf('/', i) >= 0 ? image : image.substring(i + 1);
    }
}
