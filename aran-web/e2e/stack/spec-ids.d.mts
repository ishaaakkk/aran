// Type declarations for spec-ids.mjs. Kept separate because the module
// itself must stay plain, loader-free ESM (see spec-ids.mjs); this file
// pins the runtime array to a readonly tuple of literals so consumers get
// a real `SpecId` union rather than `string`.
export declare const SPEC_IDS: readonly [
  'auth',
  'a1', 'a2', 'a3', 'a4', 'a5',
  'b1', 'b2', 'b3', 'b4', 'b5',
  'c1', 'c2', 'c3',
  'd1',
  's1',
  'a6',
  'p1',
  'v32',
  'v34',
  'v37',
  'ui1',
  't1',
];

export declare const SPEC_PASSWORD: 'e2e';
