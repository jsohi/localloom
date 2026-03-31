'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { ScrollArea } from '@/components/ui/scroll-area';
import { ChatMessage } from '@/components/chat-message';
import { ChatInput } from '@/components/chat-input';
import { CitationPanel } from '@/components/citation-panel';
import { streamQuery, type Citation } from '@/lib/api';

interface ChatMessageData {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
}

interface ChatViewProps {
  readonly conversationId?: string;
  readonly onConversationCreated?: (id: string) => void;
}

export function ChatView({ conversationId, onConversationCreated }: ChatViewProps) {
  const [messages, setMessages] = useState<ChatMessageData[]>([]);
  const [streamingContent, setStreamingContent] = useState('');
  const [isStreaming, setIsStreaming] = useState(false);
  const [citations, setCitations] = useState<Citation[]>([]);
  const scrollRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages, streamingContent]);

  const handleSend = useCallback(
    (question: string) => {
      const userMsg: ChatMessageData = {
        id: `user-${Date.now()}`,
        role: 'USER',
        content: question,
      };
      setMessages((prev) => [...prev, userMsg]);
      setStreamingContent('');
      setCitations([]);
      setIsStreaming(true);

      let accumulated = '';

      const controller = streamQuery({
        question,
        conversationId,
        onToken: (content) => {
          accumulated += content;
          setStreamingContent(accumulated);
        },
        onSources: (sources) => {
          setCitations(sources);
        },
        onDone: (messageId) => {
          setMessages((prev) => [
            ...prev,
            { id: messageId, role: 'ASSISTANT', content: accumulated },
          ]);
          setStreamingContent('');
          setIsStreaming(false);

          if (!conversationId && onConversationCreated) {
            onConversationCreated(messageId);
          }
        },
        onError: (error) => {
          setMessages((prev) => [
            ...prev,
            { id: `error-${Date.now()}`, role: 'ASSISTANT', content: `Error: ${error.message}` },
          ]);
          setStreamingContent('');
          setIsStreaming(false);
        },
      });

      abortRef.current = controller;
    },
    [conversationId, onConversationCreated],
  );

  return (
    <div className="flex h-full flex-col">
      <ScrollArea className="flex-1 px-4" ref={scrollRef}>
        <div className="mx-auto max-w-3xl py-4">
          {messages.length === 0 && !isStreaming && (
            <div className="flex flex-col items-center justify-center py-24 text-center">
              <h2 className="text-2xl font-bold">Ask anything</h2>
              <p className="text-muted-foreground mt-2 text-sm">
                Query your knowledge base across all indexed sources.
              </p>
            </div>
          )}
          {messages.map((msg) => (
            <ChatMessage key={msg.id} role={msg.role} content={msg.content} />
          ))}
          {isStreaming && streamingContent && (
            <ChatMessage role="ASSISTANT" content={streamingContent} isStreaming />
          )}
        </div>
      </ScrollArea>
      <CitationPanel citations={citations} />
      <div className="mx-auto w-full max-w-3xl">
        <ChatInput onSend={handleSend} disabled={isStreaming} />
      </div>
    </div>
  );
}
