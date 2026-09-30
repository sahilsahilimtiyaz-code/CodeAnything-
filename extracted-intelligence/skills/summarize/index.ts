import type { Skill } from "../types.js";

/** Local extractive summarizer – no model call, keeps hard rule (no vector DB). */
export function summarizeSkill(): Skill {
  return {
    name: "summarize",
    description: "Summarize long text locally (first N sentences / truncation).",
    tools: [
      {
        name: "summarize_text",
        description: "Return a short extractive summary of the given text.",
        parameters: {
          type: "object",
          properties: { text: { type: "string" }, maxSentences: { type: "number" } },
          required: ["text"],
        },
        riskLevel: "low",
        async execute(args) {
          const text = String(args.text ?? "");
          const max = Math.max(1, Math.min(Number((args as any).maxSentences ?? 5), 20));
          const sentences = text.replace(/\s+/g, " ").split(/(?<=[.!?])\s+/).filter(Boolean);
          return sentences.slice(0, max).join(" ") || text.slice(0, 500);
        },
      },
    ],
  };
}
