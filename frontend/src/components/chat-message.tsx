'use client';

import { useCallback, useState } from 'react';
import { BotIcon, LoaderCircleIcon, Volume2Icon, UserIcon } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { AudioPlayer } from '@/components/audio-player';
import { generateTts } from '@/lib/api';

interface ChatMessageProps {
  readonly id?: string;
  readonly role: 'USER' | 'ASSISTANT';
  readonly content: string;
  readonly isStreaming?: boolean;
}

export function ChatMessage({ id, role, content, isStreaming }: ChatMessageProps) {
  const isUser = role === 'USER';
  const [audioUrl, setAudioUrl] = useState<string | null>(null);
  const [ttsLoading, setTtsLoading] = useState(false);
  const [ttsError, setTtsError] = useState<string | null>(null);

  const handleListen = useCallback(async () => {
    if (!id || ttsLoading) return;

    setTtsLoading(true);
    setTtsError(null);
    setAudioUrl(null);

    try {
      const response = await generateTts(id);
      setAudioUrl(response.audio_url);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'TTS generation failed';
      setTtsError(message);
    } finally {
      setTtsLoading(false);
    }
  }, [id, ttsLoading]);

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
        <p className="text-sm whitespace-pre-wrap">{content}</p>
        {isStreaming && (
          <span className="mt-1 inline-block size-2 animate-pulse rounded-full bg-current" />
        )}
        {!isUser && !isStreaming && id && (
          <div className="mt-2">
            {!audioUrl && (
              <Button
                variant="ghost"
                size="sm"
                className="h-7 gap-1.5 px-2 text-xs"
                onClick={handleListen}
                disabled={ttsLoading}
              >
                {ttsLoading ? (
                  <LoaderCircleIcon className="size-3.5 animate-spin" />
                ) : (
                  <Volume2Icon className="size-3.5" />
                )}
                {ttsLoading ? 'Generating...' : 'Listen'}
              </Button>
            )}
            {ttsError && (
              <p className="text-destructive mt-1 text-xs" role="alert">
                {ttsError}
              </p>
            )}
            {audioUrl && <AudioPlayer audioUrl={audioUrl} />}
          </div>
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
