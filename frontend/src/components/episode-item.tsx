'use client';

import { useCallback, useEffect, useState } from 'react';
import { ChevronDownIcon, ChevronRightIcon } from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { TranscriptViewer } from '@/components/transcript-viewer';
import { getContentFragments } from '@/lib/api';
import type { ContentFragment, ContentUnit, ContentUnitStatus } from '@/lib/types';

interface EpisodeItemProps {
  readonly episode: ContentUnit;
}

function statusVariant(status: ContentUnitStatus): 'default' | 'secondary' | 'destructive' | 'outline' {
  switch (status) {
    case 'INDEXED':
      return 'default';
    case 'ERROR':
      return 'destructive';
    case 'PENDING':
      return 'outline';
    default:
      return 'secondary';
  }
}

export function EpisodeItem({ episode }: EpisodeItemProps) {
  const [expanded, setExpanded] = useState(false);
  const [fragments, setFragments] = useState<ContentFragment[]>([]);
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);

  const loadFragments = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getContentFragments(episode.id);
      setFragments(data);
      setLoaded(true);
    } finally {
      setLoading(false);
    }
  }, [episode.id]);

  useEffect(() => {
    if (expanded && !loaded) {
      loadFragments();
    }
  }, [expanded, loaded, loadFragments]);

  return (
    <div className="rounded-lg border">
      <button
        type="button"
        onClick={() => setExpanded((prev) => !prev)}
        className="flex w-full items-center gap-3 p-4 text-left hover:bg-muted/50 transition-colors"
        aria-expanded={expanded}
      >
        {expanded ? (
          <ChevronDownIcon className="text-muted-foreground size-4 shrink-0" />
        ) : (
          <ChevronRightIcon className="text-muted-foreground size-4 shrink-0" />
        )}
        <div className="min-w-0 flex-1">
          <p className="truncate font-medium text-sm">
            {episode.title ?? 'Untitled episode'}
          </p>
          {episode.publishedAt && (
            <p className="text-muted-foreground text-xs" suppressHydrationWarning>
              {new Date(episode.publishedAt).toLocaleDateString()}
            </p>
          )}
        </div>
        <Badge variant={statusVariant(episode.status)}>{episode.status}</Badge>
      </button>

      {expanded && (
        <div className="border-t px-4 pb-4 pt-3">
          {loading ? (
            <div className="space-y-2">
              <Skeleton className="h-4 w-full" />
              <Skeleton className="h-4 w-3/4" />
              <Skeleton className="h-4 w-5/6" />
            </div>
          ) : (
            <TranscriptViewer fragments={fragments} rawText={episode.rawText} />
          )}
        </div>
      )}
    </div>
  );
}
