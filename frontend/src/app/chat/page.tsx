'use client';

import { useCallback, useEffect, useState } from 'react';
import { MessageSquareIcon, PlusIcon, Trash2Icon } from 'lucide-react';
import { toast } from 'sonner';

import { ChatView } from '@/components/chat-view';
import { Button } from '@/components/ui/button';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import { SidebarTrigger } from '@/components/ui/sidebar';
import { Skeleton } from '@/components/ui/skeleton';
import { deleteConversation, getConversations } from '@/lib/api';
import type { Conversation } from '@/lib/types';

export default function ChatPage() {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeId, setActiveId] = useState<string | undefined>();

  const loadConversations = useCallback(async () => {
    try {
      const data = await getConversations();
      setConversations(data);
    } catch {
      // Conversations API may not be available yet
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadConversations();
  }, [loadConversations]);

  function handleNewChat() {
    setActiveId(undefined);
  }

  async function handleDelete(id: string) {
    try {
      await deleteConversation(id);
      setConversations((prev) => prev.filter((c) => c.id !== id));
      if (activeId === id) setActiveId(undefined);
      toast.success('Conversation deleted');
    } catch {
      toast.error('Failed to delete conversation');
    }
  }

  return (
    <div className="flex h-[calc(100vh-0px)]">
      {/* Conversation list sidebar */}
      <div className="flex w-64 shrink-0 flex-col border-r">
        <div className="flex h-16 items-center gap-2 border-b px-4">
          <SidebarTrigger className="-ml-1" />
          <Separator orientation="vertical" className="mr-2 h-4" />
          <h1 className="text-lg font-semibold">Chat</h1>
          <Button variant="ghost" size="icon" className="ml-auto" onClick={handleNewChat}>
            <PlusIcon className="size-4" />
            <span className="sr-only">New chat</span>
          </Button>
        </div>
        <ScrollArea className="flex-1">
          <div className="space-y-1 p-2">
            {loading ? (
              <>
                <Skeleton className="h-10 w-full" />
                <Skeleton className="h-10 w-full" />
              </>
            ) : conversations.length === 0 ? (
              <p className="px-2 py-4 text-center text-xs text-muted-foreground">
                No conversations yet
              </p>
            ) : (
              conversations.map((c) => (
                <div
                  key={c.id}
                  className={`group flex cursor-pointer items-center gap-2 rounded-lg px-3 py-2 text-sm transition-colors hover:bg-muted ${
                    activeId === c.id ? 'bg-muted font-medium' : ''
                  }`}
                  onClick={() => setActiveId(c.id)}
                >
                  <MessageSquareIcon className="size-4 shrink-0 text-muted-foreground" />
                  <span className="truncate">{c.title ?? 'Untitled'}</span>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="ml-auto size-6 opacity-0 transition-opacity group-hover:opacity-100"
                    onClick={(e) => {
                      e.stopPropagation();
                      handleDelete(c.id);
                    }}
                  >
                    <Trash2Icon className="size-3" />
                  </Button>
                </div>
              ))
            )}
          </div>
        </ScrollArea>
      </div>

      {/* Chat area */}
      <div className="flex-1">
        <ChatView key={activeId ?? 'new'} conversationId={activeId} onConversationCreated={loadConversations} />
      </div>
    </div>
  );
}
