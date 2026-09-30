import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";
import type { Skill } from "../skills/types.js";

export interface ImportedSkillManifest {
  name: string;
  hubId: string;
  description?: string;
  version?: string;
}

/** Load plugin.json + handler.js skills (AnythingLLM imported-agent-plugin style). */
export async function loadSkillFromDir(dir: string): Promise<Skill> {
  const manifestRaw = await fs.readFile(path.join(dir, "plugin.json"), "utf-8");
  const manifest = JSON.parse(manifestRaw) as ImportedSkillManifest;
  if (!manifest.hubId) throw new Error(`Skill in ${dir} missing hubId`);
  const handlerUrl = pathToFileURL(path.join(dir, "handler.js")).href;
  const mod = (await import(handlerUrl)) as { tools?: any[] };
  const tools = Array.isArray(mod.tools) ? mod.tools : [];
  return {
    name: manifest.hubId,
    description: manifest.description ?? manifest.name ?? manifest.hubId,
    tools: tools.map((t) => ({
      name: String(t.name),
      description: String(t.description ?? ""),
      parameters: (t.parameters as Record<string, unknown>) ?? { type: "object", properties: {} },
      riskLevel: (t.riskLevel as "low" | "medium" | "high") ?? "low",
      execute: async (args: Record<string, unknown>, ctx: any): Promise<string> =>
        String(await t.execute(args, ctx)),
    })),
  };
}

export async function loadSkillsFromRoot(root: string): Promise<Skill[]> {
  const out: Skill[] = [];
  let entries;
  try {
    entries = await fs.readdir(root, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const e of entries) {
    if (!e.isDirectory()) continue;
    try {
      out.push(await loadSkillFromDir(path.join(root, e.name)));
    } catch (err) {
      console.warn(`[skill-loader] skip ${e.name}:`, (err as Error).message);
    }
  }
  return out;
}
