import type { Skill } from "../types.js";
import type { FilesystemManager } from "./manager.js";
import { filesystemTools } from "./tools.js";

export { FilesystemManager } from "./manager.js";
export { filesystemTools } from "./tools.js";

export function filesystemSkill(fsManager: FilesystemManager): Skill {
  return {
    name: "filesystem",
    description: "Sandboxed file operations (list/read/write/edit/stat/search/grep).",
    tools: filesystemTools(fsManager),
  };
}
