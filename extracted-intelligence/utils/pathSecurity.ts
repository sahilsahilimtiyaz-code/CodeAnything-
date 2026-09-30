import path from "node:path";
import fs from "node:fs/promises";

/** Resolve + sandbox helpers shared by FilesystemManager and tools. */
export function resolveAllowedDirs(dirs: string[]): string[] {
  return dirs.map((d) => path.resolve(d));
}

export function isPathAllowed(
  candidate: string,
  allowedDirs: string[]
): boolean {
  const abs = path.resolve(candidate);
  return allowedDirs.some((d) => abs === d || abs.startsWith(d + path.sep));
}

/** Resolve a user-supplied path against the first allowed dir (workspace root). */
export function resolveInSandbox(
  userPath: string,
  allowedDirs: string[]
): string {
  if (path.isAbsolute(userPath)) return path.resolve(userPath);
  return path.resolve(allowedDirs[0] ?? process.cwd(), userPath);
}

/** Validate a path: inside sandbox + symlink-safe. Returns real absolute path. */
export async function validateSandboxPath(
  userPath: string,
  allowedDirs: string[],
  opts: { allowMissing?: boolean } = {}
): Promise<string> {
  if (allowedDirs.length === 0) throw new Error("FilesystemManager not initialized");
  const abs = resolveInSandbox(userPath, allowedDirs);
  if (!isPathAllowed(abs, allowedDirs)) {
    throw new Error("Access denied – path outside allowed directories.");
  }
  if (opts.allowMissing) return abs;
  try {
    const real = await fs.realpath(abs);
    if (!isPathAllowed(real, allowedDirs)) {
      throw new Error("Access denied – symlink target outside allowed directories.");
    }
    return real;
  } catch (e: any) {
    if (e?.code === "ENOENT") return abs;
    throw e;
  }
}
