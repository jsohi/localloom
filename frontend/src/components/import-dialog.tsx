'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { PlusIcon } from 'lucide-react';
import { toast } from 'sonner';

import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { createSource, detectUrl, uploadFile } from '@/lib/api';
import type { DetectUrlResponse } from '@/lib/types';

interface ImportDialogProps {
  readonly onImported: () => void;
}

type ImportMode = 'url' | 'file';

const DETECT_DISPLAY: Record<string, string> = {
  YOUTUBE: 'YouTube video',
  MEDIA: 'Media / podcast feed',
  WEB_PAGE: 'Web page',
};

function nameFromUrl(url: string): string {
  try {
    const parsed = new URL(url);
    const segments = parsed.pathname.split('/').filter(Boolean);
    if (segments.length > 0) {
      return decodeURIComponent(segments[segments.length - 1]).replace(/[-_]/g, ' ');
    }
    return parsed.hostname;
  } catch {
    return '';
  }
}

export function ImportDialog({ onImported }: ImportDialogProps) {
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<ImportMode>('url');
  const [url, setUrl] = useState('');
  const [name, setName] = useState('');
  const [maxEpisodes, setMaxEpisodes] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [detection, setDetection] = useState<DetectUrlResponse | null>(null);
  const [detecting, setDetecting] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout>>(undefined);
  const detectionCounterRef = useRef(0);

  const showMaxEpisodes = detection?.sourceType === 'MEDIA' || detection?.sourceType === 'YOUTUBE';

  const detectedLabel = detection
    ? (DETECT_DISPLAY[detection.sourceType] ?? detection.sourceType)
    : null;

  const runDetection = useCallback(async (urlValue: string) => {
    if (!urlValue.trim()) {
      setDetection(null);
      return;
    }
    // Monotonic counter prevents stale out-of-order responses from overwriting newer results
    const requestId = ++detectionCounterRef.current;
    try {
      setDetecting(true);
      const result = await detectUrl(urlValue.trim());
      if (detectionCounterRef.current === requestId) {
        setDetection(result);
      }
    } catch {
      if (detectionCounterRef.current === requestId) {
        setDetection(null);
      }
    } finally {
      if (detectionCounterRef.current === requestId) {
        setDetecting(false);
      }
    }
  }, []);

  function handleUrlChange(value: string) {
    setUrl(value);
    const derived = nameFromUrl(value);
    if (derived && !name) {
      setName(derived);
    }
    // Debounce detection
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => runDetection(value), 400);
  }

  function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const selected = e.target.files?.[0] ?? null;
    setFile(selected);
    if (selected && !name) {
      setName(selected.name.replace(/\.[^.]+$/, '').replace(/[-_]/g, ' '));
    }
  }

  function resetForm() {
    setMode('url');
    setUrl('');
    setName('');
    setMaxEpisodes('');
    setFile(null);
    setSubmitting(false);
    setDetection(null);
    setDetecting(false);
    if (fileInputRef.current) fileInputRef.current.value = '';
    if (debounceRef.current) clearTimeout(debounceRef.current);
  }

  // Cleanup debounce on unmount
  useEffect(() => {
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, []);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();

    if (!name.trim()) {
      toast.error('Please provide a name');
      return;
    }

    if (mode === 'file' && !file) {
      toast.error('Please select a file');
      return;
    }
    if (mode === 'url' && !url.trim()) {
      toast.error('Please provide a URL');
      return;
    }

    setSubmitting(true);
    try {
      let response;
      if (mode === 'file' && file) {
        response = await uploadFile(file, name.trim(), 'FILE_UPLOAD');
      } else {
        const parsed = maxEpisodes ? parseInt(maxEpisodes, 10) : undefined;
        response = await createSource({
          name: name.trim(),
          originUrl: url.trim(),
          maxEpisodes: parsed && parsed > 0 ? parsed : undefined,
        });
      }
      toast.success(`Source created! Job ${response.job_id} started.`);
      resetForm();
      setOpen(false);
      onImported();
    } catch {
      toast.error('Failed to import source. Please try again.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(value) => {
        setOpen(value);
        if (!value) resetForm();
      }}
    >
      <DialogTrigger asChild>
        <Button>
          <PlusIcon className="mr-2 size-4" />
          Import Source
        </Button>
      </DialogTrigger>
      <DialogContent className="sm:max-w-md">
        <form onSubmit={handleSubmit}>
          <DialogHeader>
            <DialogTitle>Import Source</DialogTitle>
            <DialogDescription>
              {mode === 'url'
                ? "Paste any URL — YouTube, podcast feed, web page — and we'll detect the type automatically."
                : 'Upload a PDF, text, or other document file.'}
            </DialogDescription>
          </DialogHeader>

          <div className="mt-4 space-y-4">
            <div className="flex gap-2">
              <Button
                type="button"
                variant={mode === 'url' ? 'default' : 'outline'}
                size="sm"
                onClick={() => setMode('url')}
              >
                Paste a URL
              </Button>
              <Button
                type="button"
                variant={mode === 'file' ? 'default' : 'outline'}
                size="sm"
                onClick={() => setMode('file')}
              >
                Upload a File
              </Button>
            </div>

            {mode === 'url' && (
              <div className="space-y-2">
                <label htmlFor="import-url" className="text-sm font-medium">
                  URL
                </label>
                <Input
                  id="import-url"
                  placeholder="https://youtube.com/watch?v=... or any URL"
                  value={url}
                  onChange={(e) => handleUrlChange(e.target.value)}
                  disabled={submitting}
                />
                {detecting && <p className="text-muted-foreground text-xs">Detecting type...</p>}
                {detectedLabel && !detecting && (
                  <p className="text-xs">
                    Detected:{' '}
                    <span className="bg-muted rounded px-1.5 py-0.5 font-medium">
                      {detectedLabel}
                    </span>
                  </p>
                )}
              </div>
            )}

            {mode === 'file' && (
              <div className="space-y-2">
                <label htmlFor="import-file" className="text-sm font-medium">
                  File
                </label>
                <Input
                  id="import-file"
                  ref={fileInputRef}
                  type="file"
                  accept=".pdf,.txt,.md,.csv,.json"
                  onChange={handleFileChange}
                  disabled={submitting}
                />
              </div>
            )}

            <div className="space-y-2">
              <label htmlFor="import-name" className="text-sm font-medium">
                Name
              </label>
              <Input
                id="import-name"
                placeholder="My Source"
                value={name}
                onChange={(e) => setName(e.target.value)}
                disabled={submitting}
              />
            </div>

            {mode === 'url' && showMaxEpisodes && (
              <div className="space-y-2">
                <label htmlFor="import-max-episodes" className="text-sm font-medium">
                  Max Episodes{' '}
                  <span className="text-muted-foreground font-normal">(optional, 0 = all)</span>
                </label>
                <Input
                  id="import-max-episodes"
                  type="number"
                  min="0"
                  placeholder="0"
                  value={maxEpisodes}
                  onChange={(e) => setMaxEpisodes(e.target.value)}
                  disabled={submitting}
                />
              </div>
            )}
          </div>

          <DialogFooter className="mt-6">
            <Button type="submit" disabled={submitting}>
              {submitting ? 'Importing...' : mode === 'file' ? 'Upload & Import' : 'Import'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
