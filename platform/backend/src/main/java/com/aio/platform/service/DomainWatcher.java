package com.aio.platform.service;

import com.aio.platform.domain.Domain;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.DomainRepo;
import com.aio.platform.repo.ProjectRepo;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Moves domains through PENDING_DNS -> ISSUING -> READY:
 * <ol>
 *   <li>PENDING_DNS: waits until the host resolves to the cluster's ingress IP (or, without a known IP, resolves at
 *       all), then renders the route so cert-manager can answer the HTTP-01 challenge;</li>
 *   <li>ISSUING / READY: reads the cert-manager Certificate through Argo CD (the platform has no workload-cluster
 *       credentials) and records readiness, expiry or the failure reason.</li>
 * </ol>
 */
@Service
public class DomainWatcher {
    @org.springframework.beans.factory.annotation.Value("${app.runtime.workers-enabled:true}")
    boolean workersEnabled = true;

    private static final Logger log = LoggerFactory.getLogger(DomainWatcher.class);
    static final Duration READY_RECHECK = Duration.ofHours(6);

    /** Resolves A records (following CNAMEs); swapped out in tests. */
    public interface Resolver { List<String> addresses(String host); }

    private final DomainRepo domains;
    private final ProjectRepo projects;
    private final DomainService service;
    private final ArgoCdService argo;
    Resolver resolver = DomainWatcher::lookup;

    public DomainWatcher(DomainRepo domains, ProjectRepo projects, DomainService service, ArgoCdService argo) {
        this.domains = domains;
        this.projects = projects;
        this.service = service;
        this.argo = argo;
    }

    @Scheduled(fixedDelayString = "${app.watch.domains-ms:60000}", initialDelayString = "${app.watch.domains-initial-ms:45000}")
    public void poll() {
        if (!workersEnabled) return;
        for (Domain d : domains.findByStatusIn(Set.of(Domain.PENDING_DNS, Domain.ISSUING, Domain.READY, Domain.FAILED))) {
            if (Domain.READY.equals(d.status) && d.lastCheckedAt != null && d.lastCheckedAt.plus(READY_RECHECK).isAfter(Instant.now())) continue;
            try {
                check(d);
            } catch (RuntimeException e) {
                log.debug("domain check {} failed: {}", d.host, e.getMessage());
            }
        }
    }

    /** One check, also used by the "Check now" button. */
    public Domain check(Domain d) {
        Project p = projects.findById(d.projectId).orElseThrow();
        boolean wasRoutable = d.routable();
        d.lastCheckedAt = Instant.now();
        if (Domain.PENDING_DNS.equals(d.status)) checkDns(d);
        else checkCertificate(p, d);
        if (d.routable() != wasRoutable) service.statusChanged(d, d.host + " " + d.status.toLowerCase());
        else domains.save(d);
        return d;
    }

    void checkDns(Domain d) {
        List<String> ips = resolver.addresses(d.host);
        DnsResult r = dnsResult(ips, d.dnsExpected);
        d.message = r.message();
        if (r.ok()) d.status = Domain.ISSUING;
    }

    record DnsResult(boolean ok, String message) {}

    static DnsResult dnsResult(List<String> ips, String expected) {
        if (ips.isEmpty()) return new DnsResult(false, "No DNS record yet" + (expected == null ? "" : " - add an A record pointing to " + expected));
        if (expected == null) return new DnsResult(true, "DNS resolves to " + String.join(", ", ips) + " - requesting certificate");
        if (ips.contains(expected)) return new DnsResult(true, "DNS points to the cluster - requesting certificate");
        return new DnsResult(false, "DNS points to " + String.join(", ", ips) + ", expected " + expected
                + " (changes can take a while to propagate)");
    }

    void checkCertificate(Project p, Domain d) {
        Domain owner = domains.findByHostOrderByIdAsc(d.host).stream().filter(x -> x.certOwner).findFirst().orElse(d);
        Project ownerProject = owner.projectId.equals(p.id) ? p : projects.findById(owner.projectId).orElse(p);
        String secret = secretName(d.host);
        Map<String, Object> cert = argo.resource(ArgoCdService.appName(ownerProject, d.env),
                new ArgoCdService.Ref(ClusterService.namespace(ownerProject, d.env), "cert-manager.io", "v1", "Certificate", secret));
        CertResult r = certResult(cert);
        d.message = r.message();
        if (r.ready()) {
            if (!Domain.READY.equals(d.status)) d.readyAt = Instant.now();
            d.status = Domain.READY;
            d.certExpiresAt = r.notAfter();
        } else {
            // FAILED is shown loudly but stays routable: cert-manager retries with backoff through the same Ingress.
            // READY -> ISSUING covers renewals and a certificate that went missing.
            d.status = r.failed() ? Domain.FAILED : Domain.ISSUING;
        }
    }

    record CertResult(boolean ready, boolean failed, String message, Instant notAfter) {}

    static CertResult certResult(Map<String, Object> cert) {
        if (cert == null) return new CertResult(false, false,
                "Waiting for cert-manager to create the certificate (is cert-manager installed on the cluster?)", null);
        Instant notAfter = J.time(cert.get("status"), "notAfter");
        String readyMsg = null, issuingMsg = null;
        boolean failed = false;
        for (Map<String, Object> c : J.list(cert.get("status"), "conditions")) {
            if ("Ready".equals(c.get("type"))) {
                if ("True".equals(c.get("status"))) return new CertResult(true, false,
                        "Certificate issued" + (notAfter == null ? "" : ", valid until " + notAfter.toString().substring(0, 10)), notAfter);
                readyMsg = J.str(c, "message");
            }
            // cert-manager reports a failed issuance on the Issuing condition (reason Failed); it retries with backoff
            if ("Issuing".equals(c.get("type")) && "Failed".equals(c.get("reason"))) {
                failed = true;
                issuingMsg = J.str(c, "message");
            }
        }
        String msg = failed ? "Issuance failed, retrying: " + (issuingMsg == null ? readyMsg : issuingMsg)
                : readyMsg == null ? "Issuing certificate" : readyMsg;
        return new CertResult(false, failed, msg, notAfter);
    }

    /** Same naming as the chart: tls-<host with dots as dashes>, max 63 chars. */
    static String secretName(String host) {
        String s = "tls-" + host.replace('.', '-');
        if (s.length() > 63) s = s.substring(0, 63);
        return s.replaceAll("-+$", "");
    }

    /** DNS lookup through JNDI (A records; CNAMEs are followed by the system resolver). */
    static List<String> lookup(String host) {
        List<String> out = new ArrayList<>();
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "3000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            var ctx = new InitialDirContext(env);
            Attribute a = ctx.getAttributes(host, new String[]{"A"}).get("A");
            if (a != null) for (int i = 0; i < a.size(); i++) out.add(String.valueOf(a.get(i)));
            ctx.close();
        } catch (Exception e) {
            // NXDOMAIN / timeout: treated as "no record yet"
        }
        if (out.isEmpty()) {
            try {
                for (var addr : java.net.InetAddress.getAllByName(host))
                    if (addr instanceof java.net.Inet4Address) out.add(addr.getHostAddress());
            } catch (java.net.UnknownHostException e) {
                // no record
            }
        }
        return out.stream().distinct().toList();
    }
}
