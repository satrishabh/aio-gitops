package com.aio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aio.platform.domain.ClusterRecord;
import com.aio.platform.domain.Environment;
import com.aio.platform.repo.ClusterRecordRepo;
import com.aio.platform.repo.EnvironmentRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

/** First start after the upgrade: empty tables are seeded with exactly today's layout, once. */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "logging.level.org.hibernate.SQL=OFF", "spring.datasource.url=jdbc:h2:mem:envseed;MODE=PostgreSQL;NON_KEYWORDS=POSITION,VALUE",
        "spring.jpa.show-sql=false"})
class EnvironmentSeedTest {
    @Autowired EnvironmentRepo envs;
    @Autowired ClusterRecordRepo clusters;

    @Test
    void seedsTodaysStateOnceAndReadsItBack() {
        var registry = new EnvironmentRegistry(envs, clusters, EnvironmentsTest.liveProps(), "aks");

        assertThat(envs.findAllByOrderByPositionAsc()).extracting(e -> e.name).containsExactly("dev", "staging", "prod");
        Environment prod = envs.findById("prod").orElseThrow();
        assertThat(prod.promoteFrom).isEqualTo("staging");
        assertThat(prod.cluster).isEqualTo("azure");
        assertThat(prod.namespace).isEqualTo("prod");
        assertThat(prod.approvalRequired).isTrue();
        assertThat(prod.prodLike).isTrue();
        assertThat(prod.spotAllowed).isFalse();
        Environment dev = envs.findById("dev").orElseThrow();
        assertThat(dev.promoteFrom).isNull();
        assertThat(dev.approvalRequired).isFalse();
        assertThat(dev.spotAllowed).isTrue();
        assertThat(envs.findById("staging").orElseThrow().promoteFrom).isEqualTo("dev");

        ClusterRecord azure = clusters.findById("azure").orElseThrow();
        assertThat(azure.cloud).isEqualTo("aks");
        assertThat(azure.server).isEqualTo("https://kubernetes.default.svc");
        assertThat(azure.ingressClass).isEqualTo("aio-azure");
        assertThat(azure.ingressController).isEqualTo("traefik");
        assertThat(azure.ingressIp).isEqualTo("52.140.55.184");
        assertThat(azure.baseDomain).isEqualTo("52.140.55.184.nip.io");
        assertThat(azure.issuer).isEqualTo("letsencrypt-azure");
        assertThat(azure.tls).isFalse();
        assertThat(azure.rollouts).isNull();   // still detected on the hub, as before
        assertThat(azure.ingressNamespace).isNull();   // NetworkPolicies stay unmanaged until set

        // the registry answers like the old static config
        assertThat(registry.names()).containsExactly("dev", "staging", "prod");
        var view = new ClusterRegistry(registry, org.mockito.Mockito.mock(io.fabric8.kubernetes.client.KubernetesClient.class));
        assertThat(view.clusters()).hasSize(1);
        assertThat(view.clusterFor("staging").envs()).containsExactly("dev", "staging", "prod");

        // a second start (or a second instance) does not seed again, even if the configuration changed meanwhile
        envs.findById("staging").ifPresent(e -> { e.spotAllowed = false; envs.save(e); });
        new EnvironmentRegistry(envs, clusters, EnvironmentsTest.liveProps(), "gke");
        assertThat(envs.count()).isEqualTo(3);
        assertThat(clusters.count()).isEqualTo(1);
        assertThat(envs.findById("staging").orElseThrow().spotAllowed).isFalse();
        assertThat(clusters.findById("azure").orElseThrow().cloud).isEqualTo("aks");
    }
}
