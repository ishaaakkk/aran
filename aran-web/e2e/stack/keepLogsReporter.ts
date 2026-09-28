import type { Reporter, TestCase, TestResult } from '@playwright/test/reporter';

/** Sets E2E_KEEP_LOGS so globalTeardown preserves the stack logs after a failure. */
class KeepLogsReporter implements Reporter {
  onTestEnd(_test: TestCase, result: TestResult): void {
    if (result.status === 'failed' || result.status === 'timedOut') {
      process.env.E2E_KEEP_LOGS = '1';
    }
  }
}

export default KeepLogsReporter;
