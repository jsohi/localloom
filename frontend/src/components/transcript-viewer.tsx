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

function highlightText(text: string, regex: RegExp | null): React.ReactNode[] {
  if (!regex) return [text];
  const parts = text.split(regex);
  return parts.map((part, i) =>
    regex.test(part) ? (
      <mark key={i} className="rounded-sm bg-yellow-200 px-0.5 dark:bg-yellow-800">
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

  const searchRegex = useMemo(() => {
    if (!search) return null;
    const escaped = search.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return new RegExp(`(${escaped})`, 'gi');
  }, [search]);

  const matchCount = useMemo(() => {
    if (!searchRegex) return 0;
    const countRegex = new RegExp(searchRegex.source, 'gi');
    if (hasFragments) {
      return fragments.reduce((count, f) => count + (f.text.match(countRegex)?.length ?? 0), 0);
    }
    return rawText?.match(countRegex)?.length ?? 0;
  }, [searchRegex, fragments, rawText, hasFragments]);

  if (!hasFragments && !rawText) {
    return (
      <p className="text-muted-foreground py-4 text-center text-sm">No transcript available.</p>
    );
  }

  return (
    <div className="space-y-3">
      <div className="relative">
        <SearchIcon className="text-muted-foreground absolute top-1/2 left-3 size-4 -translate-y-1/2" />
        <Input
          placeholder="Search transcript..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="pl-9"
          aria-label="Search transcript"
        />
        {search && (
          <span className="text-muted-foreground absolute top-1/2 right-3 -translate-y-1/2 text-xs">
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
                  <p className="leading-relaxed">{highlightText(fragment.text, searchRegex)}</p>
                </div>
              );
            })}
          </div>
        ) : (
          <p className="text-sm leading-relaxed whitespace-pre-wrap">
            {highlightText(rawText ?? '', searchRegex)}
          </p>
        )}
      </ScrollArea>
    </div>
  );
}
