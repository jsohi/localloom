'use client';

import { useEffect, useState } from 'react';
import {
  ActivityIcon,
  BrainIcon,
  DatabaseIcon,
  DownloadIcon,
  RefreshCwIcon,
} from 'lucide-react';
import { toast } from 'sonner';

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { getLlmHealth, getModels, getSources } from '@/lib/api';
import type { LlmHealthResponse, ModelInfo, Source } from '@/lib/types';

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return `${(bytes / Math.pow(1024, i)).toFixed(1)} ${units[i]}`;
}

function SectionSkeleton() {
  return (
    <Card>
      <CardHeader>
        <Skeleton className="h-5 w-40" />
        <Skeleton className="h-4 w-64" />
      </CardHeader>
      <CardContent className="space-y-3">
        <Skeleton className="h-4 w-full" />
        <Skeleton className="h-4 w-3/4" />
        <Skeleton className="h-4 w-1/2" />
      </CardContent>
    </Card>
  );
}

export function SettingsView() {
  const [health, setHealth] = useState<LlmHealthResponse | null>(null);
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [sources, setSources] = useState<Source[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [healthError, setHealthError] = useState(false);
  const [modelsError, setModelsError] = useState(false);

  useEffect(() => {
    async function fetchInitialData() {
      const results = await Promise.allSettled([getLlmHealth(), getModels(), getSources()]);
      const [healthResult, modelsResult, sourcesResult] = results as [
        PromiseSettledResult<LlmHealthResponse>,
        PromiseSettledResult<ModelInfo[]>,
        PromiseSettledResult<Source[]>,
      ];

      if (healthResult.status === 'fulfilled') {
        setHealth(healthResult.value);
        setHealthError(false);
      } else {
        setHealthError(true);
        toast.error('Failed to check LLM health. Is Ollama running?');
      }

      if (modelsResult.status === 'fulfilled') {
        setModels(modelsResult.value);
        setModelsError(false);
      } else {
        setModelsError(true);
        toast.error('Failed to load available models.');
      }

      if (sourcesResult.status === 'fulfilled') {
        setSources(sourcesResult.value);
      } else {
        toast.error('Failed to load sources.');
      }

      setLoading(false);
    }

    fetchInitialData();
  }, []);

  async function handleRefresh() {
    setRefreshing(true);
    try {
      const results = await Promise.allSettled([getLlmHealth(), getModels(), getSources()]);
      const [healthResult, modelsResult, sourcesResult] = results as [
        PromiseSettledResult<LlmHealthResponse>,
        PromiseSettledResult<ModelInfo[]>,
        PromiseSettledResult<Source[]>,
      ];

      if (healthResult.status === 'fulfilled') {
        setHealth(healthResult.value);
        setHealthError(false);
      } else {
        setHealthError(true);
        toast.error('Failed to check LLM health. Is Ollama running?');
      }

      if (modelsResult.status === 'fulfilled') {
        setModels(modelsResult.value);
        setModelsError(false);
      } else {
        setModelsError(true);
        toast.error('Failed to load available models.');
      }

      if (sourcesResult.status === 'fulfilled') {
        setSources(sourcesResult.value);
      } else {
        toast.error('Failed to load sources.');
      }
    } finally {
      setRefreshing(false);
    }
  }

  const isUp = health?.status === 'UP' || health?.status === 'ok';
  const totalContentUnits = sources.reduce((acc, s) => acc + (s.contentUnits?.length ?? 0), 0);
  const syncedSources = sources.filter((s) => s.syncStatus === 'IDLE').length;

  if (loading) {
    return (
      <div className="space-y-6">
        <SectionSkeleton />
        <SectionSkeleton />
        <SectionSkeleton />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* LLM Configuration */}
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <BrainIcon className="text-muted-foreground size-5" />
              <CardTitle>LLM Configuration</CardTitle>
            </div>
            <Button
              variant="outline"
              size="sm"
              onClick={handleRefresh}
              disabled={refreshing}
              aria-label="Refresh status"
            >
              <RefreshCwIcon className={`mr-2 size-4 ${refreshing ? 'animate-spin' : ''}`} />
              Refresh
            </Button>
          </div>
          <CardDescription>Manage local language models served by Ollama.</CardDescription>
        </CardHeader>
        <CardContent>
          {modelsError ? (
            <p className="text-destructive text-sm">
              Unable to load models. Check that Ollama is running.
            </p>
          ) : models.length === 0 ? (
            <p className="text-muted-foreground text-sm">No models found. Pull a model in Ollama to get started.</p>
          ) : (
            <div className="space-y-3">
              {models.map((model) => (
                <div
                  key={model.name}
                  className="flex items-center justify-between rounded-lg border p-3"
                >
                  <div className="space-y-1">
                    <div className="flex items-center gap-2">
                      <span className="font-medium">{model.name}</span>
                      {model.details?.parameterSize && (
                        <Badge variant="secondary">{model.details.parameterSize}</Badge>
                      )}
                      {model.details?.quantizationLevel && (
                        <Badge variant="outline">{model.details.quantizationLevel}</Badge>
                      )}
                    </div>
                    <div className="text-muted-foreground flex gap-3 text-xs">
                      {model.details?.family && <span>Family: {model.details.family}</span>}
                      {model.size != null && <span>Size: {formatBytes(model.size)}</span>}
                    </div>
                  </div>
                  <Button variant="outline" size="sm" disabled title="Not yet implemented">
                    <DownloadIcon className="mr-2 size-4" />
                    Pull
                  </Button>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* System Status */}
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <ActivityIcon className="text-muted-foreground size-5" />
            <CardTitle>System Status</CardTitle>
          </div>
          <CardDescription>Health of backend services and connections.</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="space-y-3">
            <div className="flex items-center justify-between rounded-lg border p-3">
              <div className="flex items-center gap-2">
                <span
                  className={`inline-block size-2 rounded-full ${
                    healthError ? 'bg-destructive' : isUp ? 'bg-green-500' : 'bg-yellow-500'
                  }`}
                />
                <span className="font-medium">Ollama LLM Service</span>
              </div>
              <Badge variant={healthError ? 'destructive' : isUp ? 'default' : 'secondary'}>
                {healthError ? 'Unreachable' : isUp ? 'Online' : 'Degraded'}
              </Badge>
            </div>
            {health?.model && (
              <p className="text-muted-foreground pl-4 text-sm">Active model: {health.model}</p>
            )}
          </div>
        </CardContent>
      </Card>

      {/* Storage & Data */}
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <DatabaseIcon className="text-muted-foreground size-5" />
            <CardTitle>Storage &amp; Data</CardTitle>
          </div>
          <CardDescription>Overview of indexed sources and stored content.</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="grid gap-4 sm:grid-cols-3">
            <div className="rounded-lg border p-3 text-center">
              <div className="text-2xl font-bold">{sources.length}</div>
              <div className="text-muted-foreground text-xs">Total Sources</div>
            </div>
            <div className="rounded-lg border p-3 text-center">
              <div className="text-2xl font-bold">{syncedSources}</div>
              <div className="text-muted-foreground text-xs">Synced Sources</div>
            </div>
            <div className="rounded-lg border p-3 text-center">
              <div className="text-2xl font-bold">{totalContentUnits}</div>
              <div className="text-muted-foreground text-xs">Content Units</div>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
