package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Clusters → Add cluster: bootstrap script, Argo CD cluster Secret, connection tests, remove / rotate rules. */
class AddClusterTest {
    private static final String CA = Base64.getEncoder().encodeToString(
            "-----BEGIN CERTIFICATE-----\nMIIBfake\n-----END CERTIFICATE-----\n".getBytes(StandardCharsets.US_ASCII));
    private static final String TOKEN = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln";

    // ------------------------------------------------------------------ bootstrap script

    @Test
    void bootstrapScriptIsLeastPrivilegeIdempotentAndHasNoSecrets() {
        String s = BootstrapScript.render("oci-edge", "oke", "https://10.9.8.7:6443");
        assertTrue(s.startsWith("#!/usr/bin/env bash"));
        assertTrue(s.contains("set -euo pipefail"));
        assertTrue(s.contains("k apply -f -"), "declarative apply: re-runnable");
        assertTrue(s.contains("kind: ServiceAccount\nmetadata: { name: aio-deployer, namespace: aio-system }"));
        assertTrue(s.contains("type: kubernetes.io/service-account-token"));
        assertTrue(s.contains("resourceNames: [\"aio-deployer-apps\"]\n    verbs: [\"bind\"]"), "may bind only the app role");
        assertTrue(s.contains("kind: ClusterRoleBinding"));
        // the app role is never bound cluster-wide: the only ClusterRoleBinding targets aio-deployer
        assertEquals(1, s.split("kind: ClusterRoleBinding", -1).length - 1);
        assertTrue(s.contains("roleRef: { apiGroup: rbac.authorization.k8s.io, kind: ClusterRole, name: aio-deployer }"));
        // no wildcard rights, no escalate / impersonate, no cluster-admin
        assertFalse(s.contains("\"*\""));
        assertFalse(s.contains("escalate"));
        assertFalse(s.contains("impersonate"));
        assertFalse(s.contains("cluster-admin"));
        // prints token + CA as one JSON line on stdout; everything else on stderr
        assertTrue(s.contains("printf '{\"cluster\":\"%s\",\"server\":\"%s\",\"token\":\"%s\",\"caData\":\"%s\"}\\n'"));
        assertTrue(s.contains("jsonpath='{.data.ca\\.crt}'"));
        // revoke section
        assertTrue(s.contains("--revoke"));
        assertTrue(s.contains("kubectl delete clusterrolebinding aio-deployer"));
        assertTrue(s.contains("kubectl delete namespace aio-system"));
        // no secret material in the generated text
        assertFalse(s.matches("(?s).*eyJ[A-Za-z0-9_-]{10,}.*"));
        assertFalse(s.contains("BEGIN CERTIFICATE"));
        assertFalse(s.matches("(?s).*@[A-Z]+@.*"), "all placeholders replaced");
        assertEquals("aio-bootstrap-oci-edge.sh", BootstrapScript.fileName("oci-edge"));
    }

    @Test
    void bootstrapRejectsInjection() {
        assertThrows(IllegalArgumentException.class, () -> BootstrapScript.render("x; rm -rf /", "oke", ""));
        assertThrows(IllegalArgumentException.class, () -> BootstrapScript.render("edge", "azure-cloud", ""));
        assertThrows(IllegalArgumentException.class, () -> BootstrapScript.render("edge", "oke", "https://x\"; curl evil"));
        assertThrows(IllegalArgumentException.class, () -> BootstrapScript.render("edge", "oke", "http://plain"));
    }

    // ------------------------------------------------------------------ Argo CD cluster Secret

    @Test
    void argoClusterSecretHasTheClusterLabelBearerTokenAndCa() {
        Secret s = ClusterAccess.clusterSecret("oci-edge", "https://10.9.8.7:6443", new ClusterAccess.Credentials(TOKEN, CA), List.of("edge"));
        assertEquals("aio-cluster-oci-edge", s.getMetadata().getName());
        assertEquals("argocd", s.getMetadata().getNamespace());
        assertEquals("cluster", s.getMetadata().getLabels().get("argocd.argoproj.io/secret-type"));
        assertEquals("true", s.getMetadata().getLabels().get("aio.platform/managed"));
        Map<String, String> d = s.getStringData();
        assertEquals("oci-edge", d.get("name"));
        assertEquals("https://10.9.8.7:6443", d.get("server"));
        assertEquals("edge", d.get("namespaces"));
        assertEquals("false", d.get("clusterResources"));
        assertTrue(d.get("config").contains("\"bearerToken\":\"" + TOKEN + "\""));
        assertTrue(d.get("config").contains("\"caData\":\"" + CA + "\""));
        assertTrue(d.get("config").contains("\"insecure\":false"));
        assertFalse(ClusterAccess.clusterSecret("x", "https://y", new ClusterAccess.Credentials(TOKEN, CA), List.of()).getStringData().containsKey("namespaces"));
    }

    @Test
    void credentialsComeFromTheBootstrapLineAndAreValidated() {
        String line = "{\"cluster\":\"oci-edge\",\"server\":\"https://10.9.8.7:6443\",\"token\":\"" + TOKEN + "\",\"caData\":\"" + CA + "\"}";
        var req = request("oci-edge", null, null, line);
        var creds = ClusterAdminService.credentials(req, "oci-edge");
        assertEquals(TOKEN, creds.token());
        assertDoesNotThrow(creds::validate);
        assertThrows(IllegalArgumentException.class, () -> ClusterAdminService.credentials(req, "other"));
        assertThrows(IllegalArgumentException.class, () -> new ClusterAccess.Credentials("bad token!", CA).validate());
        assertThrows(IllegalArgumentException.class, () -> new ClusterAccess.Credentials(TOKEN, Base64.getEncoder().encodeToString("x".getBytes())).validate());
        assertThrows(IllegalArgumentException.class, () -> ClusterAccess.parseBootstrapLine("not json"));
    }

    // ------------------------------------------------------------------ connect / remove / rotate (mocked cluster + Argo CD)

    static ClusterAdminService.ClusterRequest request(String name, String token, String ca, String line) {
        return new ClusterAdminService.ClusterRequest(name, "oke", "https://10.9.8.7:6443", "nginx", null, null, "129.1.2.3",
                "129.1.2.3.nip.io", "letsencrypt", false, null, null, token, ca, line);
    }

    private record H(ClusterAdminService admin, EnvironmentRegistry envs, ClusterAccess access) {}

    private H harness() {
        EnvironmentRegistry envs = EnvironmentRegistry.fromConfig(EnvironmentsTest.liveProps(), "aks");
        ClusterAccess access = mock(ClusterAccess.class);
        var admin = new ClusterAdminService(envs, access, mock(AuditService.class), new ClusterRegistry(envs, mock(KubernetesClient.class)), true);
        return new H(admin, envs, access);
    }

    private static List<ClusterAccess.Check> allowed() {
        return List.of(new ClusterAccess.Check("Kubernetes API reachable", true, "v1.31"), new ClusterAccess.Check("can create namespaces", true, "allowed"));
    }

    @Test
    void connectCreatesTheArgoSecretAfterTheTestsAndStoresNoCredentials() {
        H h = harness();
        when(h.access().testKubernetes(eq("https://10.9.8.7:6443"), any())).thenReturn(allowed());
        when(h.access().testArgo("oci-edge")).thenReturn(new ClusterAccess.Check("Argo CD connection", null, "connects with the first app"));
        var res = h.admin().connect(request("oci-edge", TOKEN, CA, null), "admin");
        assertTrue(res.ok(), res.message());
        verify(h.access()).upsertSecret(eq("oci-edge"), eq("https://10.9.8.7:6443"), argThat(c -> TOKEN.equals(c.token())), eq(List.of()));
        var row = h.envs().cluster("oci-edge").orElseThrow();
        assertEquals("Connected", row.status);
        assertEquals("oke", row.cloud);
        assertEquals("nginx", row.ingressClass);
        assertEquals("ingress-nginx", row.ingressNamespace);   // default for the controller
        // the row type has no field that could hold a credential
        for (var f : com.aio.platform.domain.ClusterRecord.class.getFields())
            assertFalse(f.getName().toLowerCase().matches(".*(token|ca|secret|password|cert).*"), f.getName());
        assertThrows(IllegalStateException.class, () -> h.admin().connect(request("oci-edge", TOKEN, CA, null), "admin"));
    }

    @Test
    void failedChecksStoreNothingAndRestoreTheSecret() {
        H h = harness();
        when(h.access().testKubernetes(any(), any())).thenReturn(List.of(new ClusterAccess.Check("Kubernetes API reachable", false, "timeout")));
        var res = h.admin().connect(request("oci-edge", TOKEN, CA, null), "admin");
        assertFalse(res.ok());
        verify(h.access(), never()).upsertSecret(any(), any(), any(), any());
        assertTrue(h.envs().cluster("oci-edge").isEmpty());

        when(h.access().testKubernetes(any(), any())).thenReturn(List.of(
                new ClusterAccess.Check("Kubernetes API reachable", true, "v1.31"), new ClusterAccess.Check("can bind clusterroles", false, "denied")));
        assertFalse(h.admin().connect(request("oci-edge", TOKEN, CA, null), "admin").ok());
        verify(h.access(), never()).upsertSecret(any(), any(), any(), any());

        when(h.access().testKubernetes(any(), any())).thenReturn(allowed());
        Secret previous = new Secret();
        when(h.access().upsertSecret(any(), any(), any(), any())).thenReturn(previous);
        when(h.access().testArgo("oci-edge")).thenReturn(new ClusterAccess.Check("Argo CD connection", false, "x509: unknown authority"));
        assertFalse(h.admin().connect(request("oci-edge", TOKEN, CA, null), "admin").ok());
        verify(h.access()).restoreSecret("oci-edge", previous);
        assertTrue(h.envs().cluster("oci-edge").isEmpty());
    }

    @Test
    void removeIsRefusedWhileAnEnvironmentUsesTheCluster() {
        H h = harness();
        when(h.access().testKubernetes(any(), any())).thenReturn(allowed());
        when(h.access().testArgo(any())).thenReturn(new ClusterAccess.Check("Argo CD connection", true, "Successful"));
        h.admin().connect(request("oci-edge", TOKEN, CA, null), "admin");
        var e = new com.aio.platform.domain.Environment();
        e.name = "edge"; e.position = 4; e.promoteFrom = "prod"; e.cluster = "oci-edge"; e.namespace = "edge"; e.prodLike = true;
        h.envs().save(e);

        var err = assertThrows(IllegalStateException.class, () -> h.admin().remove("oci-edge", "admin"));
        assertTrue(err.getMessage().contains("edge"));
        assertThrows(IllegalStateException.class, () -> h.admin().remove("azure", "admin"));
        verify(h.access(), never()).deleteSecret(any());

        h.envs().delete("edge");
        when(h.access().deleteSecret("oci-edge")).thenReturn(true);
        assertEquals(true, h.admin().remove("oci-edge", "admin").get("argoSecretDeleted"));
        assertTrue(h.envs().cluster("oci-edge").isEmpty());
    }

    @Test
    void rotateKeepsTheEnvNamespacesAndIsNotOfferedForTheHub() {
        H h = harness();
        when(h.access().testKubernetes(any(), any())).thenReturn(allowed());
        when(h.access().testArgo(any())).thenReturn(new ClusterAccess.Check("Argo CD connection", true, "Successful"));
        h.admin().connect(request("oci-edge", TOKEN, CA, null), "admin");
        var e = new com.aio.platform.domain.Environment();
        e.name = "edge"; e.position = 4; e.promoteFrom = "prod"; e.cluster = "oci-edge"; e.namespace = "edge-ns";
        h.envs().save(e);
        assertTrue(h.admin().rotate("oci-edge", request("oci-edge", TOKEN, CA, null), "admin").ok());
        verify(h.access()).upsertSecret(eq("oci-edge"), any(), any(), eq(List.of("edge-ns")));
        assertThrows(IllegalArgumentException.class, () -> h.admin().rotate("azure", request("azure", TOKEN, CA, null), "admin"));
        assertTrue(h.admin().bootstrap(new ClusterAdminService.ClusterRequest("oci-edge", null, null, null, null, null, null, null, null, null, null, null, null, null, null), true)
                .get("script").contains("aio-bootstrap-oci-edge.sh"));
    }

    @Test
    void describeValidatesStepOne() {
        assertThrows(IllegalArgumentException.class, () -> ClusterAdminService.describe(request("Bad_Name", null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> ClusterAdminService.describe(new ClusterAdminService.ClusterRequest(
                "edge", "oke", "http://x", null, null, null, null, null, null, null, null, null, null, null, null)));
        assertThrows(IllegalStateException.class, () -> ClusterAdminService.describe(new ClusterAdminService.ClusterRequest(
                "edge", "oke", "https://x", "haproxy", null, null, null, null, null, null, null, null, null, null, null)));
        var c = ClusterAdminService.describe(new ClusterAdminService.ClusterRequest(
                "edge", "OKE", "https://x:6443/", "traefik", null, null, null, "Apps.Example.COM", null, true, null, null, null, null, null));
        assertEquals("oke", c.cloud);
        assertEquals("https://x:6443", c.server);
        assertEquals("traefik", c.ingressClass);
        assertEquals("traefik", c.ingressNamespace);
        assertEquals("apps.example.com", c.baseDomain);
        assertTrue(c.tls);
    }

    @Test
    void networkPoliciesMatchTheHubBaseline() {
        var nps = ClusterAccess.networkPolicies("edge", "ingress-nginx");
        assertEquals(List.of("default-deny-ingress", "allow-ingress-controller-and-same-namespace"),
                nps.stream().map(n -> n.getMetadata().getName()).toList());
        var from = nps.get(1).getSpec().getIngress().get(0).getFrom();
        assertEquals("ingress-nginx", from.get(0).getNamespaceSelector().getMatchLabels().get("kubernetes.io/metadata.name"));
        assertNotNull(from.get(1).getPodSelector());
        assertEquals(List.of("Ingress"), nps.get(0).getSpec().getPolicyTypes());
    }
}
