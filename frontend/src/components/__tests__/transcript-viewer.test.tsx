import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect } from 'vitest';
import { TranscriptViewer } from '../transcript-viewer';
import type { ContentFragment } from '@/lib/types';

function makeFragment(overrides: Partial<ContentFragment> & { id: number }): ContentFragment {
  return {
    fragmentType: 'TRANSCRIPT',
    sequenceIndex: overrides.id,
    text: 'Default fragment text',
    ...overrides,
  };
}

describe('TranscriptViewer', () => {
  it('renders fragments with timestamps', () => {
    const fragments: ContentFragment[] = [
      makeFragment({
        id: 1,
        text: 'Hello world',
        location: JSON.stringify({ startTime: 65, endTime: 70 }),
      }),
      makeFragment({
        id: 2,
        text: 'Second segment',
        location: JSON.stringify({ startTime: 130, endTime: 140 }),
      }),
    ];

    render(<TranscriptViewer fragments={fragments} />);
    expect(screen.getByText('Hello world')).toBeInTheDocument();
    expect(screen.getByText('Second segment')).toBeInTheDocument();
    expect(screen.getByText('1:05')).toBeInTheDocument();
    expect(screen.getByText('2:10')).toBeInTheDocument();
  });

  it('renders rawText fallback when no fragments', () => {
    render(<TranscriptViewer fragments={[]} rawText="Fallback raw text content" />);
    expect(screen.getByText('Fallback raw text content')).toBeInTheDocument();
  });

  it('shows empty message when no fragments and no rawText', () => {
    render(<TranscriptViewer fragments={[]} />);
    expect(screen.getByText('No transcript available.')).toBeInTheDocument();
  });

  it('highlights search matches in fragments', async () => {
    const user = userEvent.setup();
    const fragments: ContentFragment[] = [
      makeFragment({ id: 1, text: 'The quick brown fox jumps over the lazy dog' }),
    ];

    render(<TranscriptViewer fragments={fragments} />);

    const input = screen.getByLabelText('Search transcript');
    await user.type(input, 'fox');

    const marks = document.querySelectorAll('mark');
    expect(marks).toHaveLength(1);
    expect(marks[0]?.textContent).toBe('fox');
  });

  it('highlights search matches in rawText', async () => {
    const user = userEvent.setup();
    render(<TranscriptViewer fragments={[]} rawText="apple banana apple cherry" />);

    const input = screen.getByLabelText('Search transcript');
    await user.type(input, 'apple');

    const marks = document.querySelectorAll('mark');
    expect(marks).toHaveLength(2);
    expect(screen.getByText('2 matches')).toBeInTheDocument();
  });

  it('shows match count for search', async () => {
    const user = userEvent.setup();
    const fragments: ContentFragment[] = [
      makeFragment({ id: 1, text: 'test one test two' }),
      makeFragment({ id: 2, text: 'test three' }),
    ];

    render(<TranscriptViewer fragments={fragments} />);

    const input = screen.getByLabelText('Search transcript');
    await user.type(input, 'test');

    expect(screen.getByText('3 matches')).toBeInTheDocument();
  });

  it('shows singular match text', async () => {
    const user = userEvent.setup();
    render(<TranscriptViewer fragments={[]} rawText="unique word here" />);

    const input = screen.getByLabelText('Search transcript');
    await user.type(input, 'unique');

    expect(screen.getByText('1 match')).toBeInTheDocument();
  });

  it('formats hour-long timestamps correctly', () => {
    const fragments: ContentFragment[] = [
      makeFragment({
        id: 1,
        text: 'Long episode',
        location: JSON.stringify({ startTime: 3661, endTime: 3700 }),
      }),
    ];

    render(<TranscriptViewer fragments={fragments} />);
    expect(screen.getByText('1:01:01')).toBeInTheDocument();
  });
});
