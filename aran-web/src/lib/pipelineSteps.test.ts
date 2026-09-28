import { describe, expect, it } from 'vitest';
import { pipelineSteps, type PipelineInputs } from './pipelineSteps';

const base: PipelineInputs = {
  enrolledCount: 2, acceptedCount: 3, pendingCount: 0, compiling: false,
  draftVersionNumber: null, liveVersionNumber: null, changesToCompile: true,
};

const states = (i: PipelineInputs) => pipelineSteps(i).map((s) => s.state);
const promote = (i: PipelineInputs) => pipelineSteps(i)[4];

describe('pipelineSteps (J4-F5) — the stepper from server state', () => {
  it('the day after a promote, with nothing changed, every step is done and Promote names the version', () => {
    const live = { ...base, liveVersionNumber: 2, changesToCompile: false };
    expect(states(live)).toEqual(['done', 'done', 'done', 'done', 'done']);
    expect(promote(live).sub).toBe('v2 active');
  });

  it('a live model with new accepted work points at Compile, and says the active version stays', () => {
    const s = { ...base, liveVersionNumber: 2, changesToCompile: true };
    expect(states(s)[3]).toBe('current');
    expect(promote(s).sub).toBe('v2 active · changes to compile');
  });

  it('a draft the model compiled — this session or before a reload — makes Promote current', () => {
    const s = { ...base, draftVersionNumber: 7 };
    expect(states(s)[4]).toBe('current');
    expect(promote(s).sub).toBe('v7 awaits');
  });

  it('never claims a promote that has not happened', () => {
    expect(promote(base).sub).toBe('after compile');
    expect(states(base)[4]).toBe('todo');
  });

  it('keeps the review step current while suggestions wait', () => {
    expect(states({ ...base, pendingCount: 2 })[2]).toBe('current');
  });
});

describe('pipelineSteps (J4-F5) — a deferred alignment is said, not called a change to compile', () => {
  it('a live model whose only open item is a deferred alignment is up to date, and says one was deferred', () => {
    const s = { ...base, liveVersionNumber: 2, changesToCompile: false, deferredCount: 1 };
    expect(states(s)).toEqual(['done', 'done', 'done', 'done', 'done']);
    expect(pipelineSteps(s)[3].sub).toBe('up to date · 1 deferred');
    expect(promote(s).sub).toBe('v2 active');
  });

  it('counts several, and keeps saying so beside a draft or a pending compile', () => {
    expect(pipelineSteps({ ...base, deferredCount: 2 })[3].sub).toBe('changes to compile · 2 deferred');
    expect(pipelineSteps({ ...base, draftVersionNumber: 5, deferredCount: 2 })[3].sub).toBe('draft built · 2 deferred');
    expect(pipelineSteps({ ...base, compiling: true, deferredCount: 2 })[3].sub).toBe('running…');
  });
});
