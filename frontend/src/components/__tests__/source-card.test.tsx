import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { SourceCard } from '../source-card';
import type { Source } from '@/lib/types';
import { SourceType, SyncStatus } from '@/lib/types';

vi.mock('@/lib/api', () => ({
  deleteSource: vi.fn(),
}));

vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

vi.mock('next/link', () => ({
  default: ({ children, href }: { children: React.ReactNode; href: string }) => (
    <a href={href}>{children}</a>
  ),
}));

function makeSource(overrides: Partial<Source> = {}): Source {
  return {
    id: 'src-1',
    name: 'My Podcast',
    sourceType: SourceType.PODCAST,
    syncStatus: SyncStatus.IDLE,
    createdAt: '2026-01-01T00:00:00Z',
    contentUnits: [],
    ...overrides,
  };
}

describe('SourceCard', () => {
  const onDeleted = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders source name and episode count', () => {
    const source = makeSource({
      contentUnits: [
        {
          id: 'cu-1',
          contentType: 'AUDIO' as never,
          status: 'INDEXED' as never,
          createdAt: '2026-01-01T00:00:00Z',
        },
        {
          id: 'cu-2',
          contentType: 'AUDIO' as never,
          status: 'INDEXED' as never,
          createdAt: '2026-01-02T00:00:00Z',
        },
      ],
    });

    render(<SourceCard source={source} onDeleted={onDeleted} />);
    expect(screen.getByText('My Podcast')).toBeInTheDocument();
    expect(screen.getByText('2 episodes')).toBeInTheDocument();
  });

  it('shows sync status badge', () => {
    render(<SourceCard source={makeSource({ syncStatus: SyncStatus.SYNCING })} onDeleted={onDeleted} />);
    expect(screen.getByText('SYNCING')).toBeInTheDocument();
  });

  it('shows placeholder icon when no artwork', () => {
    render(<SourceCard source={makeSource({ iconUrl: undefined })} onDeleted={onDeleted} />);
    // The placeholder is a div with the MicIcon inside; no <img> should be present
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('delete button appears', () => {
    render(<SourceCard source={makeSource()} onDeleted={onDeleted} />);
    expect(screen.getByRole('button', { name: /delete source/i })).toBeInTheDocument();
  });
});
