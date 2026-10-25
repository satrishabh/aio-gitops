package com.aio.platform.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TemplateServiceTest {

    @Test
    void detectsViteReactAndGeneratesNginxDockerfile() {
        String pkg = "{\"scripts\":{\"build\":\"vite build\"},\"dependencies\":{\"react\":\"18\"},\"devDependencies\":{\"vite\":\"5\"}}";
        var s = TemplateService.detect(List.of("package.json", "package-lock.json", "src"), pkg);
        assertEquals("React + Vite", s.label());
        assertEquals("dist", s.outDir());
        var files = TemplateService.generatedFiles(s, List.of("package.json"));
        assertTrue(files.get("Dockerfile").contains("RUN npm ci && npm run build"));
        assertTrue(files.get("Dockerfile").contains("COPY --from=build /src/dist /usr/share/nginx/html"));
        assertTrue(files.get(".aio/nginx.conf").contains("location = /health"));
        assertTrue(files.containsKey(".dockerignore"));
    }

    @Test
    void detectsCraWithPnpmAndStaticHtml() {
        String pkg = "{\"scripts\":{\"build\":\"react-scripts build\"},\"dependencies\":{\"react-scripts\":\"5\"}}";
        var cra = TemplateService.detect(List.of("package.json", "pnpm-lock.yaml"), pkg);
        assertEquals("build", cra.outDir());
        assertTrue(cra.install().startsWith("corepack enable && pnpm"));
        var html = TemplateService.detect(List.of("index.html", "css"), null);
        assertEquals(".", html.outDir());
        assertTrue(TemplateService.generatedFiles(html, List.of()).get("Dockerfile").contains("COPY . /usr/share/nginx/html"));
    }

    @Test
    void serverStacksAreNotAutoContainerized() {
        assertTrue(TemplateService.generatedFiles(TemplateService.detect(List.of("pom.xml"), null), List.of()).isEmpty());
        var next = TemplateService.detect(List.of("package.json"), "{\"scripts\":{\"build\":\"next build\"},\"dependencies\":{\"next\":\"15\"}}");
        assertEquals("Next.js", next.label());
        assertTrue(TemplateService.generatedFiles(next, List.of()).isEmpty());
    }

    @Test
    void communityQueryStripsQualifiersAndValidates() {
        String q = TemplateService.communityQuery("ui-dashboard", "tailwind user:evil <script>", "TypeScript");
        assertEquals("tailwind user evil script dashboard language:TypeScript template:true archived:false stars:>10", q);
        assertThrows(IllegalArgumentException.class, () -> TemplateService.communityQuery("nope", null, null));
        assertThrows(IllegalArgumentException.class, () -> TemplateService.communityQuery(null, null, "a b"));
    }

    @Test
    void templateNameValidation() {
        assertArrayEquals(new String[]{"satrishabh", "aio-template-go-api"}, TemplateService.split("satrishabh/aio-template-go-api"));
        assertThrows(IllegalArgumentException.class, () -> TemplateService.split("a/../b"));
        assertThrows(IllegalArgumentException.class, () -> TemplateService.split("no-slash"));
        assertThrows(IllegalArgumentException.class, () -> TemplateService.split("o/r?x=1"));
    }
}
