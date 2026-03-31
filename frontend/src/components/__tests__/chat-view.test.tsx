import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ChatView } from '../chat-view';

Element.prototype.scrollIntoView = vi.fn();

vi.mock('@/lib/api', () => ({
  getConversation: vi.fn(),
  streamQuery: vi.fn(),
}));

vi.mock('@/components/ui/scroll-area', () => ({
  ScrollArea: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

const { getConversation } = await import('@/lib/api');
const mockGetConversation = vi.mocked(getConversation);

describe('ChatView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows empty state when no conversationId', () => {
    render(<ChatView />);
    expect(screen.getByText('Ask anything')).toBeInTheDocument();
  });

  it('loads messages when mounting with a conversationId', async () => {
    mockGetConversation.mockResolvedValue({
      id: 'conv-1',
      title: 'Test',
      createdAt: '2025-01-01T00:00:00Z',
      updatedAt: '2025-01-01T00:00:00Z',
      messages: [
        { id: 'msg-1', role: 'USER', content: 'Hello', createdAt: '2025-01-01T00:00:00Z' },
        {
          id: 'msg-2',
          role: 'ASSISTANT',
          content: 'Hi there!',
          createdAt: '2025-01-01T00:00:01Z',
        },
      ],
    });

    render(<ChatView conversationId="conv-1" />);

    await waitFor(() => {
      expect(screen.getByText('Hello')).toBeInTheDocument();
      expect(screen.getByText('Hi there!')).toBeInTheDocument();
    });

    expect(mockGetConversation).toHaveBeenCalledWith('conv-1');
    expect(screen.queryByText('Ask anything')).not.toBeInTheDocument();
  });

  it('does not show empty state while loading conversation', () => {
    mockGetConversation.mockReturnValue(new Promise(() => {}));

    render(<ChatView conversationId="conv-1" />);

    expect(screen.queryByText('Ask anything')).not.toBeInTheDocument();
  });

  it('shows empty state when conversation fails to load', async () => {
    mockGetConversation.mockRejectedValue(new Error('404: Not Found'));

    render(<ChatView conversationId="conv-1" />);

    await waitFor(() => {
      expect(screen.getByText('Ask anything')).toBeInTheDocument();
    });
  });
});
