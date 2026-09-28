package edu.upc.dtak.aran.operators.schemaintegration;

import edu.upc.dtak.aran.core.domain.JoinLabel;
import edu.upc.dtak.aran.core.util.Names;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure schema-integration logic. Implements the I1–I4 class invariants and the
 * domain-/range-constrained property variants from §5 of Flores et al. (2024).
 *
 * <p>Given two RDFS-typed graphs and a list of {@link Alignment}s, produces an
 * integrated graph and the list of alignments that could not be applied (typically
 * properties whose domain classes weren't aligned in this call).
 *
 * <p>The algorithm is class-oriented: classes are integrated first, then datatype
 * properties (which require domains to share an integrated class), then object
 * properties (which require both domains and ranges to share integrated classes).
 * This ordering matches Algorithm 4 of the paper.
 *
 * <p>This class is stateless and Spring-free. {@link SchemaIntegrationOperator}
 * wraps it to wire up Fuseki I/O.
 */
@NullMarked
public final class SchemaIntegrator {

    private static final Resource INTEGRATED_RESOURCE =
            ResourceFactory.createResource(IntegrationVocab.INTEGRATED_RESOURCE);

    /** An {@link Alignment} paired with the id of the durable mapping it came from (may be {@code null}). */
    public record TrackedAlignment(@Nullable UUID mappingId, Alignment alignment) {}

    /**
     * Outcome of one integration call. {@code applied} are the alignments that were
     * integrated into {@code graph}; {@code deferred} are those whose conditions
     * were not yet met (e.g. a property whose domain classes are not integrated).
     */
    public record IntegrationResult(Model graph,
                                    List<TrackedAlignment> applied,
                                    List<TrackedAlignment> deferred) {
        /** Back-compat view: the plain alignments that could not be applied (deferred). */
        public List<Alignment> unused() {
            return deferred.stream().map(TrackedAlignment::alignment).toList();
        }
    }

    /**
     * Full-rebuild convenience overload: treats every alignment as an untracked new
     * mapping ({@code mappingId == null}) with no previously-deferred set.
     */
    public IntegrationResult integrate(Model graphA, Model graphB, List<Alignment> alignments) {
        List<TrackedAlignment> tracked = new ArrayList<>();
        for (Alignment a : alignments) tracked.add(new TrackedAlignment(null, a));
        return integrate(graphA, graphB, tracked, List.of());
    }

    /**
     * Incremental-aware integration. {@code priorIntegratedGraph} is {@code G_I^old}
     * (for a full rebuild, the union of everything; for an incremental step, the
     * active integrated graph). Only {@code newMappings + previouslyDeferred} are
     * evaluated — previously-applied mappings are never re-run.
     */
    public IntegrationResult integrate(Model priorIntegratedGraph, Model newSourceGraph,
                                       List<TrackedAlignment> newMappings,
                                       List<TrackedAlignment> previouslyDeferred) {
        Model result = ModelFactory.createDefaultModel();
        result.add(priorIntegratedGraph);
        result.add(newSourceGraph);

        List<TrackedAlignment> toEvaluate = new ArrayList<>(newMappings);
        toEvaluate.addAll(previouslyDeferred);

        // M-compliance validation (paper §5.1): structurally invalid alignments are a
        // validation failure — surfaced by throwing — distinct from a legitimately
        // deferred "conditions not yet met" alignment (handled by the phase loops).
        for (TrackedAlignment ta : toEvaluate) {
            validateMCompliance(result, ta.alignment());
        }

        List<TrackedAlignment> applied = new ArrayList<>();
        List<TrackedAlignment> deferred = new ArrayList<>();

        // Phase 1 — classes (paper Algorithm 2).
        for (TrackedAlignment ta : toEvaluate) {
            if (ta.alignment().type() != Alignment.Type.CLASS) continue;
            Alignment a = ta.alignment();
            if (!hasResource(result, a.iriA()) || !hasResource(result, a.iriB())) {
                deferred.add(ta);
                continue;
            }
            integrateClass(result, a);
            applied.add(ta);
        }

        // Phase 2 — datatype properties (paper Algorithm 6).
        for (TrackedAlignment ta : toEvaluate) {
            if (ta.alignment().type() != Alignment.Type.DATATYPE) continue;
            if (integrateDatatypeProperty(result, ta.alignment())) applied.add(ta);
            else deferred.add(ta);
        }

        // Phase 3 — object properties (paper §5.1.2).
        for (TrackedAlignment ta : toEvaluate) {
            if (ta.alignment().type() != Alignment.Type.OBJECT) continue;
            if (integrateObjectProperty(result, ta.alignment())) applied.add(ta);
            else deferred.add(ta);
        }

        // Phase 4 — join properties (paper Algorithm 8).
        for (TrackedAlignment ta : toEvaluate) {
            if (ta.alignment().type() != Alignment.Type.JOIN) continue;
            if (integrateJoinProperty(result, ta.alignment())) applied.add(ta);
            else deferred.add(ta);
        }
        return new IntegrationResult(result, applied, deferred);
    }

    /**
     * M-compliance structural validation (paper §5.1). A resource that is not yet
     * present in the graph (no {@code rdf:type}) is treated as "not ready" and left
     * to the phase loops to defer; a resource that IS present but has the wrong shape
     * for its alignment kind is a hard validation failure.
     */
    private static void validateMCompliance(Model m, Alignment a) {
        switch (a.type()) {
            case CLASS -> {
                requireClass(m, a.iriA(), a);
                requireClass(m, a.iriB(), a);
            }
            case DATATYPE, JOIN -> {
                requirePropertyWithDomain(m, a.iriA(), a);
                requirePropertyWithDomain(m, a.iriB(), a);
            }
            case OBJECT -> {
                requirePropertyWithDomainAndRange(m, a.iriA(), a);
                requirePropertyWithDomainAndRange(m, a.iriB(), a);
            }
        }
    }

    private static boolean isPresent(Model m, Resource r) {
        return m.contains(r, RDF.type, (org.apache.jena.rdf.model.RDFNode) null);
    }

    private static void requireClass(Model m, String iri, Alignment a) {
        Resource r = m.createResource(iri);
        if (isPresent(m, r) && !m.contains(r, RDF.type, RDFS.Class)) {
            throw new MappingValidationException(a, iri + " is not an rdfs:Class");
        }
    }

    private static void requirePropertyWithDomain(Model m, String iri, Alignment a) {
        Resource r = m.createResource(iri);
        if (!isPresent(m, r)) return;
        if (!m.contains(r, RDF.type, RDF.Property)) {
            throw new MappingValidationException(a, iri + " is not an rdf:Property");
        }
        if (!m.contains(r, RDFS.domain, (org.apache.jena.rdf.model.RDFNode) null)) {
            throw new MappingValidationException(a, iri + " has no rdfs:domain");
        }
    }

    private static void requirePropertyWithDomainAndRange(Model m, String iri, Alignment a) {
        requirePropertyWithDomain(m, iri, a);
        Resource r = m.createResource(iri);
        if (isPresent(m, r) && !m.contains(r, RDFS.range, (org.apache.jena.rdf.model.RDFNode) null)) {
            throw new MappingValidationException(a, iri + " has no rdfs:range");
        }
    }

    // ─── Class integration: I1..I4 ──────────────────────────────────────────

    private void integrateClass(Model m, Alignment a) {
        Resource rA = m.createResource(a.iriA());
        Resource rB = m.createResource(a.iriB());

        // Resolve the effective IntegratedResource for each alignment target.
        // This handles source classes that already have an IR superclass (e.g. from a
        // JOIN auto-promotion that ran in an earlier phase of this same call) so we
        // reuse the existing IR rather than minting a duplicate.
        Resource effA = effectiveIR(m, rA);
        Resource effB = effectiveIR(m, rB);
        boolean aIsIR = effA != null;
        boolean bIsIR = effB != null;

        if (aIsIR && bIsIR) {
            if (effA.equals(effB)) {
                // Already share the same IR — nothing to do.
                assertDepthOne(m);
                return;
            }
            // I4: both effectively integrated — create new IR, redirect both to it.
            String newIri = mintIri(a.label());
            Resource newIR = createIntegratedClass(m, newIri, a.label(), a, effA, effB);
            retireMergedInto(m, effA, newIR);
            retireMergedInto(m, effB, newIR);
        } else if (aIsIR) {
            // I3: B has no IR yet — rB becomes subClassOf the effective IR of A.
            m.add(rB, RDFS.subClassOf, effA);
        } else if (bIsIR) {
            // I2: A has no IR yet — rA becomes subClassOf the effective IR of B.
            m.add(rA, RDFS.subClassOf, effB);
        } else {
            // I1: neither has an IR — create new IR; both become subclasses of it.
            String newIri = mintIri(a.label());
            Resource newIR = createIntegratedClass(m, newIri, a.label(), a);
            m.add(rA, RDFS.subClassOf, newIR);
            m.add(rB, RDFS.subClassOf, newIR);
        }
        assertDepthOne(m);
    }

    /**
     * Returns the IntegratedResource that {@code cls} IS, or HAS as a direct
     * superclass via {@code rdfs:subClassOf} (or the NextiaDI variant).  Returns
     * {@code null} if no IntegratedResource is found.  The depth-1 invariant
     * guarantees at most one such superclass.
     */
    private static @Nullable Resource effectiveIR(Model m, Resource cls) {
        if (isIntegratedResource(m, cls)) return cls;
        Property nxSubClassOf = m.createProperty(IntegrationVocab.SUB_CLASS_OF);
        for (Property pred : List.of(RDFS.subClassOf, nxSubClassOf)) {
            StmtIterator supers = m.listStatements(cls, pred, (org.apache.jena.rdf.model.RDFNode) null);
            while (supers.hasNext()) {
                org.apache.jena.rdf.model.RDFNode sup = supers.next().getObject();
                if (sup.isResource() && isIntegratedResource(m, sup.asResource())) {
                    return sup.asResource();
                }
            }
        }
        return null;
    }

    /**
     * S5-3. {@code m.add} is idempotent onto whatever resource already holds {@code iri}, so
     * before this guard a second alignment carrying the same label silently attached its two
     * source classes under the FIRST alignment's IntegratedResource — one integrated class over
     * two unrelated concepts, whose {@code int_} view is a UNION ALL that DuckDbExecutor pads
     * with NULLs rather than failing. Wrong rows, not an error. Decision D2 closes it by
     * refusing the mint (integrated IRIs must stay label-derived: OQL names a class by the IRI's
     * local name, so the label is the name a user types).
     */
    private Resource createIntegratedClass(Model m, String iri, String label,
                                           Alignment a, @Nullable Resource... ownedIrs) {
        refuseCollidingMint(m, iri, a, "class", ownedIrs);
        Resource r = m.createResource(iri);
        m.add(r, RDF.type, RDFS.Class);
        m.add(r, RDF.type, INTEGRATED_RESOURCE);
        m.add(r, RDFS.label, label);
        return r;
    }

    // ─── Property integration ───────────────────────────────────────────────

    /**
     * @return true if integrated; false if the alignment goes to the unused set
     *         (typically because the domain classes aren't aligned yet).
     */
    private boolean integrateDatatypeProperty(Model m, Alignment a) {
        if (!hasResource(m, a.iriA()) || !hasResource(m, a.iriB())) return false;
        Resource rA = m.createResource(a.iriA());
        Resource rB = m.createResource(a.iriB());

        Resource domA = integratedClassOf(m, rA, RDFS.domain);
        Resource domB = integratedClassOf(m, rB, RDFS.domain);
        if (domA == null || domB == null || !domA.equals(domB)) {
            return false; // paper: defer until domains get integrated.
        }
        applyPropertyInvariants(m, rA, rB, a, domA, /*range=*/ ResourceFactory.createResource(XSD.xstring.getURI()));
        return true;
    }

    private boolean integrateObjectProperty(Model m, Alignment a) {
        if (!hasResource(m, a.iriA()) || !hasResource(m, a.iriB())) return false;
        Resource rA = m.createResource(a.iriA());
        Resource rB = m.createResource(a.iriB());

        Resource domA = integratedClassOf(m, rA, RDFS.domain);
        Resource domB = integratedClassOf(m, rB, RDFS.domain);
        Resource rngA = integratedClassOf(m, rA, RDFS.range);
        Resource rngB = integratedClassOf(m, rB, RDFS.range);
        if (domA == null || !domA.equals(domB)) return false;
        if (rngA == null || !rngA.equals(rngB)) return false;

        applyPropertyInvariants(m, rA, rB, a, domA, rngA);
        return true;
    }

    private boolean integrateJoinProperty(Model m, Alignment a) {
        if (!hasResource(m, a.iriA()) || !hasResource(m, a.iriB())) return false;
        Resource rA = m.createResource(a.iriA());
        Resource rB = m.createResource(a.iriB());

        // A join bridges two datatype properties whose domain classes are typically NOT
        // integrated — that is precisely why a join is needed (§5.1.2 of the paper). So we
        // prefer the integrated class of each domain when one exists, but fall back to the
        // raw domain class otherwise. Requiring integration here would defeat the purpose.
        Resource domA = integratedOrRawClass(m, rA, RDFS.domain);
        Resource domB = integratedOrRawClass(m, rB, RDFS.domain);
        if (domA == null || domB == null) return false; // properties with no domain at all.

        JoinLabel packed = JoinLabel.parse(a.label());
        String joinLabel = packed.label();
        String direction = packed.direction();
        // P_l and P_s minted as one IRI make the join column a sub-property of the join object
        // property itself; compile succeeded and every query then failed as an ambiguous join.
        if (packed.mintsOneIri()) {
            throw new MappingValidationException(a, "the join label '" + joinLabel.trim()
                    + "' and its relationship name '" + packed.propertyName().trim()
                    + "' mint the same IRI <" + mintIri(joinLabel) + ">, but the join's datatype"
                    + " property and its relationship must be two resources — give the"
                    + " relationship a name that differs from the label.");
        }

        // Auto-promote domA to integrated class if it is a raw source class. The fragment is
        // Int_<localName> of the SOURCE class, so two datasets that each call a table `customer`
        // mint the same IRI — refused by createIntegratedClass rather than silently shared (S5-3).
        // This is the one integrated name nobody chose, so it is written in OQL's bare alphabet:
        // the local name's SQL form, the one its int_ view already carries. An Iceberg table
        // identifier (db.venue) or a nested JSON class (paper.venue) used to mint Int_db.venue,
        // which a query reaches only quoted and a cube refuses outright.
        if (!isIntegratedResource(m, domA)) {
            String localName = domA.getURI().replaceAll("^.*[/#:]", "");
            String newClassIri = mintIri("Int_" + Names.sqlName(domA.getURI()));
            Resource newClassIR = createIntegratedClass(m, newClassIri, "Integrated " + localName, a);
            m.add(domA, RDFS.subClassOf, newClassIR);
            domA = newClassIR;
        }

        // Auto-promote domB to integrated class if it is a raw source class
        if (!isIntegratedResource(m, domB)) {
            String localName = domB.getURI().replaceAll("^.*[/#:]", "");
            String newClassIri = mintIri("Int_" + Names.sqlName(domB.getURI()));
            Resource newClassIR = createIntegratedClass(m, newClassIri, "Integrated " + localName, a);
            m.add(domB, RDFS.subClassOf, newClassIR);
            domB = newClassIR;
        }

        // Emit the two-node form of Flores et al. (2024) Fig. 14 (see Alg. 8):
        //
        //   P_l — the unified integrated *datatype* property that the two join
        //         columns become sub-properties of. It carries the datatype range
        //         and, deliberately, NO rdfs:domain so it never surfaces as an
        //         object property in MappingCatalog Query 3.
        //   P_s — the join *object* property that carries the entity domain/range
        //         and the execution metadata (joinOnLeft/joinOnRight). Typed
        //         nx:JoinProperty (an Aran choice per metamodel Fig. 9) but NOT
        //         IntegratedResource, so it too stays out of Query 3.
        Resource intResourceType = m.createResource(IntegrationVocab.INTEGRATED_RESOURCE);
        Resource joinPropertyType = m.createResource(IntegrationVocab.JOIN_PROPERTY);
        Property joinPropPred = m.createProperty(IntegrationVocab.JOIN_PROPERTY);
        Property joinOnLeftProp = m.createProperty(IntegrationVocab.JOIN_ON_LEFT);
        Property joinOnRightProp = m.createProperty(IntegrationVocab.JOIN_ON_RIGHT);

        // P_l = generateURI(joinLabel)
        Resource pL = m.createResource(mintIri(joinLabel));
        m.add(pL, RDF.type, intResourceType);
        m.add(pL, RDF.type, RDF.Property);
        m.add(pL, RDFS.label, joinLabel);
        // Datatype range fallback — join columns are datatype properties, not classes.
        m.add(pL, RDFS.range, ResourceFactory.createResource(XSD.xstring.getURI()));
        m.add(rA, RDFS.subPropertyOf, pL);
        m.add(rB, RDFS.subPropertyOf, pL);

        // P_s = generateURI(relName, else joinLabel + "_join")
        String joinName = packed.propertyName();
        Resource pS = m.createResource(mintIri(joinName));
        m.add(pS, RDF.type, RDF.Property);
        m.add(pS, RDF.type, joinPropertyType);
        m.add(pS, RDFS.label, joinName);
        // A "backward" relationship simply swaps which side is left/right; the join
        // stays internally consistent (domain ↔ joinOnLeft, range ↔ joinOnRight).
        boolean backward = "backward".equalsIgnoreCase(direction);
        Resource leftClass  = backward ? domB : domA;
        Resource rightClass = backward ? domA : domB;
        Resource leftCol    = backward ? rB : rA;
        Resource rightCol   = backward ? rA : rB;
        m.add(pS, RDFS.domain, leftClass);
        m.add(pS, RDFS.range, rightClass);
        m.add(pS, joinOnLeftProp, leftCol);
        m.add(pS, joinOnRightProp, rightCol);

        // Link P_l to its join object property.
        m.add(pL, joinPropPred, pS);

        return true;
    }

    /**
     * I1..I4 for properties — same shape as the class version, just with
     * subPropertyOf. Branches on the <em>effective</em> integrated property of each
     * side so a source property that is already {@code rdfs:subPropertyOf} an
     * integrated property is treated as I2/I3 reuse, not misread as I1 (a fresh mint).
     */
    private void applyPropertyInvariants(Model m, Resource rA, Resource rB,
                                         Alignment a, Resource integratedDomain,
                                         Resource integratedRange) {
        String label = a.label();
        Resource effA = effectiveIntegratedProperty(m, rA);
        Resource effB = effectiveIntegratedProperty(m, rB);
        boolean aIsIR = effA != null;
        boolean bIsIR = effB != null;

        if (aIsIR && bIsIR) {
            if (effA.equals(effB)) {
                // Already share the same integrated property — nothing to do.
                assertPropertyDepthOne(m);
                return;
            }
            // I4: both effectively integrated — mint a new IR, redirect both to it.
            String newIri = mintIri(label);
            Resource newIR = createIntegratedProperty(m, newIri, label, integratedDomain,
                    integratedRange, a, effA, effB);
            retireMergedInto(m, effA, newIR);
            retireMergedInto(m, effB, newIR);
        } else if (aIsIR) {
            // I3: B has no integrated property yet — attach to the effective IR of A.
            m.add(rB, RDFS.subPropertyOf, effA);
        } else if (bIsIR) {
            // I2: A has no integrated property yet — attach to the effective IR of B.
            m.add(rA, RDFS.subPropertyOf, effB);
        } else {
            // I1: neither is integrated — mint a new IR; both become sub-properties.
            String newIri = mintIri(label);
            Resource newIR = createIntegratedProperty(m, newIri, label, integratedDomain,
                    integratedRange, a);
            m.add(rA, RDFS.subPropertyOf, newIR);
            m.add(rB, RDFS.subPropertyOf, newIR);
        }
        assertPropertyDepthOne(m);
    }

    /**
     * Returns the IntegratedResource that {@code prop} IS, or HAS as a direct
     * super-property via {@code rdfs:subPropertyOf} (or the NextiaDI variant).
     * Returns {@code null} if none is found. Mirrors {@link #effectiveIR} for the
     * property hierarchy.
     *
     * <p>The "at most one integrated parent per property" depth-1 invariant this
     * relies on is an <strong>Aran addition</strong> — Flores et al. (2024) state
     * depth-1 for classes only. Finding more than one distinct integrated parent is
     * a structural error and throws.
     */
    private static @Nullable Resource effectiveIntegratedProperty(Model m, Resource prop) {
        if (isIntegratedResource(m, prop)) return prop;
        Property nxSubPropertyOf = m.createProperty(IntegrationVocab.SUB_PROPERTY_OF);
        Resource found = null;
        for (Property pred : List.of(RDFS.subPropertyOf, nxSubPropertyOf)) {
            StmtIterator supers = m.listStatements(prop, pred, (org.apache.jena.rdf.model.RDFNode) null);
            while (supers.hasNext()) {
                org.apache.jena.rdf.model.RDFNode sup = supers.next().getObject();
                if (sup.isResource() && isIntegratedResource(m, sup.asResource())) {
                    Resource ir = sup.asResource();
                    if (found != null && !found.equals(ir)) {
                        throw new IllegalStateException(
                                "Property " + prop + " has more than one distinct integrated parent: "
                                        + found + " and " + ir);
                    }
                    found = ir;
                }
            }
        }
        return found;
    }

    /** Property analogue of {@link #createIntegratedClass}'s S5-3 guard. */
    private Resource createIntegratedProperty(Model m, String iri, String label,
                                              Resource domain, Resource range,
                                              Alignment a, @Nullable Resource... ownedIrs) {
        refuseCollidingMint(m, iri, a, "property", ownedIrs);
        Resource r = m.createResource(iri);
        m.add(r, RDF.type, RDF.Property);
        m.add(r, RDF.type, INTEGRATED_RESOURCE);
        m.add(r, RDFS.label, label);
        m.add(r, RDFS.domain, domain);
        m.add(r, RDFS.range, range);
        return r;
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private static void assertDepthOne(Model m) {
        Property nxSubClassOf = m.createProperty(IntegrationVocab.SUB_CLASS_OF);
        List<Statement> stmts = new ArrayList<>();
        m.listStatements(null, RDFS.subClassOf, (org.apache.jena.rdf.model.RDFNode) null).forEachRemaining(stmts::add);
        m.listStatements(null, nxSubClassOf, (org.apache.jena.rdf.model.RDFNode) null).forEachRemaining(stmts::add);
        Property wrapperSqlProp = m.createProperty("http://www.essi.upc.edu/DTIM/NextiaDI/wrapperSql");
        for (Statement s : stmts) {
            Resource sub = s.getSubject();
            if (s.getObject().isResource()) {
                Resource sup = s.getObject().asResource();
                if (isIntegratedResource(m, sub) && isIntegratedResource(m, sup)) {
                    throw new IllegalStateException("Depth-1 violation: Integrated class " + sub + " is subClassOf integrated class " + sup);
                }
            }
            if (sub.hasProperty(wrapperSqlProp)) {
                if (s.getObject().isResource() && !isIntegratedResource(m, s.getObject().asResource())) {
                    throw new IllegalStateException("Depth-1 violation: Leaf class " + sub + " is subClassOf non-integrated class " + s.getObject());
                }
            }
        }
    }

    /**
     * Property analogue of {@link #assertDepthOne}: no integrated property may be a
     * {@code (nx:)subPropertyOf} another integrated property. This "at most one
     * integrated parent per property" rule is an Aran addition (the paper states
     * depth-1 for classes only).
     */
    private static void assertPropertyDepthOne(Model m) {
        Property nxSubPropertyOf = m.createProperty(IntegrationVocab.SUB_PROPERTY_OF);
        List<Statement> stmts = new ArrayList<>();
        m.listStatements(null, RDFS.subPropertyOf, (org.apache.jena.rdf.model.RDFNode) null).forEachRemaining(stmts::add);
        m.listStatements(null, nxSubPropertyOf, (org.apache.jena.rdf.model.RDFNode) null).forEachRemaining(stmts::add);
        for (Statement s : stmts) {
            if (s.getObject().isResource()
                    && isIntegratedResource(m, s.getSubject())
                    && isIntegratedResource(m, s.getObject().asResource())) {
                throw new IllegalStateException("Depth-1 violation: Integrated property "
                        + s.getSubject() + " is subPropertyOf integrated property " + s.getObject());
            }
        }
    }

    private static boolean isIntegratedResource(Model m, Resource r) {
        return m.contains(r, RDF.type, INTEGRATED_RESOURCE);
    }

    /**
     * S5-3 — the mint refusal shared by {@link #createIntegratedClass} and
     * {@link #createIntegratedProperty}.
     *
     * <p>{@code mintIri} derives the IRI from the alignment label alone, which makes the label a
     * key over the whole integrated graph. Landing on an existing IntegratedResource is legitimate
     * in exactly one case — the paper's §5.2 I4, where the merge label deliberately names one of
     * the two IRs being merged (see {@link #retireMergedInto} and
     * {@code PaperRunningExampleTest.i4_label_colliding_with_an_existing_ir_iri}) — so the merge's
     * own effective IRs are passed in as {@code ownedIrs} and exempted. Every other landing is an
     * accident that used to produce one integrated resource over two unrelated concepts, silently.
     *
     * <p>Refusing, rather than disambiguating the IRI, is decision D2: the local name of an
     * integrated IRI is how OQL addresses the class, so a minted-from-id or suffixed IRI would be
     * a name the user has to look up rather than the label they chose — quoted identifiers make
     * it writable, not findable.
     */
    private static void refuseCollidingMint(Model m, String iri, Alignment a, String what,
                                            @Nullable Resource[] ownedIrs) {
        Resource target = m.createResource(iri);
        if (!isIntegratedResource(m, target)) return;
        if (ownedIrs != null) {
            for (Resource owned : ownedIrs) {
                if (owned != null && owned.equals(target)) return;
            }
        }
        throw new MappingValidationException(a,
                "would mint the integrated " + what + " <" + iri + ">, which already exists as an"
                        + " IntegratedResource this alignment does not own. Integrated IRIs are"
                        + " derived from the label (OQL addresses a class by the IRI's local name),"
                        + " so two alignments cannot share one — give this alignment a distinct"
                        + " label, or align the two concepts explicitly if they really are the same.");
    }

    /**
     * The integrated class associated with a property's domain/range if one exists,
     * otherwise the raw (source) class itself. Used by join integration, which must
     * tolerate non-integrated domains.
     */
    private static @Nullable Resource integratedOrRawClass(Model m, Resource property, Property which) {
        Resource integrated = integratedClassOf(m, property, which);
        if (integrated != null) return integrated;
        StmtIterator it = m.listStatements(property, which, (org.apache.jena.rdf.model.RDFNode) null);
        while (it.hasNext()) {
            org.apache.jena.rdf.model.RDFNode node = it.next().getObject();
            if (node.isResource()) return node.asResource();
        }
        return null;
    }

    private static @Nullable Resource integratedClassOf(Model m, Resource property, Property which) {
        // First, find the property's domain/range resource.
        StmtIterator it = m.listStatements(property, which, (org.apache.jena.rdf.model.RDFNode) null);
        Resource cls = null;
        if (it.hasNext()) {
            org.apache.jena.rdf.model.RDFNode node = it.next().getObject();
            if (node.isResource()) cls = node.asResource();
        }
        if (cls == null) return null;

        if (isIntegratedResource(m, cls)) return cls;

        // Otherwise walk up subClassOf chain looking for an IR.
        Property nxSubClassOf = m.createProperty(IntegrationVocab.SUB_CLASS_OF);
        StmtIterator supers = m.listStatements(cls, RDFS.subClassOf, (org.apache.jena.rdf.model.RDFNode) null);
        while (supers.hasNext()) {
            org.apache.jena.rdf.model.RDFNode sup = supers.next().getObject();
            if (sup.isResource() && isIntegratedResource(m, sup.asResource())) {
                return sup.asResource();
            }
        }
        supers = m.listStatements(cls, nxSubClassOf, (org.apache.jena.rdf.model.RDFNode) null);
        while (supers.hasNext()) {
            org.apache.jena.rdf.model.RDFNode sup = supers.next().getObject();
            if (sup.isResource() && isIntegratedResource(m, sup.asResource())) {
                return sup.asResource();
            }
        }
        return null;
    }

    /**
     * Fold {@code old} into the I4 merge target {@code neu}: redirect everything pointing at
     * {@code old}, then drop {@code old}'s own triples.
     *
     * <p>{@code mintIri} derives the new IR's IRI from the alignment label, so a label naming
     * one of the two resources being merged mints an IRI <em>identical</em> to it — the paper's
     * own §5.2 does exactly this (aligning {@code Name} with {@code Full_name} under the label
     * "Name"). Removing {@code old} in that case would delete the merge target that was just
     * created, leaving the sub-class/sub-property edges pointing at a resource that is no longer
     * an {@code :IntegratedResource}. When the two coincide there is nothing to retire: the
     * target already <em>is</em> {@code old}, enriched in place.
     */
    private static void retireMergedInto(Model m, Resource old, Resource neu) {
        if (old.equals(neu)) {
            return;
        }
        replaceObjectOccurrences(m, old, neu);
        m.removeAll(old, null, null);
    }

    /**
     * Rewrite every triple of the form {@code (?s, ?p, old)} to {@code (?s, ?p, neu)}.
     * Triples where {@code old} is the subject are left alone — callers handle those
     * explicitly (usually by removing them, since IR replacement drops the old IR's
     * own type/label triples in favour of the new one).
     */
    private static void replaceObjectOccurrences(Model m, Resource old, Resource neu) {
        List<Statement> toRemove = new ArrayList<>();
        List<Statement> toAdd = new ArrayList<>();
        StmtIterator it = m.listStatements(null, null, old);
        while (it.hasNext()) {
            Statement s = it.next();
            toRemove.add(s);
            toAdd.add(m.createStatement(s.getSubject(), s.getPredicate(), neu));
        }
        m.remove(toRemove);
        m.add(toAdd);
    }

    private static boolean hasResource(Model m, String iri) {
        Resource r = m.createResource(iri);
        return m.contains(r, null, (org.apache.jena.rdf.model.RDFNode) null)
                || m.contains(null, null, r);
    }

    /** Mint a stable integrated-IRI from a user-supplied label. */
    private static String mintIri(String label) {
        return IntegrationVocab.INTEGRATION_NS + Names.iriFragment(label.trim());
    }

}
