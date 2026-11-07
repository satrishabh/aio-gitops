package com.aio.platform.security;

import com.aio.platform.domain.*;
import com.aio.platform.repo.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop", "logging.level.org.hibernate.SQL=OFF", "debug=false",
    "spring.datasource.url=jdbc:h2:mem:isolation;MODE=PostgreSQL;NON_KEYWORDS=TRIGGER", "spring.jpa.show-sql=false"})
class ScopedRepositoryTest {
    @Autowired ProjectRepo projects;
    @Autowired CiRunRepo runs;
    @Autowired DeploymentRepo deployments;
    @Autowired PromotionRepo promotions;
    @Autowired ScanRunRepo scans;
    @Autowired AuditRepo audit;
    @Test void ownershipQueriesFilterBeforePaginationAndDoNotLeakOtherUsers() {
        Project alice = new Project(); alice.name = "alice-app"; alice.ownerSubject = "issuer|alice"; projects.save(alice);
        Project bob = new Project(); bob.name = "bob-app"; bob.ownerSubject = "issuer|bob"; projects.save(bob);
        CiRun own = new CiRun(); own.projectId = alice.id; runs.save(own);
        for (int i = 0; i < 3; i++) { CiRun foreign = new CiRun(); foreign.projectId = bob.id; runs.save(foreign); }
        var page = PageRequest.of(0, 1);
        assertThat(runs.findByProjectIdInOrderByIdDesc(List.of(alice.id), page)).extracting(r -> r.id).containsExactly(own.id);
        assertThat(runs.findByProjectIdInOrderByIdDesc(List.of(), page)).isEmpty();
        assertThat(projects.findByOwnerSubjectOrderByNameAsc("issuer|alice")).extracting(p -> p.id).containsExactly(alice.id);
        Deployment d = new Deployment(); d.projectId = bob.id; d.deployedAt = Instant.now(); deployments.save(d);
        assertThat(deployments.findByProjectIdInAndDeployedAtAfter(List.of(alice.id), Instant.EPOCH)).isEmpty();
        Promotion promotion = new Promotion(); promotion.projectId = bob.id; promotion.status = "PENDING"; promotions.save(promotion);
        assertThat(promotions.findVisible(List.of(alice.id), "PENDING", page)).isEmpty();
        assertThat(promotions.findVisible(List.of(bob.id), null, page)).hasSize(1);
        ScanRun mine = new ScanRun(); mine.ownerSubject = "issuer|alice"; scans.save(mine);
        ScanRun theirs = new ScanRun(); theirs.ownerSubject = "issuer|bob"; scans.save(theirs);
        assertThat(scans.findVisible(List.of(), "issuer|alice", page)).extracting(s -> s.id).containsExactly(mine.id);
        AuditEvent event = new AuditEvent(); event.actor = "same-display-name"; event.actorSubject = "issuer|alice"; audit.save(event);
        AuditEvent other = new AuditEvent(); other.actor = "same-display-name"; other.actorSubject = "issuer|bob"; audit.save(other);
        assertThat(audit.findTop300ByActorSubjectOrderByIdDesc("issuer|alice")).extracting(a -> a.id).containsExactly(event.id);
    }
}
