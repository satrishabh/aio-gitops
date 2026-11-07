package com.aio.platform.service;

import com.aio.platform.config.AppProperties;
import com.aio.platform.domain.Domain;
import com.aio.platform.domain.Project;
import com.aio.platform.repo.DomainRepo;
import com.aio.platform.repo.ProjectRepo;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Custom domains and path routes. A route ({@code host + path}) belongs to one project's env; several projects
 * may share a host with different paths (ingress-nginx merges them; they all live in the env's shared namespace).
 *
 * <p>Routes are rendered into each project's {@code routes:} values block. Only routes whose DNS already points
 * at the cluster are rendered (cert-manager's HTTP-01 challenge needs a reachable Ingress), and exactly one route
 * per host - the {@code certOwner} - asks cert-manager for the host's certificate; the others reuse its secret.
 */
@Service
public class DomainService {
    private static final Logger log = LoggerFactory.getLogger(DomainService.class);
    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern PATH = Pattern.compile("/|(/[A-Za-z0-9._~-]+)+");
    static final int MAX_PER_PROJECT = 50;

    private final DomainRepo domains;
    private final ProjectRepo projects;
    private final GitOpsService gitops;
    private final ClusterRegistry clusters;
    private final AppProperties props;
    private final AuditService audit;

    public DomainService(DomainRepo domains, ProjectRepo projects, GitOpsService gitops, ClusterRegistry clusters,
                         AppProperties props, AuditService audit) {
        this.domains = domains;
        this.projects = projects;
        this.gitops = gitops;
        this.clusters = clusters;
        this.props = props;
        this.audit = audit;
    }

    public record AddRequest(String env, String host, String path, Boolean stripPrefix, Boolean forceHttps, Boolean wwwRedirect) {}

    // ------------------------------------------------------------------ validation (pure, unit tested)

    /** Lower-cased FQDN without trailing dot; rejects wildcards, IPs, single labels and bad characters. */
    static String normalizeHost(String raw) {
        String h = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("http://") || h.startsWith("https://")) h = URI.create(h).getHost() == null ? "" : URI.create(h).getHost();
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.isEmpty() || h.length() > 253) throw new IllegalArgumentException("enter a host name like shop.example.com");
        if (h.contains("*")) throw new IllegalArgumentException("wildcard hosts are not supported - add each host separately");
        if (h.matches("[0-9.]+") || h.contains(":")) throw new IllegalArgumentException("use a host name, not an IP address");
        String[] labels = h.split("\\.", -1);
        if (labels.length < 2) throw new IllegalArgumentException("use a fully qualified host name like shop.example.com");
        for (String l : labels)
            if (!LABEL.matcher(l).matches()) throw new IllegalArgumentException("invalid host name: " + h);
        if (h.startsWith("www.")) throw new IllegalArgumentException("add the apex host and tick 'redirect www' instead of adding www. directly");
        return h;
    }

    /** "/", or "/a/b" (no trailing slash, no regex characters, no dot segments). */
    static String normalizePath(String raw) {
        String p = raw == null || raw.isBlank() ? "/" : raw.trim();
        if (!p.startsWith("/")) p = "/" + p;
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.length() > 200 || !PATH.matcher(p).matches() || p.contains("/./") || p.contains("/../") || p.endsWith("/..") || p.endsWith("/."))
            throw new IllegalArgumentException("path must look like / or /api/v1 (letters, digits, . _ ~ -)");
        return p;
    }

    /** Hosts the platform owns: its own UI and the generated <project>-<env>.<baseDomain> names. */
    void rejectReserved(String host) {
        String own = props.publicUrl() == null || props.publicUrl().isBlank() ? null : URI.create(props.publicUrl()).getHost();
        if (host.equals(own)) throw new IllegalArgumentException(host + " is the platform's own address");
        for (var c : clusters.clusters())
            if (c.baseDomain() != null && ClusterRegistry.inZone(c.baseDomain().toLowerCase(Locale.ROOT), host))
                throw new IllegalArgumentException(host + " is inside " + c.baseDomain() + ", which the platform uses for generated app hosts");
    }

    // ------------------------------------------------------------------ add / remove

    public List<Domain> list(Project p) {
        return domains.findByProjectIdOrderByHostAscPathAsc(p.id);
    }

    public Domain add(Project p, AddRequest req, String actor) {
        if (req == null || req.env() == null || !GitOpsService.ENVS.contains(req.env())) throw new IllegalArgumentException("choose dev, staging or prod");
        String host = normalizeHost(req.host());
        String path = normalizePath(req.path());
        rejectReserved(host);
        if (domains.findByProjectIdOrderByHostAscPathAsc(p.id).size() >= MAX_PER_PROJECT)
            throw new IllegalArgumentException("at most " + MAX_PER_PROJECT + " domains per project");
        domains.findByHostAndPath(host, path).ifPresent(d -> {
            throw new IllegalStateException(host + path + " is already routed to " + projectName(d.projectId) + " (" + d.env + ")");
        });
        List<Domain> sameHost = domains.findByHostOrderByIdAsc(host);
        // A host resolves to one cluster, and its certificate secret lives in one namespace: one env per host
        sameHost.stream().filter(d -> !d.env.equals(req.env())).findFirst().ifPresent(d -> {
            throw new IllegalStateException(host + " already serves " + d.env + " (" + projectName(d.projectId) + "); one host can only route to one environment");
        });

        Domain d = new Domain();
        d.projectId = p.id;
        d.env = req.env();
        d.host = host;
        d.path = path;
        d.stripPrefix = Boolean.TRUE.equals(req.stripPrefix()) && !"/".equals(path);
        d.forceHttps = req.forceHttps() == null || req.forceHttps();
        // www handling is per host (the certificate covers www only once): follow the existing routes
        d.wwwRedirect = sameHost.isEmpty() ? Boolean.TRUE.equals(req.wwwRedirect()) : sameHost.get(0).wwwRedirect;
        d.issuer = clusters.issuerFor(d.env, host);
        d.dnsExpected = clusters.ingressIpFor(d.env);
        d.createdBy = actor;
        // In the managed zone external-dns creates the record from the Ingress itself, so render it right away
        d.status = clusters.managedDns(d.env, host) ? Domain.ISSUING : Domain.PENDING_DNS;
        d.message = Domain.ISSUING.equals(d.status) ? "DNS record and certificate are being created"
                : "Create the DNS record shown, then the certificate is requested automatically";
        d = domains.save(d);
        audit.record(actor, "domain.add", p.name + "/" + d.env, d.host + d.path);
        if (d.routable()) syncHost(host, "add " + host + path);
        return d;
    }

    public void remove(Project p, Long domainId, String actor) {
        Domain d = domains.findById(domainId).orElseThrow();
        if (!d.projectId.equals(p.id)) throw new java.util.NoSuchElementException();
        domains.delete(d);
        audit.record(actor, "domain.remove", p.name + "/" + d.env, d.host + d.path);
        // Re-render this project (route gone) and hand the certificate to another route on the host if needed
        var touched = new java.util.LinkedHashMap<Long, Project>();
        touched.put(p.id, p);
        reassignOwner(d.host).forEach(t -> touched.putIfAbsent(t.id, t));
        for (Project t : touched.values()) render(t, d.env, "remove " + d.host + d.path);
    }

    /** Marks a route routable/unroutable after a status change and re-renders everything sharing its host. */
    public void statusChanged(Domain d, String why) {
        domains.save(d);
        syncHost(d.host, why);
    }

    // ------------------------------------------------------------------ rendering

    /** Recomputes the certificate owner for a host and re-renders every project serving it. */
    void syncHost(String host, String why) {
        var touched = new java.util.LinkedHashMap<Long, Project>();
        reassignOwner(host).forEach(t -> touched.putIfAbsent(t.id, t));
        List<Domain> all = domains.findByHostOrderByIdAsc(host);
        for (Domain d : all) if (!touched.containsKey(d.projectId)) projects.findById(d.projectId).ifPresent(t -> touched.put(t.id, t));
        if (all.isEmpty()) return;
        for (Project t : touched.values()) render(t, all.get(0).env, why);
    }

    /** Oldest routable route of the host owns the certificate; returns projects whose ownership flag changed. */
    List<Project> reassignOwner(String host) {
        List<Domain> all = domains.findByHostOrderByIdAsc(host);
        Optional<Domain> owner = chooseOwner(all);
        List<Project> changed = new ArrayList<>();
        for (Domain d : all) {
            boolean should = owner.isPresent() && owner.get().id.equals(d.id);
            if (d.certOwner != should) {
                d.certOwner = should;
                domains.save(d);
                projects.findById(d.projectId).ifPresent(changed::add);
            }
        }
        return changed;
    }

    static Optional<Domain> chooseOwner(List<Domain> sameHost) {
        return sameHost.stream().filter(Domain::routable).min(Comparator.comparing((Domain d) -> d.id));
    }

    /** Writes one project's routes: block for an env (only routable routes). */
    void render(Project p, String env, String why) {
        String block = routesBlock(domains.findByProjectIdAndEnvOrderByHostAscPathAsc(p.id, env).stream()
                .filter(Domain::routable).toList(), clusters, env);
        gitops.applyRoutes(p, env, block, "platform: routes " + p.name + " " + env + " - " + why);
    }

    static String routesBlock(List<Domain> routes, ClusterRegistry clusters, String env) {
        if (routes.isEmpty()) return "routes: []\n";
        StringBuilder b = new StringBuilder("routes:\n");
        for (Domain d : routes) {
            b.append("  - host: ").append(d.host).append('\n')
             .append("    path: \"").append(d.path).append("\"\n")
             .append("    tls: true\n")
             .append("    issuer: ").append(d.issuer == null ? ClusterRegistry.DEFAULT_ISSUER : d.issuer).append('\n')
             .append("    certOwner: ").append(d.certOwner).append('\n')
             .append("    forceHttps: ").append(d.forceHttps).append('\n')
             .append("    wwwRedirect: ").append(d.wwwRedirect).append('\n')
             .append("    stripPrefix: ").append(d.stripPrefix).append('\n')
             .append("    externalDns: ").append(clusters != null && clusters.managedDns(env, d.host)).append('\n');
        }
        return b.toString();
    }

    /**
     * Re-renders a project's routes for envs on their configured cluster (after a cluster move the issuer and
     * expected IP may differ; manual-DNS domains go back to PENDING_DNS until the record points at the new IP).
     */
    public void reconcile(Project p, Set<String> envs) {
        for (String env : GitOpsService.ENVS) {
            if (!envs.contains(env)) continue;
            List<Domain> mine = domains.findByProjectIdAndEnvOrderByHostAscPathAsc(p.id, env);
            if (mine.isEmpty()) continue;   // nothing to route; removals re-render on their own
            for (Domain d : mine) {
                String ip = clusters.ingressIpFor(env), issuer = clusters.issuerFor(env, d.host);
                boolean moved = ip != null && d.dnsExpected != null && !ip.equals(d.dnsExpected);
                d.issuer = issuer;
                d.dnsExpected = ip;
                if (moved && !clusters.managedDns(env, d.host)) {
                    d.status = Domain.PENDING_DNS;
                    d.message = "The environment moved to another cluster - point the DNS record at " + ip;
                }
                domains.save(d);
            }
            mine.stream().map(d -> d.host).distinct().forEach(this::reassignOwner);
            try {
                render(p, env, "reconcile");
            } catch (RuntimeException e) {
                log.warn("routes reconcile {}/{} skipped: {}", p.name, env, e.getMessage());   // retried on the next reconcile
            }
        }
    }

    private String projectName(Long id) {
        return projects.findById(id).map(x -> x.name).orElse("another project");
    }
}
