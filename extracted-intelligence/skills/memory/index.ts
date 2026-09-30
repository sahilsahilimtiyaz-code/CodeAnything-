import type { Skill } from "../types.js";

/** In-memory key/value store (per-process; Android persists sessions separately). */
export function memorySkill(store: Map<string, string> = new Map()): Skill {
  return {
    name: "memory",
    description: "Simple key/value memory.",
    tools: [
      {
        name: "memory_store",
        description: "Store a value under a key.",
        parameters: {
          type: "object",
          properties: { key: { type: "string" }, value: { type: "string" } },
          required: ["key", "value"],
        },
        riskLevel: "low",
        async execute(args) {
          store.set(String(args.key), String(args.value));
          return `Stored memory "${String(args.key)}"`;
        },
      },
      {
        name: "memory_recall",
        description: "Recall a value by key.",
        parameters: { type: "object", properties: { key: { type: "string" } }, required: ["key"] },
        riskLevel: "low",
        async execute(args) {
          const v = store.get(String(args.key));
          return v ?? `No memory for "${String(args.key)}"`;
        },
      },
      {
        name: "memory_list",
        description: "List all memory keys.",
        parameters: { type: "object", properties: {} },
        riskLevel: "low",
        async execute() {
          const keys = [...store.keys()];
          return keys.length ? keys.join("\n") : "Memory is empty.";
        },
      },
    ],
  };
}
