import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { JobTracker } from '../job-tracker';
import type { Job } from '@/lib/types';

const mockGetJobs = vi.fn<() => Promise<Job[]>>();
const mockGetSource = vi.fn();

vi.mock('@/lib/api', () => ({
  getJobs: (...args: unknown[]) => mockGetJobs(...(args as [])),
  getSource: (...args: unknown[]) => mockGetSource(...(args as [])),
}));

describe('JobTracker', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetSource.mockResolvedValue({
      source: { name: 'Test Source' },
      contentUnits: [],
    });
  });

  it('returns null when no active jobs', async () => {
    mockGetJobs.mockResolvedValue([]);

    const { container } = render(<JobTracker />);

    await waitFor(() => {
      expect(mockGetJobs).toHaveBeenCalled();
    });

    expect(container.innerHTML).toBe('');
  });

  it('renders active jobs with progress bars and source name', async () => {
    mockGetSource.mockResolvedValue({
      source: { name: 'My Podcast' },
      contentUnits: [
        { status: 'INDEXED', title: 'Ep 1' },
        { status: 'TRANSCRIBING', title: 'Ep 2' },
        { status: 'PENDING', title: 'Ep 3' },
      ],
    });

    mockGetJobs.mockResolvedValue([
      {
        id: 'j1',
        type: 'SYNC',
        entityId: 'src-1',
        entityType: 'SOURCE',
        status: 'RUNNING' as never,
        progress: 0.33,
        createdAt: '2026-01-01T00:00:00Z',
      },
    ]);

    render(<JobTracker />);

    expect(await screen.findByText('My Podcast')).toBeInTheDocument();
    expect(screen.getByText('RUNNING')).toBeInTheDocument();
    expect(screen.getByText('Active Jobs (1)')).toBeInTheDocument();
    expect(screen.getByText(/1 of 3 items/)).toBeInTheDocument();
    expect(screen.getByText(/Transcribing: Ep 2/)).toBeInTheDocument();
  });

  it('shows correct percentage', async () => {
    mockGetJobs.mockResolvedValue([
      {
        id: 'j2',
        type: 'SYNC',
        entityId: 'src-2',
        entityType: 'SOURCE',
        status: 'RUNNING' as never,
        progress: 0.75,
        createdAt: '2026-01-01T00:00:00Z',
      },
    ]);

    render(<JobTracker />);

    expect(await screen.findByText(/75%/)).toBeInTheDocument();
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

    expect(await screen.findByText('Test Source')).toBeInTheDocument();
    expect(screen.queryByText('DONE_JOB')).not.toBeInTheDocument();
  });
});
