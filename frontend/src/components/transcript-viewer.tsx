'use client';

import { useMemo, useState } from 'react';
import { SearchIcon } from 'lucide-react';

import { Input } from '@/components/ui/input';
import { ScrollArea } from '@/components/ui/scroll-area';
import type { ContentFragment, SegmentLocation } from '@/lib/types';
import { formatTimestamp } from '@/lib/utils';

interface TranscriptViewerProps {
  readonly fragments: ContentFragment[];
  readonly rawText?: string;
}

function parseLocation(location?: string): SegmentLocation | undefined {
  if (!location) return undefined;
  try {
    const parsed: unknown = JSON.parse(location);
    if (
      typeof parsed === 'object' &&
      parsed !== null &&
      'startTime' in parsed &&
      typeof (parsed as SegmentLocation).startTime === 'number'
    ) {
      return parsed as SegmentLocation;
    }
  } catch {
    // Not JSON — ignore
  }
  return undefined;
}

function highlightText(text: string, query: string): React.ReactNode[] {
  if (!query) return [text];
  const escaped = query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const regex = new RegExp(`(${escaped})`, 'gi');
  const parts = text.split(regex);
  return parts.map((part, i) =>
    regex.test(part) ? (
      <mark key={i} className="bg-yellow-200 dark:bg-yellow-800 rounded-sm px-0.5">
        {part}
      </mark>
    ) : (
      part
    ),
  );
}

export function TranscriptViewer({ fragments, rawText }: TranscriptViewerProps) {
  const [search, setSearch] = useState('');

  const hasFragments = fragments.length > 0;

  const matchCount = useMemo(() => {
    if (!search) return 0;
    const escaped = search.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const regex = new RegExp(escaped, 'gi');
    if (hasFragments) {
      return fragments.reduce((count, f) => count + (f.text.match(regex)?.length ?? 0), 0);
    }
    return rawText?.match(regex)?.length ?? 0;
  }, [search, fragments, rawText, hasFragments]);

  if (!hasFragments && !rawText) {
    return (
      <p className="text-muted-foreground py-4 text-center text-sm">
        No transcript available.
      </p>
    );
  }

  return (
    <div className="space-y-3">
      <div className="relative">
        <SearchIcon className="text-muted-foreground absolute left-3 top-1/2 size-4 -translate-y-1/2" />
        <Input
          placeholder="Search transcript..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="pl-9"
          aria-label="Search transcript"
        />
        {search && (
          <span className="text-muted-foreground absolute right-3 top-1/2 -translate-y-1/2 text-xs">
            {matchCount} {matchCount === 1 ? 'match' : 'matches'}
          </span>
        )}
      </div>

      <ScrollArea className="h-80 rounded-md border p-4">
        {hasFragments ? (
          <div className="space-y-2">
            {fragments.map((fragment) => {
              const loc = parseLocation(fragment.location);
              return (
                <div key={fragment.id} className="flex gap-3 text-sm">
                  {loc && (
                    <span className="text-muted-foreground shrink-0 font-mono text-xs leading-relaxed">
                      {formatTimestamp(loc.startTime)}
                    </span>
                  )}
                  <p className="leading-relaxed">{highlightText(fragment.text, search)}</p>
                </div>
              );
            })}
          </div>
        ) : (
          <p className="whitespace-pre-wrap text-sm leading-relaxed">
            {highlightText(rawText ?? '', search)}
          </p>
        )}
      </ScrollArea>
    </div>
  );
}
