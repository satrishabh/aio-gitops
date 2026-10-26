package com.aio.platform.service;

import com.aio.platform.domain.Project;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Blue-green for prod via Argo Rollouts. The chart renders a Rollout (instead of a Deployment) when
 * {@code blueGreen.enabled}; a new image starts as the "preview" next to the live version and only takes
 * traffic when promoted here. Abort switches back to the previous (still running) version.
 *
 * <p>Prod may run on a workload cluster the platform has no credentials for, so the Rollout is read and
 * driven through Argo CD (live manifest + the built-in Rollout resource actions).
 */
@Service
public class RolloutService {
    private static final Logger log = LoggerFactory.getLogger(RolloutService.class);
    public static final String ENV = "prod";
    private static final String GROUP = "argoproj.io", VERSION = "v1alpha1", KIND = "Rollout";

    private final ArgoCdService argo;
    private final ClusterRegistry clusters;

    public RolloutService(ArgoCdService argo, ClusterRegistry clusters) {
        this.argo = argo;
        this.clusters = clusters;
    }

    /** True when prod's cluster runs Argo Rollouts; until then prod keeps plain rolling Deployments (never broken). */
    public boolean installed() {
        return clusters.rolloutsFor(ENV);
    }

    /** View of a Rollout for the portal: what is live, what is waiting in preview, and whether it can be promoted. */
    public record Status(boolean enabled, String phase, String activeImage, String previewImage,
                         boolean awaitingPromotion, boolean aborted, String message) {
        static final Status OFF = new Status(false, null, null, null, false, false, null);
    }

    public Status status(Project p) {
        if (!installed()) return Status.OFF;
        try {
            Map<String, Object> r = argo.resource(app(p), rollout(p));
            if (r == null) return Status.OFF;
            Raw raw = parse(r);
            String active = imageOf(p, raw.activeHash()), preview = raw.split() ? imageOf(p, raw.previewHash()) : null;
            return new Status(true, raw.phase(), active == null ? raw.specTag() : active, preview,
                    raw.paused() && raw.split() && !raw.aborted(), raw.aborted(), raw.message());
        } catch (RuntimeException e) {
            log.debug("rollout read {} failed: {}", p.name, e.getMessage());
            return Status.OFF;
        }
    }

    /** Image tag of the ReplicaSet for this pod-template hash (Argo Rollouts names it {@code <rollout>-<hash>}). */
    private String imageOf(Project p, String hash) {
        if (hash == null) return null;
        Map<String, Object> rs = argo.resource(app(p), new ArgoCdService.Ref(ns(p), "apps", "v1", "ReplicaSet", p.name + "-" + hash));
        List<Map<String, Object>> c = J.list(J.get(rs, "spec", "template", "spec"), "containers");
        return c.isEmpty() ? null : tag(J.str(c.get(0), "image"));
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
        argo.runAction(app(p), rollout(p), "resume");
        log.info("rollout {} promoted", p.name);
    }

    /** Send traffic back to the previous version and scale the preview down. */
    public void abort(Project p) {
        argo.runAction(app(p), rollout(p), "abort");
        log.info("rollout {} aborted", p.name);
    }

    /** After an abort, accept the current spec again (Argo Rollouts needs this to retry the same version). */
    public void retry(Project p) {
        argo.runAction(app(p), rollout(p), "retry");
    }

    private static String app(Project p) { return ArgoCdService.appName(p, ENV); }
    private static String ns(Project p) { return ClusterService.namespace(p, ENV); }
    private static ArgoCdService.Ref rollout(Project p) { return new ArgoCdService.Ref(ns(p), GROUP, VERSION, KIND, p.name); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static String tag(String image) {
        if (image == null) return null;
        int i = image.lastIndexOf(':');
        return i < 0 || image.indexOf('/', i) >= 0 ? image : image.substring(i + 1);
    }
}
