package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildPipelineContractTest {
    @Test void buildPipelineHasStableResultsAndNoDeploymentTaskOrGitopsInputs() throws Exception {
        String source;
        try (var contract = getClass().getResourceAsStream("/contracts/ci-build-v2.yaml")) {
            assertNotNull(contract, "The build contract must be available inside the Docker build context");
            source = new String(contract.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        Path pipeline = Path.of("../../tekton/pipelines/ci-build-v2.yaml");
        if (Files.exists(pipeline)) assertEquals(source.replace("\r\n", "\n"), Files.readString(pipeline).replace("\r\n", "\n"),
                "The packaged contract must match the deployed pipeline");
        Map<?, ?> document = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions())).load(source);
        Map<?, ?> spec = (Map<?, ?>) document.get("spec");
        List<?> tasks = (List<?>) spec.get("tasks");
        assertEquals(List.of("clone", "test", "build-push", "scan"), tasks.stream().map(t -> ((Map<?, ?>) t).get("name")).toList());
        assertFalse(source.contains("gitops"));
        assertEquals(4, ((List<?>) spec.get("results")).size());
        Map<?, ?> scan = (Map<?, ?>) tasks.get(3);
        assertEquals("scanconfig", ((Map<?, ?>) ((List<?>) scan.get("workspaces")).getFirst()).get("workspace"));
        assertTrue(source.contains("@$(tasks.build-push.results.IMAGE_DIGEST)"));
    }
}
