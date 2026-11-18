import java.nio.charset.StandardCharsets;
import java.util.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Run from the repository root with Java 21+, Helm and SnakeYAML. */
class PortablePlatformTest {
    static int checks;
    static String render(boolean success, String... extra) throws Exception {
        var args = new ArrayList<>(List.of("helm", "template", "customer-aio", "helm/aio-platform",
                "-f", "helm/aio-platform/values-example.yaml", "--namespace", "aio-platform"));
        args.addAll(List.of(extra));
        var process = new ProcessBuilder(args).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if ((process.waitFor() == 0) != success) throw new AssertionError(output);
        return output;
    }
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    public static void main(String[] args) throws Exception {
        var output = render(true);
        var yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        var docs = new ArrayList<Map<String, Object>>();
        yaml.loadAll(output).forEach(doc -> { if (doc != null) docs.add(map(doc)); });
        var deployment = docs.stream().filter(d -> d.get("kind").equals("Deployment")).findFirst().orElseThrow();
        var spec = map(deployment.get("spec"));
        check(spec.get("replicas").equals(1), "Platform must stay single replica");
        var pod = map(map(spec.get("template")).get("spec"));
        var container = map(((List<?>) pod.get("containers")).getFirst());
        check(container.get("image").toString().contains("@sha256:"), "Image must use a digest");
        check(container.containsKey("startupProbe"), "Slow JVM startup must not trigger liveness restarts");
        var env = (List<?>) container.get("env");
        check(env.stream().filter(e -> map(e).containsKey("valueFrom")).count() == 6,
                "Runtime credentials must reference existing Secret keys");
        check(!output.contains("sarvaikam") && !output.contains("gke.io") && !output.contains("docker.pkg.dev"),
                "Portable render must not inherit production/provider endpoints");
        check(output.contains("ingressClassName: traefik") && !output.contains("nginx.ingress.kubernetes.io"),
                "Ingress must use operator-selected class and annotations");
        check(!render(true, "--set", "ingress.enabled=false").contains("kind: Ingress"), "Ingress must be optional");
        check(!render(true, "--set", "serviceAccount.create=false").contains("kind: ServiceAccount"),
                "Existing service account must be supported");
        check(render(false, "--set", "image.digest=latest").contains("immutable sha256"), "Mutable image rejected");
        check(render(false, "--set", "config.OIDC_ISSUER_URI=http://identity.local").contains("must use HTTPS"),
                "Plaintext identity rejected");
        check(render(false, "--set", "config.DB_PASSWORD=bad").contains("existingSecret"), "Inline password rejected");
        check(render(false, "--set", "config.GITOPS_OWNER=").contains("is required"), "Missing tenant config rejected");
        check(render(false, "--set", "ingress.tlsSecretName=").contains("is required"), "Missing ingress TLS rejected");
        System.out.println(checks + " portable platform contract checks passed");
    }
}
