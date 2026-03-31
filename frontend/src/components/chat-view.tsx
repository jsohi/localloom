'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { ScrollArea } from '@/components/ui/scroll-area';
import { ChatMessage } from '@/components/chat-message';
import { ChatInput } from '@/components/chat-input';
import { CitationPanel } from '@/components/citation-panel';
import { getConversation, streamQuery, type Citation } from '@/lib/api';

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
  const bottomRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  // Load existing messages when mounting with a conversationId
  useEffect(() => {
    if (!conversationId) return;
    getConversation(conversationId)
      .then((conv) => {
        setMessages(
          conv.messages.map((m) => ({ id: m.id, role: m.role, content: m.content })),
        );
      })
      .catch(() => {
        // Conversation may have been deleted
      });
  }, [conversationId]);

  // Abort any active stream on unmount
  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, streamingContent]);

  const handleSend = useCallback(
    (question: string) => {
      // Abort any previous stream
      abortRef.current?.abort();

      const userMsg: ChatMessageData = {
        id: crypto.randomUUID(),
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
        onDone: (messageId, newConversationId) => {
          setMessages((prev) => [
            ...prev,
            { id: messageId, role: 'ASSISTANT', content: accumulated },
          ]);
          setStreamingContent('');
          setIsStreaming(false);

          if (!conversationId && newConversationId && onConversationCreated) {
            onConversationCreated(newConversationId);
          }
        },
        onError: (error) => {
          setMessages((prev) => [
            ...prev,
            { id: crypto.randomUUID(), role: 'ASSISTANT', content: `Error: ${error.message}` },
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
      <ScrollArea className="flex-1 px-4">
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
            <ChatMessage key={msg.id} id={msg.id} role={msg.role} content={msg.content} />
          ))}
          {isStreaming && streamingContent && (
            <ChatMessage role="ASSISTANT" content={streamingContent} isStreaming />
          )}
          <div ref={bottomRef} />
        </div>
      </ScrollArea>
      <CitationPanel citations={citations} />
      <div className="mx-auto w-full max-w-3xl">
        <ChatInput onSend={handleSend} disabled={isStreaming} />
      </div>
    </div>
  );
}
