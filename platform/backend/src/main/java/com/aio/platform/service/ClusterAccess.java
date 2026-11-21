package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReview;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Access to clusters added from the portal. Their only credential is the limited {@code aio-deployer} token from the
 * bootstrap script, kept in the Argo CD cluster Secret {@code argocd/aio-cluster-<name>} (never in the database).
 * Argo CD deploys with it; the platform reads the same Secret to prepare environment namespaces there and to test
 * the connection.
 */
@Service
public class ClusterAccess {
    private static final Logger log = LoggerFactory.getLogger(ClusterAccess.class);
    static final String ARGOCD_NS = "argocd";
    static final String MANAGED = "aio.platform/managed";
    private static final Duration CLIENT_TTL = Duration.ofMinutes(5);
    private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults().build();
    private static final ResourceDefinitionContext APP_PROJECT = new ResourceDefinitionContext.Builder()
            .withGroup("argoproj.io").withVersion("v1alpha1").withPlural("appprojects").withKind("AppProject")
            .withNamespaced(true).build();

    private final KubernetesClient hub;
    private final AppProperties props;
    private final ArgoCdService argo;
    private final Map<String, Cached> clients = new ConcurrentHashMap<>();

    private record Cached(String version, KubernetesClient client, Instant at) {}

    public ClusterAccess(KubernetesClient hub, AppProperties props, ArgoCdService argo) {
        this.hub = hub;
        this.props = props;
        this.argo = argo;
    }

    public static String secretName(String cluster) {
        return "aio-cluster-" + cluster;
    }

    /** Credentials pasted in step 3: a token and the cluster CA (base64 PEM, as Kubernetes prints it). */
    public record Credentials(String token, String caData) {
        public Credentials {
            token = token == null ? null : token.trim();
            caData = caData == null ? null : caData.replaceAll("\\s", "");
        }

        void validate() {
            if (token == null || token.isEmpty() || token.length() > 8192 || !token.matches("[A-Za-z0-9._-]+"))
                throw new IllegalArgumentException("token is missing or malformed (paste the JSON line the script printed)");
            if (caData == null || caData.isEmpty() || caData.length() > 65536 || !caData.matches("[A-Za-z0-9+/=]+"))
                throw new IllegalArgumentException("CA is missing or not base64");
            String pem = new String(Base64.getDecoder().decode(caData), StandardCharsets.US_ASCII);
            if (!pem.contains("BEGIN CERTIFICATE")) throw new IllegalArgumentException("CA is not a PEM certificate");
        }
    }

    /** Parses the one-line JSON the bootstrap script prints ({@code {"cluster","server","token","caData"}}). */
    public static Map<String, String> parseBootstrapLine(String line) {
        if (line == null || line.isBlank()) return Map.of();
        try {
            JsonNode n = JSON.readTree(line.trim());
            Map<String, String> m = new LinkedHashMap<>();
            for (String k : List.of("cluster", "server", "token", "caData"))
                if (n.hasNonNull(k)) m.put(k, n.get(k).asString());
            return m;
        } catch (Exception e) {
            throw new IllegalArgumentException("not the JSON line printed by the bootstrap script");
        }
    }

    // ------------------------------------------------------------------ Argo CD cluster Secret

    /** The Argo CD cluster Secret for a portal-added cluster (Argo CD deploys to it by {@code name}). */
    static Secret clusterSecret(String name, String server, Credentials creds, List<String> namespaces) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("bearerToken", creds.token());
        config.put("tlsClientConfig", Map.of("insecure", false, "caData", creds.caData()));
        Map<String, String> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("server", server);
        data.put("config", JSON.writeValueAsString(config));
        // Namespaced mode: Argo CD only watches the env namespaces (the token has no cluster-wide read on app kinds)
        if (namespaces != null && !namespaces.isEmpty()) data.put("namespaces", String.join(",", namespaces));
        data.put("clusterResources", "false");
        return new SecretBuilder().withNewMetadata().withName(secretName(name)).withNamespace(ARGOCD_NS)
                .addToLabels("argocd.argoproj.io/secret-type", "cluster")
                .addToLabels(MANAGED, "true")
                .addToLabels("aio.platform/cluster", name)
                .endMetadata().withType("Opaque").withStringData(data).build();
    }

    /** Creates or replaces the cluster Secret; returns the previous one (to restore when the tests fail) or null. */
    public Secret upsertSecret(String name, String server, Credentials creds, List<String> namespaces) {
        creds.validate();
        Secret previous = hub.secrets().inNamespace(ARGOCD_NS).withName(secretName(name)).get();
        if (previous != null && !isManaged(previous)) throw new IllegalStateException(secretName(name) + " exists and was not created by AIO");
        hub.secrets().inNamespace(ARGOCD_NS).resource(clusterSecret(name, server, creds, namespaces)).forceConflicts().serverSideApply();
        clients.remove(name);
        return previous;
    }

    public void restoreSecret(String name, Secret previous) {
        clients.remove(name);
        if (previous == null) {
            deleteSecret(name);
            return;
        }
        previous.getMetadata().setResourceVersion(null);
        previous.getMetadata().setManagedFields(null);
        hub.secrets().inNamespace(ARGOCD_NS).resource(previous).forceConflicts().serverSideApply();
    }

    /** Deletes the cluster Secret, but only one AIO created (label check). */
    public boolean deleteSecret(String name) {
        clients.remove(name);
        Secret s = hub.secrets().inNamespace(ARGOCD_NS).withName(secretName(name)).get();
        if (s == null || !isManaged(s)) return false;
        hub.secrets().inNamespace(ARGOCD_NS).withName(secretName(name)).delete();
        return true;
    }

    public boolean hasSecret(String name) {
        Secret s = hub.secrets().inNamespace(ARGOCD_NS).withName(secretName(name)).get();
        return s != null && isManaged(s);
    }

    /** Points Argo CD at the cluster's env namespaces (namespaced mode). No-op for clusters AIO did not add. */
    public void setNamespaces(String name, List<String> namespaces) {
        Secret s = hub.secrets().inNamespace(ARGOCD_NS).withName(secretName(name)).get();
        if (s == null || !isManaged(s)) return;
        String value = String.join(",", namespaces);
        Map<String, String> data = new LinkedHashMap<>(s.getData() == null ? Map.of() : s.getData());
        String current = data.get("namespaces") == null ? "" : new String(Base64.getDecoder().decode(data.get("namespaces")), StandardCharsets.UTF_8);
        if (current.equals(value)) return;
        hub.secrets().inNamespace(ARGOCD_NS).withName(secretName(name))
                .edit(x -> new SecretBuilder(x).addToStringData("namespaces", value).build());
    }

    private static boolean isManaged(Secret s) {
        Map<String, String> l = s.getMetadata().getLabels();
        return l != null && "true".equals(l.get(MANAGED)) && "cluster".equals(l.get("argocd.argoproj.io/secret-type"));
    }

    // ------------------------------------------------------------------ clients

    /** A client acting as {@code aio-deployer} on a portal-added cluster, or null for any other cluster. */
    public KubernetesClient client(String name) {
        Secret s;
        try {
            s = hub.secrets().inNamespace(ARGOCD_NS).withName(secretName(name)).get();
        } catch (KubernetesClientException e) {
            log.debug("cluster secret {} not readable: {}", name, e.getMessage());
            return null;
        }
        if (s == null || !isManaged(s) || s.getData() == null) return null;
        String version = s.getMetadata().getResourceVersion();
        Cached c = clients.get(name);
        if (c != null && version != null && version.equals(c.version()) && c.at().plus(CLIENT_TTL).isAfter(Instant.now())) return c.client();
        String server = decode(s.getData().get("server"));
        JsonNode cfg = JSON.readTree(decode(s.getData().get("config")));
        KubernetesClient client = build(server, new Credentials(cfg.path("bearerToken").asString(null),
                cfg.path("tlsClientConfig").path("caData").asString(null)));
        Cached old = clients.put(name, new Cached(version, client, Instant.now()));
        if (old != null && old.client() != client) old.client().close();
        return client;
    }

    private static String decode(String b64) {
        return b64 == null ? null : new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
    }

    /** A client from explicit credentials only (never the local kubeconfig or the pod's service account). */
    static KubernetesClient build(String server, Credentials creds) {
        Config cfg = new ConfigBuilder(Config.empty())
                .withMasterUrl(server)
                .withOauthToken(creds.token())
                .withCaCertData(creds.caData())
                .withConnectionTimeout(5000)
                .withRequestTimeout(10000)
                .withRequestRetryBackoffLimit(0)
                .build();
        return new KubernetesClientBuilder().withConfig(cfg).build();
    }

    // ------------------------------------------------------------------ connection tests

    /** One connection check shown in the Add cluster dialog. {@code ok} null: could not tell yet (not a failure). */
    public record Check(String name, Boolean ok, String detail) {}

    /** The verbs AIO and Argo CD rely on (cluster-wide part; namespaced rights come from the per-namespace binding). */
    static final List<String[]> REQUIRED = List.of(
            new String[]{"create", "", "namespaces", null},
            new String[]{"patch", "", "namespaces", null},
            new String[]{"list", "", "namespaces", null},
            new String[]{"list", "", "nodes", null},
            new String[]{"list", "", "pods", null},
            new String[]{"list", "", "events", null},
            new String[]{"list", "apiextensions.k8s.io", "customresourcedefinitions", null},
            new String[]{"create", "rbac.authorization.k8s.io", "rolebindings", null},
            new String[]{"bind", "rbac.authorization.k8s.io", "clusterroles", BootstrapScript.APP_ROLE});

    /** Kubernetes /version and can-i for each required verb, as the token. */
    public List<Check> testKubernetes(String server, Credentials creds) {
        List<Check> out = new ArrayList<>();
        try (KubernetesClient c = build(server, creds)) {
            try {
                var v = c.getKubernetesVersion();
                out.add(new Check("Kubernetes API reachable", true, "version " + v.getGitVersion()));
            } catch (RuntimeException e) {
                out.add(new Check("Kubernetes API reachable", false, short_(e)));
                return out;
            }
            for (String[] r : REQUIRED) {
                String label = "can " + r[0] + " " + (r[3] == null ? r[2] : r[2] + "/" + r[3]);
                try {
                    SelfSubjectAccessReview review = c.authorization().v1().selfSubjectAccessReview().create(
                            new SelfSubjectAccessReviewBuilder().withNewSpec().withNewResourceAttributes()
                                    .withVerb(r[0]).withGroup(r[1]).withResource(r[2]).withName(r[3])
                                    .endResourceAttributes().endSpec().build());
                    boolean allowed = review.getStatus() != null && Boolean.TRUE.equals(review.getStatus().getAllowed());
                    out.add(new Check(label, allowed, allowed ? "allowed" : "denied - re-run the bootstrap script"));
                } catch (RuntimeException e) {
                    out.add(new Check(label, false, short_(e)));
                }
            }
        }
        return out;
    }

    /** What Argo CD reports for the cluster (it connects lazily, so "Unknown" before the first app is not a failure). */
    public Check testArgo(String name) {
        try {
            var reg = argo.listClusters().stream().filter(a -> name.equals(J.str(a, "name"))).findFirst().orElse(null);
            if (reg == null) return new Check("registered in Argo CD", null, "not listed yet (the platform's Argo CD token may not list clusters)");
            String status = J.str(reg, "connectionState", "status");
            if (status == null) status = J.str(reg, "info", "connectionState", "status");
            String msg = J.str(reg, "connectionState", "message");
            if (msg == null) msg = J.str(reg, "info", "connectionState", "message");
            if ("Successful".equals(status)) return new Check("Argo CD connection", true, "Successful");
            if ("Failed".equals(status)) return new Check("Argo CD connection", false, msg == null ? "Failed" : msg);
            return new Check("Argo CD connection", null, "registered; Argo CD connects when the first app is deployed");
        } catch (RuntimeException e) {
            return new Check("Argo CD connection", null, "Argo CD not reachable: " + short_(e));
        }
    }

    /** Quick reachability for the scheduled health check: null when fine, else the error. */
    public String probe(String name, boolean hubCluster) {
        try {
            if (hubCluster) {
                hub.getKubernetesVersion();
                return null;
            }
            KubernetesClient c = client(name);
            if (c == null) {
                // added with add-cluster.sh: only Argo CD can reach it
                Check a = testArgo(name);
                return Boolean.FALSE.equals(a.ok()) ? a.detail() : null;
            }
            c.getKubernetesVersion();
            return null;
        } catch (RuntimeException e) {
            return short_(e);
        }
    }

    static String short_(Throwable e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        m = m.lines().findFirst().orElse(m);
        return m.length() > 300 ? m.substring(0, 300) + "..." : m;
    }

    // ------------------------------------------------------------------ namespace baseline helpers

    /** default-deny ingress plus "from the ingress controller and the same namespace" (as on the hub's env namespaces). */
    static List<NetworkPolicy> networkPolicies(String ns, String ingressNamespace) {
        NetworkPolicy deny = new NetworkPolicyBuilder().withNewMetadata().withName("default-deny-ingress").withNamespace(ns)
                .addToLabels(MANAGED, "true").endMetadata()
                .withNewSpec().withNewPodSelector().endPodSelector().withPolicyTypes("Ingress").endSpec().build();
        NetworkPolicy allow = new NetworkPolicyBuilder().withNewMetadata().withName("allow-ingress-controller-and-same-namespace")
                .withNamespace(ns).addToLabels(MANAGED, "true").endMetadata()
                .withNewSpec().withNewPodSelector().endPodSelector().withPolicyTypes("Ingress")
                .addNewIngress()
                .addNewFrom().withNewNamespaceSelector().addToMatchLabels("kubernetes.io/metadata.name", ingressNamespace).endNamespaceSelector().endFrom()
                .addNewFrom().withNewPodSelector().endPodSelector().endFrom()
                .endIngress().endSpec().build();
        return List.of(deny, allow);
    }

    /** Binds the app role to aio-deployer in one namespace (the token may bind only that role). */
    static void bindDeployer(KubernetesClient k8s, String ns) {
        if (k8s.rbac().roleBindings().inNamespace(ns).withName(BootstrapScript.ROLE_BINDING).get() != null) return;
        try {
            k8s.rbac().roleBindings().inNamespace(ns).resource(new RoleBindingBuilder()
                    .withNewMetadata().withName(BootstrapScript.ROLE_BINDING).withNamespace(ns)
                    .addToLabels("aio.platform/deployer", "true").addToLabels(MANAGED, "true").endMetadata()
                    .addNewSubject().withKind("ServiceAccount").withName(BootstrapScript.SERVICE_ACCOUNT).withNamespace(BootstrapScript.NAMESPACE).endSubject()
                    .withNewRoleRef().withApiGroup("rbac.authorization.k8s.io").withKind("ClusterRole").withName(BootstrapScript.APP_ROLE).endRoleRef()
                    .build()).create();
        } catch (KubernetesClientException e) {
            if (e.getCode() != 409) throw e;
        }
        // RBAC caches settle within a moment; the next calls in this namespace depend on the binding
        try { Thread.sleep(1500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    // ------------------------------------------------------------------ Argo CD project destinations

    /**
     * Allows the env namespace as a destination of the apps' Argo CD project (any cluster), so the new env's
     * Applications are accepted. Returns false (with a log line naming the manual fix) when not permitted.
     */
    public boolean allowDestination(String namespace) {
        String project = props.argocd() == null ? "apps" : props.argocd().project();
        try {
            GenericKubernetesResource p = hub.genericKubernetesResources(APP_PROJECT).inNamespace(ARGOCD_NS).withName(project).get();
            if (p == null) return false;
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = (Map<String, Object>) p.getAdditionalProperties().get("spec");
            List<Map<String, Object>> dests = J.list(spec, "destinations");
            boolean present = dests.stream().anyMatch(d -> namespace.equals(d.get("namespace"))
                    && ("*".equals(d.get("name")) || "*".equals(d.get("server"))));
            if (present || dests.stream().anyMatch(d -> "*".equals(d.get("namespace")) && "*".equals(d.get("server")))) return true;
            List<Object> updated = new ArrayList<>(dests);
            updated.add(Map.of("name", "*", "namespace", namespace));
            String patch = JSON.writeValueAsString(Map.of("spec", Map.of("destinations", updated)));
            hub.genericKubernetesResources(APP_PROJECT).inNamespace(ARGOCD_NS).withName(project)
                    .patch(io.fabric8.kubernetes.client.dsl.base.PatchContext.of(io.fabric8.kubernetes.client.dsl.base.PatchType.JSON_MERGE), patch);
            log.info("Argo CD project {} now allows namespace {}", project, namespace);
            return true;
        } catch (KubernetesClientException e) {
            log.warn("could not add namespace {} to Argo CD project {} ({}); add it by hand: kubectl -n argocd edit appproject {}",
                    namespace, project, e.getMessage(), project);
            return false;
        }
    }
}
