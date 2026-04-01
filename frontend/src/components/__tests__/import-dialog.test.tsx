import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ImportDialog } from '../import-dialog';

vi.mock('@/lib/api', () => ({
  createSource: vi.fn(),
}));

vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

describe('ImportDialog', () => {
  const onImported = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders the import button', () => {
    render(<ImportDialog onImported={onImported} />);
    expect(screen.getByRole('button', { name: /import source/i })).toBeInTheDocument();
  });

  it('opens dialog on click', async () => {
    render(<ImportDialog onImported={onImported} />);
    fireEvent.click(screen.getByRole('button', { name: /import source/i }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });

  it('shows URL, name, and source type fields', async () => {
    render(<ImportDialog onImported={onImported} />);
    fireEvent.click(screen.getByRole('button', { name: /import source/i }));

    expect(await screen.findByLabelText(/url/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/name/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/source type/i)).toBeInTheDocument();
  });

  it('submit button is disabled while submitting', async () => {
    const { createSource } = await import('@/lib/api');
    const mockedCreateSource = vi.mocked(createSource);
    // Never resolve so we stay in submitting state
    mockedCreateSource.mockReturnValue(new Promise(() => {}));

    render(<ImportDialog onImported={onImported} />);
    fireEvent.click(screen.getByRole('button', { name: /import source/i }));

    const urlInput = await screen.findByLabelText(/url/i);
    const nameInput = screen.getByLabelText(/name/i);

    fireEvent.change(urlInput, { target: { value: 'https://example.com/feed.xml' } });
    fireEvent.change(nameInput, { target: { value: 'Test Podcast' } });

    const submitButton = screen.getByRole('button', { name: /import$/i });
    fireEvent.click(submitButton);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /importing/i })).toBeDisabled();
    });
  });

  it('calls createSource on submit', async () => {
    const { createSource } = await import('@/lib/api');
    const mockedCreateSource = vi.mocked(createSource);
    mockedCreateSource.mockResolvedValue({ source_id: 's1', job_id: 'j1' });

    render(<ImportDialog onImported={onImported} />);
    fireEvent.click(screen.getByRole('button', { name: /import source/i }));

    const urlInput = await screen.findByLabelText(/url/i);
    const nameInput = screen.getByLabelText(/name/i);

    fireEvent.change(urlInput, { target: { value: 'https://example.com/feed.xml' } });
    fireEvent.change(nameInput, { target: { value: 'Test Podcast' } });

    fireEvent.click(screen.getByRole('button', { name: /import$/i }));

    await waitFor(() => {
      expect(mockedCreateSource).toHaveBeenCalledWith({
        sourceType: 'PODCAST',
        name: 'Test Podcast',
        originUrl: 'https://example.com/feed.xml',
      });
    });
  });
});
