package edu.upc.dtak.aran.core.service;

import edu.upc.dtak.aran.core.domain.GraphStore;
import edu.upc.dtak.aran.core.domain.GraphVersion;
import edu.upc.dtak.aran.core.domain.IntegrationModel;
import edu.upc.dtak.aran.core.domain.IntegrationModelRepository;
import edu.upc.dtak.aran.core.domain.IntegrationRun;
import edu.upc.dtak.aran.core.domain.Project;
import edu.upc.dtak.aran.core.domain.ProjectRepository;
import edu.upc.dtak.aran.core.tenant.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * UI-OD2 (ruled 2026-09-25, audit J4-F1): a promote that would remove classes another project's
 * integration model compiled is refused.
 *
 * <p>The ACTIVE schema is one graph per tenant, but a compile reads one project's sources, so
 * promoting project B's draft replaced every class project A had promoted — and with them A's
 * graph-tier queries, its VIRTUAL products and its enforced constraints. The owner chose this
 * guard over carrying other models' classes into every compile and over one model per tenant.
 *
 * <p>A removed class belongs to another model — and the promote is refused — when (review UA5,
 * round 2):
 * <ol>
 *   <li>that model's last promoted version carries it. Whether the promoting model compiled it
 *       too does not matter: the refusal's own advice ("enrol their sources in this project")
 *       makes a class shared, and a shared class dropped by one project still breaks the other's
 *       queries. Or</li>
 *   <li>the ACTIVE version is that model's run's draft (a version keeps its id when promoted), so
 *       every class ACTIVE drops was put there by it.</li>
 * </ol>
 * No run owns ACTIVE after a rollback; the first rule still attributes its classes.
 *
 * <p><b>The release.</b> A class several projects promoted could otherwise never leave: each
 * one's promote would be refused on the others' last promote. So a claim on a class the
 * promoting model <em>also</em> promoted is released once the claiming model's newest compile —
 * its pending draft — no longer carries it: that project has already decided to drop it. The
 * release is for shared classes only; a class the promoter never promoted stays the other
 * project's until that project promotes without it, whatever its drafts say, so an unpromoted
 * draft never exposes a project's live classes to a stranger.
 *
 * <p>A rollback is not behind this guard: it is typed, tenant-wide and shows the same diff, and
 * whether it should be is an open owner question (see {@link SchemaVersionService#rollback}).
 *
 * <p>"Removed" is {@link SchemaDiffService#classIris}'s rule on both graphs — the rule the
 * promote dialog's diff is drawn with, so what is refused is exactly what the user was shown.
 *
 * <p>The refusal names the owning project and the way forward (review UA5): a compile carries
 * only its own project's classes, so once one project has promoted, every other project's promote
 * that drops them is refused — the reader has to learn whose classes they are and that the fix is
 * to keep them in this draft or to make the change from the owner.
 */
@NullMarked
final class PromoteGuard {

    private final IntegrationModelRepository models;
    private final GraphStore graphStore;
    private final SchemaDiffService diff;
    /** Names the owning project in the refusal; {@code null} in a build without the project port. */
    private final @Nullable ProjectRepository projects;

    PromoteGuard(IntegrationModelRepository models, GraphStore graphStore, @Nullable ProjectRepository projects) {
        this.models = models;
        this.graphStore = graphStore;
        this.diff = new SchemaDiffService(graphStore);
        this.projects = projects;
    }

    /**
     * @param promotingModelId the model whose run produced the draft; {@code null} for a draft no
     *                         run owns, which may then remove no model's classes
     * @param actor            who promotes: an owning project is named only when they can see it
     * @throws PromoteConflictException naming the classes, and the owning project when the actor
     *                                  may see it, when the promote would remove another model's
     */
    void check(TenantContext tenant, UUID draftVersionId, @Nullable UUID promotingModelId,
               @Nullable UUID actor) {
        GraphVersion active = graphStore.currentVersion(tenant);
        if (active == null || active.id().equals(draftVersionId)) {
            return;
        }
        Set<String> removed = new TreeSet<>(classesOf(tenant, active.id()));
        removed.removeAll(classesOf(tenant, draftVersionId));
        if (removed.isEmpty()) {
            return;
        }

        // Each other model's claim on a removed class: its last promote carries it, or ACTIVE is its draft.
        Map<UUID, Set<String>> claims = new LinkedHashMap<>();
        models.findRunByDraftVersion(tenant, active.id())
                .map(IntegrationRun::modelId)
                .filter(owner -> !owner.equals(promotingModelId))
                .ifPresent(owner -> claims.computeIfAbsent(owner, k -> new TreeSet<>()).addAll(removed));
        for (IntegrationModel other : models.findAllModels(tenant)) {
            if (other.id().equals(promotingModelId)) {
                continue;
            }
            Set<String> promoted = lastPromotedClasses(tenant, other.id());
            for (String classIri : removed) {
                if (promoted.contains(classIri)) {
                    claims.computeIfAbsent(other.id(), k -> new TreeSet<>()).add(classIri);
                }
            }
        }

        // The release: a class the promoter promoted too leaves once its other owners' newest
        // compile dropped it.
        Set<String> ownPromoted = promotingModelId == null ? Set.of()
                : lastPromotedClasses(tenant, promotingModelId);
        Set<String> othersClasses = new TreeSet<>();
        Set<String> shared = new TreeSet<>();
        Set<UUID> ownerModels = new LinkedHashSet<>();
        for (Map.Entry<UUID, Set<String>> claim : claims.entrySet()) {
            Optional<Set<String>> newestDraft = pendingDraftClasses(tenant, claim.getKey());
            for (String classIri : claim.getValue()) {
                boolean sharedClass = ownPromoted.contains(classIri);
                if (sharedClass && newestDraft.isPresent() && !newestDraft.get().contains(classIri)) {
                    continue;
                }
                othersClasses.add(classIri);
                ownerModels.add(claim.getKey());
                if (sharedClass) {
                    shared.add(classIri);
                }
            }
        }
        if (!othersClasses.isEmpty()) {
            List<String> refused = List.copyOf(othersClasses);
            Owners owners = owners(tenant, ownerModels, actor);
            throw new PromoteConflictException(PromoteConflictException.REMOVES_OTHER_MODELS_CLASSES,
                    "Promoting this draft would remove " + refused.size() + " class"
                            + (refused.size() == 1 ? "" : "es")
                            + " that " + owners.phrase() + " put in the tenant-wide active schema: "
                            + localNames(refused) + ". Their queries, virtual products and "
                            + "enforced constraints would stop working. Nothing was promoted. "
                            + "To promote, keep those classes in this draft — enrol their sources in "
                            + "this project, align them to the same names and compile again — or "
                            + "make the change from " + owners.from() + " (a project may remove its "
                            + "own classes)."
                            + (shared.isEmpty() ? "" : " This project promoted "
                                    + localNames(List.copyOf(shared)) + " too: a class several projects "
                                    + "promoted leaves once " + owners.from() + " compiles a draft without "
                                    + (shared.size() == 1 ? "it" : "them") + " — then promote this again."),
                    refused, owners.visibleNames());
        }
    }

    /** The classes of the model's pending draft — its newest compile — when it has one. */
    private Optional<Set<String>> pendingDraftClasses(TenantContext tenant, UUID modelId) {
        return models.latestDraftRun(tenant, modelId)
                .map(IntegrationRun::draftVersionId)
                .map(versionId -> classesOf(tenant, versionId));
    }

    /**
     * Who owns the classes, as the refusal can say it. A project is named only when the actor may
     * see it ({@link ProjectRepository#findVisibleToUser}); one they may not is said to exist and
     * never named or identified — the port's own no-leak rule.
     */
    private Owners owners(TenantContext tenant, Set<UUID> ownerModels, @Nullable UUID actor) {
        Map<UUID, UUID> projectOfModel = new LinkedHashMap<>();
        for (IntegrationModel m : models.findAllModels(tenant)) {
            projectOfModel.put(m.id(), m.projectId());
        }
        List<String> visible = new ArrayList<>();
        int hidden = 0;
        for (UUID modelId : ownerModels) {
            UUID projectId = projectOfModel.get(modelId);
            Optional<String> name = projects == null || actor == null || projectId == null
                    ? Optional.empty()
                    : projects.findVisibleToUser(tenant, projectId, actor).map(Project::name);
            if (name.isPresent()) {
                visible.add(name.get());
            } else {
                hidden++;
            }
        }
        return new Owners(visible, hidden, projects != null && actor != null);
    }

    private record Owners(List<String> visibleNames, int hidden, boolean membershipKnown) {
        String phrase() {
            List<String> parts = new ArrayList<>();
            if (!visibleNames.isEmpty()) {
                parts.add((visibleNames.size() == 1 ? "project " : "projects ") + quoted(visibleNames));
            }
            if (hidden > 0) {
                String unseen = membershipKnown
                        ? (hidden == 1 ? "another project" : hidden + " other projects") + " you are not a member of"
                        : (hidden == 1 ? "another project's integration model" : hidden + " other projects");
                parts.add(unseen);
            }
            return String.join(" and ", parts);
        }

        String from() {
            if (hidden == 0) {
                return quoted(visibleNames);
            }
            return visibleNames.isEmpty() && hidden == 1 ? "that project" : "the projects that own them";
        }

        private static String quoted(List<String> names) {
            List<String> q = new ArrayList<>();
            for (String n : names) {
                q.add("\"" + n + "\"");
            }
            return String.join(", ", q);
        }
    }

    private Set<String> lastPromotedClasses(TenantContext tenant, UUID modelId) {
        return models.latestPromotedRun(tenant, modelId)
                .map(IntegrationRun::promotedVersionId)
                .map(versionId -> classesOf(tenant, versionId))
                .orElse(Set.of());
    }

    private Set<String> classesOf(TenantContext tenant, UUID versionId) {
        return diff.classIris(tenant, versionId);
    }

    private static String localNames(List<String> iris) {
        List<String> names = new ArrayList<>();
        for (String iri : iris) {
            int cut = Math.max(iri.lastIndexOf('/'), Math.max(iri.lastIndexOf('#'), iri.lastIndexOf(':')));
            names.add(cut >= 0 && cut < iri.length() - 1 ? iri.substring(cut + 1) : iri);
        }
        return String.join(", ", names);
    }
}
