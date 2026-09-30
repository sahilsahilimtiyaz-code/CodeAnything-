import fs from "node:fs/promises";
import path from "node:path";
import { randomBytes } from "node:crypto";
import {
  isPathAllowed,
  resolveInSandbox,
  validateSandboxPath,
} from "../../utils/pathSecurity.js";

export interface FileEntry {
  name: string;
  path: string;
  isDirectory: boolean;
  size?: number;
}

/**
 * Sandboxed filesystem access. All paths go through validateSandboxPath.
 * Writes are atomic (tmp + rename).
 */
export class FilesystemManager {
  private allowed: string[] = [];
  private init = false;

  async initialize(dirs: string[]): Promise<void> {
    this.allowed = dirs.map((d) => path.resolve(d));
    for (const d of this.allowed) await fs.mkdir(d, { recursive: true });
    this.init = true;
  }

  get allowedDirectories(): string[] {
    return [...this.allowed];
  }

  isPathAllowed(p: string): boolean {
    return isPathAllowed(path.resolve(p), this.allowed);
  }

  async resolve(userPath: string, allowMissing = false): Promise<string> {
    if (!this.init) throw new Error("FilesystemManager not initialized");
    return validateSandboxPath(userPath, this.allowed, { allowMissing });
  }

  async listDirectory(dirPath: string): Promise<FileEntry[]> {
    const abs = await this.resolve(dirPath);
    const entries = await fs.readdir(abs, { withFileTypes: true });
    return Promise.all(
      entries.map(async (e) => {
        const full = path.join(abs, e.name);
        let size: number | undefined;
        try {
          if (e.isFile()) size = (await fs.stat(full)).size;
        } catch { /* ignore */ }
        return { name: e.name, path: full, isDirectory: e.isDirectory(), size };
      })
    );
  }

  async readFile(filePath: string): Promise<string> {
    const abs = await this.resolve(filePath);
    return fs.readFile(abs, "utf-8");
  }

  async writeFile(filePath: string, content: string): Promise<void> {
    const abs = await this.resolve(filePath, true);
    await fs.mkdir(path.dirname(abs), { recursive: true });
    const tmp = `${abs}.${randomBytes(8).toString("hex")}.tmp`;
    await fs.writeFile(tmp, content, "utf-8");
    await fs.rename(tmp, abs);
  }

  async deletePath(target: string, recursive = false): Promise<void> {
    const abs = await this.resolve(target);
    await fs.rm(abs, { recursive, force: false });
  }

  async createDirectory(dirPath: string): Promise<void> {
    const abs = await this.resolve(dirPath, true);
    await fs.mkdir(abs, { recursive: true });
  }

  async stat(target: string): Promise<{ isDirectory: boolean; size: number; mtime: string }> {
    const abs = await this.resolve(target);
    const st = await fs.stat(abs);
    return { isDirectory: st.isDirectory(), size: st.size, mtime: st.mtime.toISOString() };
  }

  async search(pattern: string, dir = ".", maxResults = 50): Promise<string[]> {
    const root = await this.resolve(dir);
    const out: string[] = [];
    const rx = new RegExp(pattern);
    const walk = async (cur: string): Promise<void> => {
      if (out.length >= maxResults) return;
      const entries = await fs.readdir(cur, { withFileTypes: true });
      for (const e of entries) {
        if (out.length >= maxResults) return;
        const full = path.join(cur, e.name);
        if (!isPathAllowed(full, this.allowed)) continue;
        if (e.isDirectory()) await walk(full);
        else if (rx.test(e.name) || rx.test(full)) out.push(full);
      }
    };
    await walk(root);
    return out;
  }

  async grep(query: string, dir = ".", maxResults = 50): Promise<string[]> {
    const root = await this.resolve(dir);
    const out: string[] = [];
    const walk = async (cur: string): Promise<void> => {
      if (out.length >= maxResults) return;
      let entries;
      try {
        entries = await fs.readdir(cur, { withFileTypes: true });
      } catch { return; }
      for (const e of entries) {
        if (out.length >= maxResults) return;
        const full = path.join(cur, e.name);
        if (!isPathAllowed(full, this.allowed)) continue;
        if (e.isDirectory()) {
          if (e.name === "node_modules" || e.name === ".git") continue;
          await walk(full);
        } else if (e.isFile()) {
          try {
            const st = await fs.stat(full);
            if (st.size > 512_000) continue;
            const text = await fs.readFile(full, "utf-8");
            const lines = text.split("\n");
            lines.forEach((line, i) => {
              if (out.length < maxResults && line.includes(query)) {
                const rel = path.relative(resolveInSandbox(".", this.allowed), full);
                out.push(`${rel}:${i + 1}: ${line.slice(0, 240)}`);
              }
            });
          } catch { /* binary / unreadable */ }
        }
      }
    };
    await walk(root);
    return out;
  }
}
