import type { Skill, SkillTool } from "./types.js";

/** Central registry: builtin skills + imported plugin skills + MCP tools. */
export class SkillRegistry {
  private skills = new Map<string, Skill>();
  private tools = new Map<string, SkillTool>();

  register(skill: Skill): void {
    this.skills.set(skill.name, skill);
    for (const t of skill.tools) this.tools.set(t.name, t);
  }

  unregister(skillName: string): void {
    const s = this.skills.get(skillName);
    if (!s) return;
    for (const t of s.tools) this.tools.delete(t.name);
    this.skills.delete(skillName);
  }

  getTool(name: string): SkillTool | undefined {
    return this.tools.get(name);
  }

  allTools(): SkillTool[] {
    return [...this.tools.values()];
  }

  toolDefinitions(): { name: string; description: string; parameters: Record<string, unknown> }[] {
    return this.allTools().map((t) => ({ name: t.name, description: t.description, parameters: t.parameters }));
  }

  skillNames(): string[] {
    return [...this.skills.keys()];
  }
}
