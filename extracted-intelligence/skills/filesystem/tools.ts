import { unifiedDiff } from "../../utils/diff.js";
import type { SkillTool } from "../types.js";
import type { FilesystemManager } from "./manager.js";

const str = (v: unknown, fb = ""): string => (typeof v === "string" ? v : fb);

/** 9 filesystem tools with risk levels. All results are strings. */
export function filesystemTools(fsManager: FilesystemManager): SkillTool[] {
  return [
    {
      name: "list_directory",
      description: "List files in a sandboxed directory.",
      parameters: { type: "object", properties: { path: { type: "string" } }, required: ["path"] },
      riskLevel: "low",
      async execute(args) {
        const entries = await fsManager.listDirectory(str(args.path, "."));
        if (!entries.length) return "Directory is empty.";
        return entries.map((e) => `${e.isDirectory ? "DIR " : "FILE"}  ${e.name}${e.size != null && !e.isDirectory ? `  (${e.size}b)` : ""}`).join("\n");
      },
    },
    {
      name: "read_file",
      description: "Read a UTF-8 text file inside the sandbox.",
      parameters: { type: "object", properties: { path: { type: "string" } }, required: ["path"] },
      riskLevel: "low",
      async execute(args) {
        return fsManager.readFile(str(args.path));
      },
    },
    {
      name: "write_file",
      description: "Atomically write a file inside the sandbox. HIGH risk: needs approval.",
      parameters: {
        type: "object",
        properties: { path: { type: "string" }, content: { type: "string" } },
        required: ["path", "content"],
      },
      riskLevel: "high",
      async execute(args) {
        await fsManager.writeFile(str(args.path), str(args.content));
        return `Wrote ${str(args.path)} (${str(args.content).length} chars).`;
      },
    },
    {
      name: "edit_file",
      description: "Replace exact oldText with newText in a file. Returns unified diff. HIGH risk.",
      parameters: {
        type: "object",
        properties: {
          path: { type: "string" },
          oldText: { type: "string" },
          newText: { type: "string" },
        },
        required: ["path", "oldText", "newText"],
      },
      riskLevel: "high",
      async execute(args) {
        const p = str(args.path);
        const oldText = str(args.oldText);
        const newText = str(args.newText);
        const current = await fsManager.readFile(p);
        if (!current.includes(oldText)) return "Error: oldText not found in file – no changes made.";
        const updated = current.replace(oldText, newText);
        await fsManager.writeFile(p, updated);
        return unifiedDiff(p, current, updated);
      },
    },
    {
      name: "create_directory",
      description: "Create a directory (recursive) inside the sandbox.",
      parameters: { type: "object", properties: { path: { type: "string" } }, required: ["path"] },
      riskLevel: "medium",
      async execute(args) {
        await fsManager.createDirectory(str(args.path));
        return `Created directory ${str(args.path)}.`;
      },
    },
    {
      name: "delete_path",
      description: "Delete a file or (with recursive=true) a directory. HIGH risk.",
      parameters: {
        type: "object",
        properties: { path: { type: "string" }, recursive: { type: "boolean" } },
        required: ["path"],
      },
      riskLevel: "high",
      async execute(args) {
        await fsManager.deletePath(str(args.path), args.recursive === true);
        return `Deleted ${str(args.path)}.`;
      },
    },
    {
      name: "file_stat",
      description: "Stat a file (size, mtime, isDirectory).",
      parameters: { type: "object", properties: { path: { type: "string" } }, required: ["path"] },
      riskLevel: "low",
      async execute(args) {
        const s = await fsManager.stat(str(args.path));
        return `isDirectory=${s.isDirectory} size=${s.size} mtime=${s.mtime}`;
      },
    },
    {
      name: "search_files",
      description: "Search file names/paths by regex inside the sandbox.",
      parameters: {
        type: "object",
        properties: { pattern: { type: "string" }, dir: { type: "string" } },
        required: ["pattern"],
      },
      riskLevel: "low",
      async execute(args) {
        const hits = await fsManager.search(str(args.pattern, ".*"), str((args as any).dir ?? ".", "."));
        return hits.length ? hits.join("\n") : "No matches.";
      },
    },
    {
      name: "grep_content",
      description: "Grep file contents (substring) inside the sandbox.",
      parameters: {
        type: "object",
        properties: { query: { type: "string" }, dir: { type: "string" } },
        required: ["query"],
      },
      riskLevel: "low",
      async execute(args) {
        const hits = await fsManager.grep(str(args.query), str((args as any).dir ?? ".", "."));
        return hits.length ? hits.join("\n") : "No matches.";
      },
    },
  ];
}
