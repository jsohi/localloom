import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { SettingsView } from '../settings-view';

vi.mock('sonner', () => ({
  toast: { error: vi.fn(), success: vi.fn() },
}));

vi.mock('@/lib/api', () => ({
  getLlmHealth: vi.fn(),
  getModels: vi.fn(),
  getSources: vi.fn(),
}));

import { getLlmHealth, getModels, getSources } from '@/lib/api';

const mockGetLlmHealth = vi.mocked(getLlmHealth);
const mockGetModels = vi.mocked(getModels);
const mockGetSources = vi.mocked(getSources);

describe('SettingsView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders loading skeletons initially', () => {
    mockGetLlmHealth.mockReturnValue(new Promise(() => {}));
    mockGetModels.mockReturnValue(new Promise(() => {}));
    mockGetSources.mockReturnValue(new Promise(() => {}));

    const { container } = render(<SettingsView />);
    const skeletons = container.querySelectorAll('[class*="animate-pulse"], [data-slot="skeleton"]');
    expect(skeletons.length).toBeGreaterThan(0);
  });

  it('renders model list when data loads successfully', async () => {
    mockGetLlmHealth.mockResolvedValue({ status: 'UP', model: 'llama3' });
    mockGetModels.mockResolvedValue([
      {
        name: 'llama3:latest',
        size: 4_000_000_000,
        details: {
          family: 'llama',
          parameterSize: '8B',
          quantizationLevel: 'Q4_0',
        },
      },
    ]);
    mockGetSources.mockResolvedValue([]);

    render(<SettingsView />);

    await waitFor(() => {
      expect(screen.getByText('llama3:latest')).toBeInTheDocument();
    });

    expect(screen.getByText('8B')).toBeInTheDocument();
    expect(screen.getByText('Q4_0')).toBeInTheDocument();
    expect(screen.getByText('Online')).toBeInTheDocument();
    expect(screen.getByText('Active model: llama3')).toBeInTheDocument();
  });

  it('shows error state when health check fails', async () => {
    mockGetLlmHealth.mockRejectedValue(new Error('Connection refused'));
    mockGetModels.mockResolvedValue([]);
    mockGetSources.mockResolvedValue([]);

    render(<SettingsView />);

    await waitFor(() => {
      expect(screen.getByText('Unreachable')).toBeInTheDocument();
    });
  });

  it('renders storage data from sources', async () => {
    mockGetLlmHealth.mockResolvedValue({ status: 'UP', model: 'llama3' });
    mockGetModels.mockResolvedValue([]);
    mockGetSources.mockResolvedValue([
      {
        id: '1',
        name: 'Test Source',
        sourceType: 'PODCAST',
        syncStatus: 'IDLE',
        createdAt: '2025-01-01T00:00:00Z',
        contentUnits: [
          { id: 'cu1', contentType: 'AUDIO', status: 'INDEXED', createdAt: '2025-01-01T00:00:00Z' },
          { id: 'cu2', contentType: 'AUDIO', status: 'INDEXED', createdAt: '2025-01-01T00:00:00Z' },
        ],
      },
      {
        id: '2',
        name: 'Test Source 2',
        sourceType: 'FILE_UPLOAD',
        syncStatus: 'SYNCING',
        createdAt: '2025-01-01T00:00:00Z',
        contentUnits: [
          { id: 'cu3', contentType: 'TEXT_FILE', status: 'PENDING', createdAt: '2025-01-01T00:00:00Z' },
        ],
      },
    ] as never);

    render(<SettingsView />);

    await waitFor(() => {
      expect(screen.getByText('2')).toBeInTheDocument(); // Total Sources
    });

    expect(screen.getByText('1')).toBeInTheDocument(); // Synced Sources (only IDLE)
    expect(screen.getByText('3')).toBeInTheDocument(); // Content Units
  });

  it('disables Pull button with "Not yet implemented" title', async () => {
    mockGetLlmHealth.mockResolvedValue({ status: 'UP', model: 'llama3' });
    mockGetModels.mockResolvedValue([{ name: 'llama3:latest' }]);
    mockGetSources.mockResolvedValue([]);

    render(<SettingsView />);

    await waitFor(() => {
      expect(screen.getByText('llama3:latest')).toBeInTheDocument();
    });

    const pullButton = screen.getByRole('button', { name: /pull/i });
    expect(pullButton).toBeDisabled();
    expect(pullButton).toHaveAttribute('title', 'Not yet implemented');
  });
});
