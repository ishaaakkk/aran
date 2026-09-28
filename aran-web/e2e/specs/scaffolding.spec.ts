import { test, expect } from '@playwright/test';
import { PORTS, BASE_URL } from '../stack/ports';

test('the runner is wired and the port map is coherent', () => {
  expect(BASE_URL).toBe('http://localhost:5273');
  // Every port must be distinct — a collision would silently cross-wire services.
  const values = Object.values(PORTS);
  expect(new Set(values).size).toBe(values.length);
});
