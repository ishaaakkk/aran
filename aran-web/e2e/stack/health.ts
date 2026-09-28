/**
 * Poll an HTTP endpoint until it answers. A failed gate aborts the whole run
 * with the service name in the message — far easier to read than 14 specs
 * each timing out on a blank page.
 */
export async function waitForHttp(
  name: string,
  url: string,
  opts: { timeoutMs?: number; expectBody?: string } = {},
): Promise<void> {
  const timeoutMs = opts.timeoutMs ?? 180_000;
  const deadline = Date.now() + timeoutMs;
  let lastError = 'no attempt made';

  while (Date.now() < deadline) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(5_000) });
      if (res.ok) {
        if (!opts.expectBody) return;
        const body = await res.text();
        if (body.includes(opts.expectBody)) return;
        lastError = `body did not contain ${JSON.stringify(opts.expectBody)}`;
      } else {
        lastError = `HTTP ${res.status}`;
      }
    } catch (err) {
      lastError = err instanceof Error ? err.message : String(err);
    }
    await new Promise((r) => setTimeout(r, 2_000));
  }

  throw new Error(
    `[e2e] ${name} did not become healthy within ${timeoutMs / 1000}s at ${url} — last error: ${lastError}`,
  );
}
