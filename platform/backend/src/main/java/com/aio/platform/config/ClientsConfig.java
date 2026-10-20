package com.aio.platform.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.net.Socket;
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
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new TrustAll()}, new SecureRandom());
            http.sslContext(ctx);
        }
        return RestClient.builder()
                .baseUrl(props.argocd().url())
                .requestFactory(new JdkClientHttpRequestFactory(http.build()))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.argocd().token())
                .build();
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
