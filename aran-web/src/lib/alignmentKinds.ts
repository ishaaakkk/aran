import type { MappingKind } from '@/services/types';

const CLASS_TYPES = new Set(['class', 'integrated']);
const PROPERTY_TYPES = new Set(['datatype', 'object']);

/**
 * The alignment kinds a pair of graph nodes admits, by node type, with the reason when none does.
 *
 * The schema-integration operator's M-compliance check (paper §5.1) needs a class at both ends of
 * a class alignment and a property at both ends of a datatype, object or join one. The creator
 * used to guess "datatype" for a class paired with a property, and offer an `xsd:string` node at
 * all; the row saved and every rebuild after it failed INVALID_ALIGNMENT (J4-F6). The server now
 * refuses such a row too (`IntegrationModelService.rejectMisshapenEnds`); this keeps the form from
 * offering it.
 */
export function alignmentKindsFor(
  left: string | undefined,
  right: string | undefined,
): { kinds: MappingKind[]; reason: string | null } {
  if (!left || !right) return { kinds: [], reason: null };
  const a = left.toLowerCase();
  const b = right.toLowerCase();
  if (a === 'xsd' || b === 'xsd') {
    return { kinds: [], reason: 'An xsd datatype is a type, not an element that can be aligned. Pick a class or a property.' };
  }
  if (CLASS_TYPES.has(a) && CLASS_TYPES.has(b)) return { kinds: ['class'], reason: null };
  if (PROPERTY_TYPES.has(a) && PROPERTY_TYPES.has(b)) return { kinds: ['datatype', 'object', 'join'], reason: null };
  return { kinds: [], reason: 'A class aligns only with a class, and a property only with a property.' };
}

/**
 * The accepted mappings a compile failure names, by one of their ends' IRIs (J4-F6). The operator
 * reports INVALID_ALIGNMENT with a full IRI, which a user then had to find by eye among rows that
 * show local names; the matching rows are flagged instead. An IRI counts only as a whole token,
 * so `…/acm` is not found inside `…/acm/doi`.
 */
export function mappingsNamedIn(
  message: string | null | undefined,
  mappings: { id: string; sourceIri: string; targetIri: string }[],
): Set<string> {
  const out = new Set<string>();
  if (!message) return out;
  const named = (iri: string) => {
    let from = 0;
    for (;;) {
      const at = message.indexOf(iri, from);
      if (at < 0) return false;
      const next = message.charAt(at + iri.length);
      if (next === '' || /[\s,;:)'"]/.test(next)) return true;
      from = at + 1;
    }
  };
  for (const m of mappings) {
    if (named(m.sourceIri) || named(m.targetIri)) out.add(m.id);
  }
  return out;
}
