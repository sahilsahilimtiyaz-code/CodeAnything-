export type LogLevel = "debug" | "info" | "warn" | "error";

export interface Logger {
  debug(...args: unknown[]): void;
  info(...args: unknown[]): void;
  warn(...args: unknown[]): void;
  error(...args: unknown[]): void;
}

export function createLogger(prefix = "intel"): Logger {
  const tag = `[${prefix}]`;
  return {
    debug: (...a) => console.debug(tag, ...a),
    info: (...a) => console.info(tag, ...a),
    warn: (...a) => console.warn(tag, ...a),
    error: (...a) => console.error(tag, ...a),
  };
}

export const defaultLogger: Logger = createLogger("intel");
