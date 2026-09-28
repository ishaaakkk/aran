// Preset starting positions for known demo datasets, keyed by class name.
// Purely cosmetic: a class not found here falls back to the circle + force
// layout in GraphCanvas. Kept out of the canvas component so product code
// doesn't hardcode demo data.
export const PRESET_LAYOUTS: Record<string, { x: number; y: number }> = {
  // FHIR seminar demo
  Patient: { x: 470, y: 300 }, Encounter: { x: 470, y: 96 },
  Observation: { x: 150, y: 178 }, Condition: { x: 150, y: 432 },
  MedicationRequest: { x: 470, y: 520 }, Practitioner: { x: 820, y: 178 },
  Organization: { x: 820, y: 440 },
};
