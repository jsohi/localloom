'use client';

import { useState } from 'react';
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
import { createSource } from '@/lib/api';
import { SourceType } from '@/lib/types';

interface ImportDialogProps {
  readonly onImported: () => void;
}

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
  const [url, setUrl] = useState('');
  const [name, setName] = useState('');
  const [sourceType, setSourceType] = useState<string>(SourceType.PODCAST);
  const [submitting, setSubmitting] = useState(false);

  function handleUrlChange(value: string) {
    setUrl(value);
    const derived = nameFromUrl(value);
    if (derived && !name) {
      setName(derived);
    }
  }

  function resetForm() {
    setUrl('');
    setName('');
    setSourceType(SourceType.PODCAST);
    setSubmitting(false);
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();

    if (!url.trim() || !name.trim()) {
      toast.error('Please fill in all required fields');
      return;
    }

    setSubmitting(true);
    try {
      const response = await createSource({
        sourceType,
        name: name.trim(),
        originUrl: url.trim(),
      });
      toast.success(`Source created! Job ${response.job_id} started.`);
      resetForm();
      setOpen(false);
      onImported();
    } catch {
      toast.error('Failed to create source. Check the URL and try again.');
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
          Import Podcast
        </Button>
      </DialogTrigger>
      <DialogContent className="sm:max-w-md">
        <form onSubmit={handleSubmit}>
          <DialogHeader>
            <DialogTitle>Import Source</DialogTitle>
            <DialogDescription>
              Paste a podcast RSS feed URL or other source URL to import.
            </DialogDescription>
          </DialogHeader>

          <div className="mt-4 space-y-4">
            <div className="space-y-2">
              <label htmlFor="import-url" className="text-sm font-medium">
                URL
              </label>
              <Input
                id="import-url"
                placeholder="https://feeds.example.com/podcast.xml"
                value={url}
                onChange={(e) => handleUrlChange(e.target.value)}
                disabled={submitting}
              />
            </div>

            <div className="space-y-2">
              <label htmlFor="import-name" className="text-sm font-medium">
                Name
              </label>
              <Input
                id="import-name"
                placeholder="My Podcast"
                value={name}
                onChange={(e) => setName(e.target.value)}
                disabled={submitting}
              />
            </div>

            <div className="space-y-2">
              <label htmlFor="import-type" className="text-sm font-medium">
                Source Type
              </label>
              <Select value={sourceType} onValueChange={setSourceType} disabled={submitting}>
                <SelectTrigger id="import-type">
                  <SelectValue placeholder="Select type" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={SourceType.PODCAST}>Podcast</SelectItem>
                  <SelectItem value={SourceType.FILE_UPLOAD}>File Upload</SelectItem>
                  <SelectItem value={SourceType.CONFLUENCE}>Confluence</SelectItem>
                  <SelectItem value={SourceType.GITHUB}>GitHub</SelectItem>
                  <SelectItem value={SourceType.TEAMS}>Teams</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>

          <DialogFooter className="mt-6">
            <Button type="submit" disabled={submitting}>
              {submitting ? 'Importing...' : 'Import'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
