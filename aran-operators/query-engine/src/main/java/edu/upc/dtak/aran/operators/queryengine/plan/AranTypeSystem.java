package edu.upc.dtak.aran.operators.queryengine.plan;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystemImpl;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * The type system {@link OqlPlanner} plans with: Calcite's default, except that {@code AVG} is
 * DOUBLE.
 *
 * <p>Calcite types {@code AVG} as its argument's own type; DuckDB, which computes it, answers
 * DOUBLE for every numeric argument. The plan's row type is what a VIRTUAL product records as its
 * {@code output_schema}, so the default declared BIGINT for a column whose values are fractional
 * ({@code ProductColumnsParityTest} pins the two paths together).
 *
 * <p>Public with a public no-argument constructor because Calcite does not take the instance:
 * {@code Frameworks} passes the class <em>name</em> through connection properties and
 * instantiates it reflectively.
 */
public final class AranTypeSystem extends RelDataTypeSystemImpl {

    @Override
    public RelDataType deriveAvgAggType(RelDataTypeFactory typeFactory, RelDataType argumentType) {
        return typeFactory.createTypeWithNullability(
                typeFactory.createSqlType(SqlTypeName.DOUBLE), argumentType.isNullable());
    }
}
