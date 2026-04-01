'use client';

import { useEffect, useRef, useState } from 'react';
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { createSource, getConnectors, uploadFile } from '@/lib/api';
import type { ConnectorInfo } from '@/lib/types';
import { SourceType } from '@/lib/types';

interface ImportDialogProps {
  readonly onImported: () => void;
}

const TYPE_CONFIG: Record<
  string,
  {
    displayName: string;
    description: string;
    urlLabel: string;
    urlPlaceholder: string;
    submitLabel: string;
    hasUrl: boolean;
    hasFile: boolean;
    hasMaxEpisodes: boolean;
    fileAccept?: string;
  }
> = {
  [SourceType.PODCAST]: {
    displayName: 'Podcast',
    description: 'Paste a podcast feed URL, YouTube, Apple Podcasts, or Spotify link.',
    urlLabel: 'Feed URL',
    urlPlaceholder: 'https://example.com/feed.xml',
    submitLabel: 'Import Podcast',
    hasUrl: true,
    hasFile: false,
    hasMaxEpisodes: true,
  },
  [SourceType.FILE_UPLOAD]: {
    displayName: 'File Upload',
    description: 'Upload a PDF, text, or other document file.',
    urlLabel: 'File URL',
    urlPlaceholder: 'https://example.com/document.pdf',
    submitLabel: 'Upload & Import',
    hasUrl: true,
    hasFile: true,
    hasMaxEpisodes: false,
    fileAccept: '.pdf,.txt,.md,.csv,.json',
  },
  [SourceType.WEB_PAGE]: {
    displayName: 'Web Page',
    description: 'Import content from any web page by URL.',
    urlLabel: 'Page URL',
    urlPlaceholder: 'https://example.com/page',
    submitLabel: 'Import Page',
    hasUrl: true,
    hasFile: false,
    hasMaxEpisodes: false,
  },
  [SourceType.GITHUB]: {
    displayName: 'GitHub',
    description: "Import a GitHub repository's content.",
    urlLabel: 'Repository URL',
    urlPlaceholder: 'https://github.com/owner/repo',
    submitLabel: 'Import Repo',
    hasUrl: true,
    hasFile: false,
    hasMaxEpisodes: false,
  },
  [SourceType.TEAMS]: {
    displayName: 'Teams',
    description: 'Upload exported Teams data (JSON or CSV).',
    urlLabel: '',
    urlPlaceholder: '',
    submitLabel: 'Upload & Import',
    hasUrl: false,
    hasFile: true,
    hasMaxEpisodes: false,
    fileAccept: '.json,.csv',
  },
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
  const [sourceType, setSourceType] = useState<string>(SourceType.PODCAST);
  const [url, setUrl] = useState('');
  const [name, setName] = useState('');
  const [maxEpisodes, setMaxEpisodes] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [useFileUpload, setUseFileUpload] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [connectors, setConnectors] = useState<ConnectorInfo[]>([]);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (open) {
      getConnectors()
        .then(setConnectors)
        .catch(() => setConnectors([]));
    }
  }, [open]);

  const config = TYPE_CONFIG[sourceType] ?? TYPE_CONFIG[SourceType.PODCAST];
  const connector = connectors.find((c) => c.type === sourceType);
  const isDisabled = connector ? !connector.enabled : false;

  function handleUrlChange(value: string) {
    setUrl(value);
    const derived = nameFromUrl(value);
    if (derived && !name) {
      setName(derived);
    }
  }

  function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const selected = e.target.files?.[0] ?? null;
    setFile(selected);
    if (selected && !name) {
      setName(selected.name.replace(/\.[^.]+$/, '').replace(/[-_]/g, ' '));
    }
  }

  function resetForm() {
    setSourceType(SourceType.PODCAST);
    setUrl('');
    setName('');
    setMaxEpisodes('');
    setFile(null);
    setUseFileUpload(true);
    setSubmitting(false);
    if (fileInputRef.current) fileInputRef.current.value = '';
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();

    if (!name.trim()) {
      toast.error('Please provide a name');
      return;
    }

    const needsFile = config.hasFile && useFileUpload;
    const needsUrl = config.hasUrl && (!config.hasFile || !useFileUpload);

    if (needsFile && !file) {
      toast.error('Please select a file');
      return;
    }
    if (needsUrl && !url.trim()) {
      toast.error('Please provide a URL');
      return;
    }

    setSubmitting(true);
    try {
      let response;
      if (needsFile && file) {
        response = await uploadFile(file, name.trim(), sourceType);
      } else {
        const parsed = maxEpisodes ? parseInt(maxEpisodes, 10) : undefined;
        response = await createSource({
          sourceType,
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
            <DialogDescription>{config.description}</DialogDescription>
          </DialogHeader>

          <div className="mt-4 space-y-4">
            <div className="space-y-2">
              <label htmlFor="import-type" className="text-sm font-medium">
                Source Type
              </label>
              <Select
                value={sourceType}
                onValueChange={(v) => {
                  setSourceType(v);
                  setUrl('');
                  setFile(null);
                  setUseFileUpload(true);
                  if (fileInputRef.current) fileInputRef.current.value = '';
                }}
                disabled={submitting}
              >
                <SelectTrigger id="import-type">
                  <SelectValue placeholder="Select type" />
                </SelectTrigger>
                <SelectContent>
                  {Object.entries(TYPE_CONFIG).map(([type, cfg]) => {
                    const conn = connectors.find((c) => c.type === type);
                    const disabled = conn ? !conn.enabled : false;
                    return (
                      <SelectItem key={type} value={type} disabled={disabled}>
                        {cfg.displayName}
                        {disabled && ' (not enabled)'}
                      </SelectItem>
                    );
                  })}
                </SelectContent>
              </Select>
            </div>

            {isDisabled && (
              <p className="text-muted-foreground rounded border border-dashed p-3 text-sm">
                This connector is not enabled. Enable it in your server configuration
                (application.yml) and restart.
              </p>
            )}

            {!isDisabled && (
              <>
                {config.hasFile && config.hasUrl && (
                  <div className="flex gap-2">
                    <Button
                      type="button"
                      variant={useFileUpload ? 'default' : 'outline'}
                      size="sm"
                      onClick={() => setUseFileUpload(true)}
                    >
                      Upload File
                    </Button>
                    <Button
                      type="button"
                      variant={!useFileUpload ? 'default' : 'outline'}
                      size="sm"
                      onClick={() => setUseFileUpload(false)}
                    >
                      From URL
                    </Button>
                  </div>
                )}

                {config.hasFile && (useFileUpload || !config.hasUrl) && (
                  <div className="space-y-2">
                    <label htmlFor="import-file" className="text-sm font-medium">
                      File
                    </label>
                    <Input
                      id="import-file"
                      ref={fileInputRef}
                      type="file"
                      accept={config.fileAccept}
                      onChange={handleFileChange}
                      disabled={submitting}
                    />
                  </div>
                )}

                {config.hasUrl && (!config.hasFile || !useFileUpload) && (
                  <div className="space-y-2">
                    <label htmlFor="import-url" className="text-sm font-medium">
                      {config.urlLabel}
                    </label>
                    <Input
                      id="import-url"
                      placeholder={config.urlPlaceholder}
                      value={url}
                      onChange={(e) => handleUrlChange(e.target.value)}
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

                {config.hasMaxEpisodes && (
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
              </>
            )}
          </div>

          <DialogFooter className="mt-6">
            <Button type="submit" disabled={submitting || isDisabled}>
              {submitting ? 'Importing...' : config.submitLabel}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
