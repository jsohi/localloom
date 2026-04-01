import type {
  Source,
  Job,
  Conversation,
  Message,
  ConnectorInfo,
  ContentFragment,
  CreateSourceResponse,
  DetectUrlResponse,
  SyncSourceResponse,
  SourceDetailResponse,
  LlmHealthResponse,
  ModelInfo,
} from './types';
import { logger } from './logger';

const API_BASE = '/api/v1';

export async function fetchApi<T>(path: string, options?: RequestInit): Promise<T> {
  const url = `${API_BASE}${path.startsWith('/') ? path : `/${path}`}`;
  const response = await fetch(url, {
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers,
    },
    ...options,
  });

  if (!response.ok) {
    const msg = `API ${options?.method ?? 'GET'} ${url} failed: ${response.status} ${response.statusText}`;
    if (response.status === 404) {
      logger.warn(msg);
    } else {
      logger.error(msg);
    }
    throw new Error(`API error ${response.status}: ${response.statusText}`);
  }

  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

// Connectors

export function getConnectors(): Promise<ConnectorInfo[]> {
  return fetchApi<ConnectorInfo[]>('/connectors');
}

// Sources

export function getSources(): Promise<Source[]> {
  return fetchApi<Source[]>('/sources');
}

export function getSource(id: string): Promise<SourceDetailResponse> {
  return fetchApi<SourceDetailResponse>(`/sources/${id}`);
}

export function detectUrl(url: string): Promise<DetectUrlResponse> {
  return fetchApi<DetectUrlResponse>('/sources/detect-url', {
    method: 'POST',
    body: JSON.stringify({ url }),
  });
}

export function createSource(body: {
  sourceType?: string;
  name: string;
  originUrl: string;
  config?: string;
  maxEpisodes?: number;
}): Promise<CreateSourceResponse> {
  return fetchApi<CreateSourceResponse>('/sources', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export async function uploadFile(
  file: File,
  name: string,
  sourceType: string,
): Promise<CreateSourceResponse> {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('name', name);
  formData.append('sourceType', sourceType);
  const response = await fetch(`${API_BASE}/sources/upload`, {
    method: 'POST',
    body: formData,
  });
  if (!response.ok) {
    logger.error(`Upload failed: ${response.status} ${response.statusText}`);
    throw new Error(`Upload failed: ${response.status}`);
  }
  return response.json() as Promise<CreateSourceResponse>;
}

export function syncSource(id: string): Promise<SyncSourceResponse> {
  return fetchApi<SyncSourceResponse>(`/sources/${id}/sync`, {
    method: 'POST',
  });
}

export function deleteSource(id: string): Promise<void> {
  return fetchApi<void>(`/sources/${id}`, { method: 'DELETE' });
}

// Returns [] on error — the fragments endpoint doesn't exist yet,
// so 404 is expected. TranscriptViewer falls back to rawText.
export async function getContentFragments(contentUnitId: string): Promise<ContentFragment[]> {
  try {
    const url = `${API_BASE}/content-units/${contentUnitId}/fragments`;
    const response = await fetch(url);
    if (!response.ok) return [];
    return (await response.json()) as ContentFragment[];
  } catch {
    return [];
  }
}

// Jobs

export function getJobs(): Promise<Job[]> {
  return fetchApi<Job[]>('/jobs');
}

export function getJob(id: string): Promise<Job> {
  return fetchApi<Job>(`/jobs/${id}`);
}

// Conversations

export function getConversations(): Promise<Conversation[]> {
  return fetchApi<Conversation[]>('/conversations');
}

export function getConversation(id: string): Promise<Conversation & { messages: Message[] }> {
  return fetchApi<Conversation & { messages: Message[] }>(`/conversations/${id}`);
}

export function deleteConversation(id: string): Promise<void> {
  return fetchApi<void>(`/conversations/${id}`, { method: 'DELETE' });
}

// Chat (SSE streaming)

export interface Citation {
  sourceType: string;
  contentUnitTitle: string;
  location: string;
  sourceId: string;
  contentUnitId: string;
}

export interface StreamQueryOptions {
  question: string;
  conversationId?: string;
  sourceIds?: string[];
  sourceTypes?: string[];
  onToken: (content: string) => void;
  onSources: (sources: Citation[]) => void;
  onDone: (messageId: string, conversationId?: string) => void;
  onError: (error: Error) => void;
}

export function streamQuery(opts: StreamQueryOptions): AbortController {
  const controller = new AbortController();

  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;

  controller.signal.addEventListener('abort', () => {
    reader?.cancel();
  });

  fetch(`${API_BASE}/query`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({
      question: opts.question,
      conversationId: opts.conversationId,
      sourceIds: opts.sourceIds,
      sourceTypes: opts.sourceTypes,
    }),
    signal: controller.signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        throw new Error(`Query failed: ${response.status}`);
      }
      reader = response.body?.getReader();
      if (!reader) throw new Error('No response body');

      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';

        let currentEvent = '';
        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim();
          } else if (line.startsWith('data:')) {
            const data = line.slice(5).trim();
            if (!data) continue;
            try {
              const parsed = JSON.parse(data);
              if (currentEvent === 'token') opts.onToken(parsed.content);
              else if (currentEvent === 'sources') opts.onSources(parsed.sources ?? []);
              else if (currentEvent === 'done')
                opts.onDone(parsed.messageId, parsed.conversationId);
            } catch {
              // Skip malformed data
            }
          } else if (line.trim() === '') {
            currentEvent = '';
          }
        }
      }
    })
    .catch((err) => {
      if (err.name !== 'AbortError') {
        logger.error('Stream query failed:', err.message);
        opts.onError(err);
      }
    });

  return controller;
}

// TTS

export interface TtsResponse {
  audio_url: string;
}

export function generateTts(messageId: string): Promise<TtsResponse> {
  return fetchApi<TtsResponse>(`/messages/${messageId}/tts`, {
    method: 'POST',
  });
}

// Models / Health

export function getLlmHealth(): Promise<LlmHealthResponse> {
  return fetchApi<LlmHealthResponse>('/models/llm/health');
}

export function getModels(): Promise<ModelInfo[]> {
  return fetchApi<ModelInfo[]>('/models/llm');
}
