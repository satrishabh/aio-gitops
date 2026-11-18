package com.aio.platform.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ClientsConfig {

    /** Uses in-cluster ServiceAccount when running in Kubernetes, ~/.kube/config locally. */
    @Bean(destroyMethod = "close")
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }

    @Bean
    public RestClient githubRestClient(AppProperties props) {
        RestClient.Builder b = RestClient.builder()
                .requestFactory(boundedFactory(HttpClient.newBuilder()))
                .baseUrl(props.github().apiUrl())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
        if (props.github().token() != null && !props.github().token().isBlank()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.github().token());
        }
        return b.build();
    }

    @Bean
    public RestClient argoRestClient(AppProperties props) throws Exception {
        HttpClient.Builder http = HttpClient.newBuilder();
        if (props.argocd().insecure()) {
            // argocd-server ships a self-signed cert in-cluster; only enable for the internal service URL
            requireInClusterOrLocal(props.argocd().url());
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new TrustAll()}, new SecureRandom());
            http.sslContext(ctx);
        }
        return RestClient.builder()
                .baseUrl(props.argocd().url())
                .requestFactory(boundedFactory(http))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.argocd().token())
                .build();
    }

    /** TLS verification may only be skipped for in-cluster (*.svc) or localhost ArgoCD URLs, never a public host. */
    static void requireInClusterOrLocal(String url) {
        String host = URI.create(url).getHost();
        if (host == null) throw new IllegalStateException("ARGOCD_URL has no host: " + url);
        boolean inCluster = host.endsWith(".svc") || host.endsWith(".svc.cluster.local");
        boolean local = host.equals("localhost") || host.equals("127.0.0.1");
        if (!inCluster && !local) {
            throw new IllegalStateException("ARGOCD_INSECURE=true is only allowed for in-cluster or localhost ARGOCD_URL, got host " + host);
        }
    }

    private static JdkClientHttpRequestFactory boundedFactory(HttpClient.Builder http) {
        var factory = new JdkClientHttpRequestFactory(http.connectTimeout(java.time.Duration.ofSeconds(5)).build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(20));
        return factory;
    }

    private static final class TrustAll extends X509ExtendedTrustManager {
        @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) {}
        @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) {}
        @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) {}
        @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) {}
        @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
        @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
