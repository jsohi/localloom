'use client';

import { BotIcon, UserIcon } from 'lucide-react';
import { cn } from '@/lib/utils';

interface ChatMessageProps {
  readonly role: 'USER' | 'ASSISTANT';
  readonly content: string;
  readonly isStreaming?: boolean;
}

export function ChatMessage({ role, content, isStreaming }: ChatMessageProps) {
  const isUser = role === 'USER';

  return (
    <div className={cn('flex gap-3 py-4', isUser ? 'justify-end' : 'justify-start')}>
      {!isUser && (
        <div className="bg-primary text-primary-foreground flex size-8 shrink-0 items-center justify-center rounded-full">
          <BotIcon className="size-4" />
        </div>
      )}
      <div
        className={cn(
          'max-w-[80%] rounded-xl px-4 py-3',
          isUser ? 'bg-primary text-primary-foreground' : 'bg-muted',
        )}
      >
        <p className="whitespace-pre-wrap text-sm">{content}</p>
        {isStreaming && (
          <span className="mt-1 inline-block size-2 animate-pulse rounded-full bg-current" />
        )}
      </div>
      {isUser && (
        <div className="bg-muted flex size-8 shrink-0 items-center justify-center rounded-full">
          <UserIcon className="size-4" />
        </div>
      )}
    </div>
  );
}
