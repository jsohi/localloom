'use client';

import { useState } from 'react';
import { SendIcon } from 'lucide-react';
import { Button } from '@/components/ui/button';

interface ChatInputProps {
  readonly onSend: (message: string) => void;
  readonly disabled?: boolean;
}

export function ChatInput({ onSend, disabled }: ChatInputProps) {
  const [input, setInput] = useState('');

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const trimmed = input.trim();
    if (!trimmed || disabled) return;
    onSend(trimmed);
    setInput('');
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex gap-2 border-t p-4">
      <textarea
        value={input}
        onChange={(e) => setInput(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder="Ask a question about your knowledge base..."
        disabled={disabled}
        rows={1}
        className="bg-muted flex-1 resize-none rounded-lg px-4 py-3 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
      />
      <Button type="submit" size="icon" disabled={disabled || !input.trim()}>
        <SendIcon className="size-4" />
        <span className="sr-only">Send</span>
      </Button>
    </form>
  );
}
