import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Run from the repository root with Java 21+, Helm, and SnakeYAML on the classpath. */
class HybridAutoscalingTest {
    static int checks;
    static Yaml yaml() { return new Yaml(new SafeConstructor(new LoaderOptions())); }
    public static void main(String[] args) throws Exception {
        var ordinary = render("--set", "image.tag=tested");
        check(kind(ordinary, "HorizontalPodAutoscaler").isEmpty(), "Autoscaling remains opt-in");
        check(at(one(ordinary, "Deployment"), "spec", "replicas").equals(1), "Manual replicas are preserved");

        var cpu = render("--set", "image.tag=tested", "--set", "autoscaling.enabled=true");
        var hpa = one(cpu, "HorizontalPodAutoscaler");
        List<?> metrics = (List<?>) at(hpa, "spec", "metrics");
        check(metrics.size() == 1 && at(metrics.getFirst(), "type").equals("ContainerResource")
                && at(metrics.getFirst(), "containerResource", "container").equals("app"), "CPU excludes the mesh sidecar");
        check(at(one(cpu, "Deployment"), "spec", "replicas") == null, "HPA owns desired replicas");

        var hybrid = render("--set", "image.tag=tested", "--set", "autoscaling.enabled=true", "--set", "autoscaling.istio.enabled=true",
                "--set", "nameOverride=owned-service", "--set-json", "autoscaling.istio.requestsPerSecondPerReplica=12.5");
        hpa = one(hybrid, "HorizontalPodAutoscaler");
        metrics = (List<?>) at(hpa, "spec", "metrics");
        check(metrics.size() == 2, "One HPA combines CPU and Istio metrics");
        Object traffic = metrics.get(1);
        check(at(traffic, "object", "describedObject", "kind").equals("Service")
                && at(traffic, "object", "describedObject", "name").equals("owned-service"), "Traffic metric binds to the owned Service");
        check(at(traffic, "object", "target", "type").equals("AverageValue")
                && at(traffic, "object", "target", "averageValue").equals("12.5"), "Traffic threshold is requests per replica, including fractional rates");
        check(at(hpa, "spec", "behavior", "scaleDown", "stabilizationWindowSeconds").equals(300), "Downscale stabilization is enabled");

        var stopped = render("--set", "image.tag=tested", "--set", "autoscaling.enabled=true", "--set", "autoscaling.istio.enabled=true", "--set", "scaledDown=true");
        check(kind(stopped, "HorizontalPodAutoscaler").isEmpty()
                && at(one(stopped, "Deployment"), "spec", "replicas").equals(0), "Stop removes the HPA and holds zero replicas");
        var placeholder = render("--set", "autoscaling.enabled=true");
        check(kind(placeholder, "HorizontalPodAutoscaler").isEmpty()
                && at(one(placeholder, "Deployment"), "spec", "replicas").equals(0), "Placeholder images cannot be autoscaled");
        var rollout = render("--set", "image.tag=tested", "--set", "autoscaling.enabled=true", "--set", "autoscaling.istio.enabled=true", "--set", "blueGreen.enabled=true");
        check(at(one(rollout, "HorizontalPodAutoscaler"), "spec", "scaleTargetRef", "kind").equals("Rollout")
                && at(one(rollout, "Rollout"), "spec", "replicas") == null, "Blue-green scaling targets the Rollout scale subresource");

        for (String setting : List.of("autoscaling.minReplicas=6,autoscaling.maxReplicas=5", "autoscaling.minReplicas=0",
                "autoscaling.maxReplicas=21", "autoscaling.targetCPUUtilizationPercentage=0",
                "autoscaling.targetCPUUtilizationPercentage=101", "autoscaling.istio.requestsPerSecondPerReplica=0",
                "autoscaling.istio.requestsPerSecondPerReplica=-1", "resources.requests.cpu=0", "resources.requests.cpu=null")) {
            String output = run(List.of("--set", "image.tag=tested", "--set", "autoscaling.enabled=true", "--set", setting), false);
            check(!output.isBlank(), "Invalid autoscaling settings are rejected: " + setting);
        }
        Map<?, ?> adapter = yaml().load(Files.readString(Path.of("ops/metrics/prometheus-adapter-istio.values.yaml")));
        Object rule = ((List<?>) at(adapter, "rules", "custom")).getFirst();
        check(at(rule, "resources", "overrides", "destination_service_namespace", "resource").equals("namespace")
                && at(rule, "resources", "overrides", "destination_service_name", "resource").equals("service"), "Adapter scopes traffic by namespace and Service");
        String query = (String) at(rule, "metricsQuery");
        check(query.contains("reporter=\"destination\"") && query.contains("<<.LabelMatchers>>")
                && !query.contains("vector(0)"), "Adapter avoids double-counting and does not disguise missing telemetry as zero");
        System.out.println(checks + " hybrid autoscaling contract checks passed");
    }
    static String run(List<String> arguments, boolean success) throws Exception {
        var command = new ArrayList<>(List.of("helm", "template", "hybrid-test", "helm/app", "--namespace", "tenant-dev"));
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if ((exit == 0) != success) throw new AssertionError("Unexpected Helm exit " + exit + ": " + output);
        return output;
    }
    static List<Map<?, ?>> render(String... arguments) throws Exception {
        var documents = new ArrayList<Map<?, ?>>();
        for (Object doc : yaml().loadAll(run(List.of(arguments), true))) if (doc instanceof Map<?, ?> mapping) documents.add(mapping);
        return documents;
    }
    static List<Map<?, ?>> kind(List<Map<?, ?>> documents, String kind) {
        return documents.stream().filter(doc -> kind.equals(doc.get("kind"))).toList();
    }
    static Map<?, ?> one(List<Map<?, ?>> documents, String kind) {
        var matches = kind(documents, kind);
        if (matches.size() != 1) throw new AssertionError("Expected one " + kind + ", got " + matches.size());
        return matches.getFirst();
    }
    static Object at(Object value, String... path) {
        for (String part : path) { if (!(value instanceof Map<?, ?> mapping)) return null; value = mapping.get(part); }
        return value;
    }
    static void check(boolean result, String message) { if (!result) throw new AssertionError(message); checks++; }
}
