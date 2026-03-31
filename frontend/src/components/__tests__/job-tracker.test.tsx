import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { JobTracker } from '../job-tracker';
import type { Job } from '@/lib/types';

const mockGetJobs = vi.fn<() => Promise<Job[]>>();

vi.mock('@/lib/api', () => ({
  getJobs: (...args: unknown[]) => mockGetJobs(...(args as [])),
}));

describe('JobTracker', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('returns null when no active jobs', async () => {
    mockGetJobs.mockResolvedValue([]);

    const { container } = render(<JobTracker />);

    await waitFor(() => {
      expect(mockGetJobs).toHaveBeenCalled();
    });

    expect(container.innerHTML).toBe('');
  });

  it('renders active jobs with progress bars', async () => {
    mockGetJobs.mockResolvedValue([
      {
        id: 'j1',
        type: 'PODCAST_SYNC',
        entityId: 'src-1',
        entityType: 'SOURCE',
        status: 'RUNNING' as never,
        progress: 0.5,
        createdAt: '2026-01-01T00:00:00Z',
      },
    ]);

    render(<JobTracker />);

    expect(await screen.findByText('PODCAST_SYNC')).toBeInTheDocument();
    expect(screen.getByText('RUNNING')).toBeInTheDocument();
    expect(screen.getByText('Active Jobs (1)')).toBeInTheDocument();
  });

  it('shows correct percentage (progress * 100)', async () => {
    mockGetJobs.mockResolvedValue([
      {
        id: 'j2',
        type: 'INGEST',
        entityId: 'src-2',
        entityType: 'SOURCE',
        status: 'RUNNING' as never,
        progress: 0.75,
        createdAt: '2026-01-01T00:00:00Z',
      },
    ]);

    render(<JobTracker />);

    expect(await screen.findByText('75% complete')).toBeInTheDocument();
  });

  it('filters out completed and failed jobs', async () => {
    mockGetJobs.mockResolvedValue([
      {
        id: 'j3',
        type: 'DONE_JOB',
        entityId: 'src-3',
        entityType: 'SOURCE',
        status: 'COMPLETED' as never,
        progress: 1.0,
        createdAt: '2026-01-01T00:00:00Z',
      },
      {
        id: 'j4',
        type: 'PENDING_JOB',
        entityId: 'src-4',
        entityType: 'SOURCE',
        status: 'PENDING' as never,
        progress: 0,
        createdAt: '2026-01-01T00:00:00Z',
      },
    ]);

    render(<JobTracker />);

    expect(await screen.findByText('PENDING_JOB')).toBeInTheDocument();
    expect(screen.queryByText('DONE_JOB')).not.toBeInTheDocument();
  });
});
