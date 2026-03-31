import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AudioPlayer } from '../audio-player';

// Radix Slider uses ResizeObserver internally
vi.stubGlobal(
  'ResizeObserver',
  class {
    observe() {}
    unobserve() {}
    disconnect() {}
  },
);

// Mock HTML5 Audio
function createMockAudio() {
  return {
    play: vi.fn().mockResolvedValue(undefined),
    pause: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    currentTime: 0,
    duration: 120,
    playbackRate: 1,
    src: '',
  };
}

let lastMockAudio: ReturnType<typeof createMockAudio>;

vi.stubGlobal(
  'Audio',
  function MockAudio() {
    lastMockAudio = createMockAudio();
    return lastMockAudio;
  },
);

describe('AudioPlayer', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders play button and seek slider', () => {
    render(<AudioPlayer audioUrl="/api/v1/audio/test.wav" />);

    expect(screen.getByRole('button', { name: 'Play' })).toBeInTheDocument();
    expect(screen.getByLabelText('Seek')).toBeInTheDocument();
    // Both current time and duration show 0:00 initially
    expect(screen.getAllByText('0:00')).toHaveLength(2);
  });

  it('toggles play/pause on click', async () => {
    const user = userEvent.setup();
    render(<AudioPlayer audioUrl="/api/v1/audio/test.wav" />);

    const playButton = screen.getByRole('button', { name: 'Play' });
    await user.click(playButton);

    expect(screen.getByRole('button', { name: 'Pause' })).toBeInTheDocument();
  });

  it('renders speed control defaulting to 1x', () => {
    render(<AudioPlayer audioUrl="/api/v1/audio/test.wav" />);

    expect(screen.getByRole('button', { name: 'Playback speed 1x' })).toBeInTheDocument();
  });

  it('cycles through speed options on click', async () => {
    const user = userEvent.setup();
    render(<AudioPlayer audioUrl="/api/v1/audio/test.wav" />);

    const speedButton = screen.getByRole('button', { name: 'Playback speed 1x' });
    await user.click(speedButton);

    expect(screen.getByRole('button', { name: 'Playback speed 1.25x' })).toBeInTheDocument();
  });

  it('shows error message when audio fails to load', () => {
    render(<AudioPlayer audioUrl="/api/v1/audio/bad.wav" />);

    // Find and invoke the error handler registered via addEventListener
    expect(lastMockAudio.addEventListener).toHaveBeenCalledWith('error', expect.any(Function));
    const errorCall = lastMockAudio.addEventListener.mock.calls.find(
      (call: unknown[]) => call[0] === 'error',
    );
    if (!errorCall) return;

    const errorHandler = errorCall[1] as () => void;
    act(() => {
      errorHandler();
    });

    expect(screen.getByRole('alert')).toHaveTextContent('Failed to load audio');
  });
});
