'use client';

import { FileTextIcon, MicIcon } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import type { Citation } from '@/lib/api';

interface CitationPanelProps {
  readonly citations: Citation[];
}

export function CitationPanel({ citations }: CitationPanelProps) {
  if (citations.length === 0) return null;

  return (
    <div className="space-y-2 border-t p-4">
      <h4 className="text-muted-foreground text-xs font-medium">Sources</h4>
      <div className="flex flex-wrap gap-2">
        {citations.map((c, i) => (
          <div
            key={`${c.sourceId}-${c.contentUnitId}-${i}`}
            className="bg-muted/50 flex items-center gap-2 rounded-lg border px-3 py-2 text-xs"
          >
            {c.sourceType === 'MEDIA' || c.sourceType === 'YOUTUBE' ? (
              <MicIcon className="size-3 shrink-0" />
            ) : (
              <FileTextIcon className="size-3 shrink-0" />
            )}
            <span className="truncate font-medium">{c.contentUnitTitle ?? 'Unknown'}</span>
            {c.location && (
              <Badge variant="outline" className="text-[10px]">
                {c.location}
              </Badge>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
