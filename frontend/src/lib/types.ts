// Enums

export enum SourceType {
  PODCAST = 'PODCAST',
  CONFLUENCE = 'CONFLUENCE',
  TEAMS = 'TEAMS',
  GITHUB = 'GITHUB',
  FILE_UPLOAD = 'FILE_UPLOAD',
}

export enum ContentType {
  AUDIO = 'AUDIO',
  PAGE = 'PAGE',
  MESSAGE_THREAD = 'MESSAGE_THREAD',
  CODE_FILE = 'CODE_FILE',
  TEXT_FILE = 'TEXT_FILE',
}

export enum JobStatus {
  PENDING = 'PENDING',
  RUNNING = 'RUNNING',
  COMPLETED = 'COMPLETED',
  FAILED = 'FAILED',
}

export enum SyncStatus {
  IDLE = 'IDLE',
  SYNCING = 'SYNCING',
  ERROR = 'ERROR',
}

export enum ContentUnitStatus {
  PENDING = 'PENDING',
  FETCHING = 'FETCHING',
  TRANSCRIBING = 'TRANSCRIBING',
  EXTRACTING = 'EXTRACTING',
  EMBEDDING = 'EMBEDDING',
  INDEXED = 'INDEXED',
  ERROR = 'ERROR',
}

// Entities — field names match Spring Boot's default camelCase JSON serialization

export interface Source {
  id: string;
  name: string;
  description?: string;
  sourceType: SourceType;
  originUrl?: string;
  iconUrl?: string;
  config?: string;
  syncStatus: SyncStatus;
  lastSyncedAt?: string;
  createdAt: string;
  contentUnits?: ContentUnit[];
}

export interface ContentUnit {
  id: string;
  title?: string;
  contentType: ContentType;
  externalId?: string;
  externalUrl?: string;
  status: ContentUnitStatus;
  rawText?: string;
  metadata?: string;
  publishedAt?: string;
  createdAt: string;
}

export interface ContentFragment {
  id: number;
  fragmentType: string;
  sequenceIndex: number;
  text: string;
  location?: string;
}

export interface Job {
  id: string;
  type: string;
  entityId: string;
  entityType: string;
  status: JobStatus;
  progress: number;
  errorMessage?: string;
  createdAt: string;
  completedAt?: string;
}

export interface Conversation {
  id: string;
  title?: string;
  createdAt: string;
  updatedAt: string;
}

export interface Message {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  sources?: string;
  audioPath?: string;
  createdAt: string;
}

// API response shapes — these use snake_case (manually built via Map.of in controllers)

export interface CreateSourceResponse {
  source_id: string;
  job_id: string;
}

export interface SyncSourceResponse {
  source_id: string;
  job_id: string;
}

export interface SourceDetailResponse {
  source: Source;
  contentUnits: ContentUnit[];
}

export interface ConnectorInfo {
  type: SourceType;
  name: string;
  enabled: boolean;
  configured: boolean;
}

// Settings / Models

export interface LlmHealthResponse {
  status: string;
  model?: string;
}

export interface ModelInfo {
  name: string;
  modifiedAt?: string;
  size?: number;
  digest?: string;
  details?: {
    format?: string;
    family?: string;
    parameterSize?: string;
    quantizationLevel?: string;
  };
}
