package com.aio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.aio.platform.domain.CiRun;
import com.aio.platform.repo.CiRunRepo;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "logging.level.org.hibernate.SQL=OFF", "spring.jpa.show-sql=false",
        "spring.datasource.url=jdbc:h2:mem:releasequeue;MODE=PostgreSQL;NON_KEYWORDS=TRIGGER"})
class BuildReleaseQueueTest {
    @Autowired CiRunRepo runs;
    @Autowired TestEntityManager entities;
    @Test void terminalBuildAndQueueStateSurviveReloadAndOnlyDueSuccessfulReleasesAreSelected() {
        CiRun due = run("INDEPENDENT", true, CiRun.SUCCEEDED, "READY"); runs.save(due);
        runs.save(run("LEGACY", true, CiRun.SUCCEEDED, "READY"));
        runs.save(run("INDEPENDENT", false, CiRun.SUCCEEDED, "READY"));
        runs.save(run("INDEPENDENT", true, CiRun.FAILED, "READY"));
        CiRun later = run("INDEPENDENT", true, CiRun.SUCCEEDED, "RETRY_WAIT");
        later.releaseNextAttempt = Instant.now().plusSeconds(300); runs.save(later);
        entities.flush(); entities.clear();
        assertThat(runs.dueReleases(Instant.now(), PageRequest.of(0, 20))).containsExactly(due.id);
        assertThat(runs.findById(due.id).orElseThrow().status).isEqualTo(CiRun.SUCCEEDED);
    }
    private CiRun run(String mode, boolean deploy, String status, String release) {
        CiRun r = new CiRun(); r.projectId = 1L; r.deliveryMode = mode; r.deploy = deploy; r.status = status; r.releaseStatus = release; return r;
    }
}
