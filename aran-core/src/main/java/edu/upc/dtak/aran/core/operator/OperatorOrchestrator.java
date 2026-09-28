package edu.upc.dtak.aran.core.operator;

import edu.upc.dtak.aran.core.tenant.TenantContext;
import edu.upc.dtak.aran.core.tenant.TenantContextHolder;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Coordinates and acts as the entry boundary for invoking platform operators.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Resolving operators at compile-time via Spring DI;</li>
 *   <li>Isolating tenant boundaries during the call;</li>
 *   <li>Catching unexpected execution exceptions to safeguard the web boundary.</li>
 * </ul>
 */
@Service
@NullMarked
public class OperatorOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(OperatorOrchestrator.class);

    private final Map<String, Operator> operatorsMap;

    public OperatorOrchestrator(List<Operator> operators) {
        Map<String, Operator> map = new LinkedHashMap<>();
        for (Operator op : operators) {
            String id = op.getOperatorId().toLowerCase();
            if (map.containsKey(id)) {
                log.warn("Duplicate operator registration ignored for key '{}' (Class: {})",
                        id, op.getClass().getName());
                continue;
            }
            map.put(id, op);
            log.info("Registered active Operator: '{}' ({})", id, op.getDisplayName());
        }
        this.operatorsMap = Map.copyOf(map);
    }

    public OperatorResult execute(String operatorId, OperatorRequest request) {
        return execute(operatorId, request, ProgressReporter.NOOP);
    }

    /**
     * Executes an operation, giving the operator a channel to report its stages on.
     *
     * <p>Used by the job substrate, whose reporter persists each stage onto the
     * job row and streams it to listeners. Operators that do not override
     * {@link Operator#execute(OperatorRequest, ProgressReporter)} ignore it.
     */
    public OperatorResult execute(String operatorId, OperatorRequest request, ProgressReporter progress) {
        Operator op = operatorsMap.get(operatorId.toLowerCase());
        if (op == null) {
            log.warn("Rejected execution request — unknown operator '{}'", operatorId);
            return OperatorResult.failure(
                    "OPERATOR_NOT_FOUND", "No active operator with id '" + operatorId + "'");
        }

        TenantContext tenant = new TenantContext(request.tenantId());
        try {
            return TenantContextHolder.callWith(tenant, () -> op.execute(request, progress));
        } catch (Exception ex) {
            log.error("Operator '{}' failed unexpectedly for tenant '{}'",
                    operatorId, tenant.value(), ex);
            return OperatorResult.failure(
                    "OPERATOR_EXECUTION_FAILURE",
                    Optional.ofNullable(ex.getMessage()).orElse("Unexpected error occurred"));
        }
    }

    public Collection<Operator> activeOperators() {
        return operatorsMap.values();
    }

    public Optional<Operator> find(String operatorId) {
        return Optional.ofNullable(operatorsMap.get(operatorId.toLowerCase()));
    }
}
