package edu.upc.dtak.aran.app.web.dto;

import edu.upc.dtak.aran.core.domain.cube.Additivity;
import edu.upc.dtak.aran.core.domain.cube.CubeCalendar;
import edu.upc.dtak.aran.core.domain.cube.CubeDefinition;
import edu.upc.dtak.aran.core.domain.cube.CubeDimension;
import edu.upc.dtak.aran.core.domain.cube.CubeMeasure;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Data-transfer objects for the cube API.
 *
 * <p>The wire shape is the {@link CubeDefinition} record with one substitution: the star's
 * {@code productIds} — a bare positional list whose meaning is "fact first, then dimensions in
 * order" — are rendered as {@link CubeProductRef}s carrying that role and the product's name
 * explicitly. Position stays the source of truth in the domain and in storage; this is where it
 * is spelled out, so a client never has to reconstruct the convention to render a link.
 *
 * <p>The names come from {@link CubeDefinition#factProductName()} and
 * {@link CubeDefinition#dimensionProductName} rather than from a second copy of the
 * {@code <cube>_fact} / {@code <cube>_<dimension>} rule. A copy would be a name in the UI that
 * finds nothing in the products list the day either side changes.
 */
public final class CubeDtos {

    private CubeDtos() {
    }

    /** Body of {@code POST /api/projects/{projectId}/cubes}. */
    public record CreateCubeRequest(String name, @Nullable String description,
                                    CubeDefinitionDto definition) {}

    /** The shape of a cube as the wizard submits it and as the API renders it back. */
    public record CubeDefinitionDto(String factClassIri, List<CubeMeasureDto> measures,
                                    List<CubeDimensionDto> dimensions,
                                    @Nullable CubeCalendarDto calendar) {

        public static CubeDefinitionDto from(CubeDefinition cube) {
            return new CubeDefinitionDto(
                    cube.factClassIri(),
                    cube.measures().stream().map(CubeMeasureDto::from).toList(),
                    cube.dimensions().stream().map(CubeDimensionDto::from).toList(),
                    cube.calendar() == null ? null : CubeCalendarDto.from(cube.calendar()));
        }

        /**
         * The domain measures. A null list is an empty one rather than a
         * {@code NullPointerException}: the domain records reject what is actually wrong with a
         * definition, and they do it with a message the user can act on.
         */
        public List<CubeMeasure> toMeasures() {
            List<CubeMeasure> result = new ArrayList<>();
            for (CubeMeasureDto measure : measures == null ? List.<CubeMeasureDto>of() : measures) {
                result.add(measure.toDomain());
            }
            return result;
        }

        public List<CubeDimension> toDimensions() {
            List<CubeDimension> result = new ArrayList<>();
            for (CubeDimensionDto dimension
                    : dimensions == null ? List.<CubeDimensionDto>of() : dimensions) {
                result.add(dimension.toDomain());
            }
            return result;
        }

        public @Nullable CubeCalendar toCalendar() {
            return calendar == null ? null : calendar.toDomain();
        }
    }

    /** Either a column with an aggregator, or a RATIO's {@code expr} — never both. */
    public record CubeMeasureDto(String name, @Nullable String columnIri, @Nullable String expr,
                                 @Nullable String aggregator, Additivity additivity) {

        public static CubeMeasureDto from(CubeMeasure m) {
            return new CubeMeasureDto(m.name(), m.columnIri(), m.expr(), m.aggregator(),
                    m.additivity());
        }

        public CubeMeasure toDomain() {
            return new CubeMeasure(name, columnIri, expr, aggregator, additivity);
        }
    }

    public record CubeDimensionDto(String name, String classIri, String joinPropertyIri,
                                   boolean unknownMember, List<String> hierarchy,
                                   boolean allowUnverified) {

        public static CubeDimensionDto from(CubeDimension d) {
            return new CubeDimensionDto(d.name(), d.classIri(), d.joinPropertyIri(),
                    d.unknownMember(), d.hierarchy(), d.allowUnverified());
        }

        public CubeDimension toDomain() {
            return new CubeDimension(name, classIri, joinPropertyIri, unknownMember, hierarchy,
                    allowUnverified);
        }
    }

    public record CubeCalendarDto(String fromColumnIri, List<String> levels) {

        public static CubeCalendarDto from(CubeCalendar c) {
            return new CubeCalendarDto(c.fromColumnIri(), c.levels());
        }

        public CubeCalendar toDomain() {
            return new CubeCalendar(fromColumnIri, levels);
        }
    }

    /** A data product the cube created, and which half of the star it is. */
    public record CubeProductRef(UUID id, String role, String name) {

        static final String FACT = "FACT";
        static final String DIMENSION = "DIMENSION";
    }

    /**
     * A cube as the API renders it.
     *
     * @param jobIds the materialization jobs a create started; empty on every read, because a
     *               read starts none — the jobs belong to the act of creating, not to the cube
     */
    public record CubeDto(
            UUID id,
            String name,
            @Nullable String description,
            String factClassIri,
            Instant createdAt,
            List<CubeProductRef> products,
            CubeDefinitionDto definition,
            List<UUID> jobIds
    ) {

        public static CubeDto from(CubeDefinition cube, List<UUID> jobIds) {
            return new CubeDto(cube.id(), cube.name(), cube.description(), cube.factClassIri(),
                    cube.createdAt(), products(cube), CubeDefinitionDto.from(cube),
                    List.copyOf(jobIds));
        }

        /** A cube read back, with no jobs to report. */
        public static CubeDto from(CubeDefinition cube) {
            return from(cube, List.of());
        }

        /**
         * The star's products, decoded from their position: index 0 is the fact, index
         * {@code i} is dimension {@code i - 1}.
         *
         * <p>Bounded by both lists rather than by the dimensions alone. A cube whose stored
         * product ids do not line up with its dimensions is a broken row, and the useful
         * behaviour is to render the products it can name — not to fail the whole listing with an
         * index error the user cannot do anything about.
         */
        private static List<CubeProductRef> products(CubeDefinition cube) {
            List<UUID> ids = cube.productIds();
            if (ids.isEmpty()) {
                return List.of();
            }
            List<CubeProductRef> refs = new ArrayList<>();
            refs.add(new CubeProductRef(ids.getFirst(), CubeProductRef.FACT,
                    cube.factProductName()));
            for (int i = 1; i < ids.size() && i - 1 < cube.dimensions().size(); i++) {
                refs.add(new CubeProductRef(ids.get(i), CubeProductRef.DIMENSION,
                        cube.dimensionProductName(cube.dimensions().get(i - 1))));
            }
            return List.copyOf(refs);
        }
    }
}
