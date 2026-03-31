const LEVEL = (process.env.LOG_LEVEL || 'info').toLowerCase();
const LEVELS: Record<string, number> = { debug: 0, info: 1, warn: 2, error: 3 };

function log(level: string, ...args: unknown[]) {
  if ((LEVELS[level] ?? 1) < (LEVELS[LEVEL] ?? 1)) return;
  const ts = new Date().toISOString();
  const prefix = `${ts} [frontend] ${level.toUpperCase().padEnd(5)} -`;
  const method = level === 'error' ? 'error' : level === 'warn' ? 'warn' : 'log';
  console[method](prefix, ...args);
}

export const logger = {
  debug: (...args: unknown[]) => log('debug', ...args),
  info: (...args: unknown[]) => log('info', ...args),
  warn: (...args: unknown[]) => log('warn', ...args),
  error: (...args: unknown[]) => log('error', ...args),
};
