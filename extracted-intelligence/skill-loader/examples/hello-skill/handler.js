/**
 * Example imported skill handler.
 * Any skill dropped into a folder with plugin.json + handler.js can be loaded.
 */

export const tools = [
  {
    name: "hello_greet",
    description: "Return a friendly greeting. Optional name parameter.",
    parameters: {
      type: "object",
      properties: {
        name: { type: "string", description: "Name to greet" },
      },
    },
    riskLevel: "low",
    async execute(args) {
      const name = args.name ? String(args.name) : "world";
      return `Hello, ${name}! This message comes from an imported skill.`;
    },
  },
  {
    name: "hello_echo",
    description: "Echo back the provided text.",
    parameters: {
      type: "object",
      properties: {
        text: { type: "string", description: "Text to echo" },
      },
      required: ["text"],
    },
    riskLevel: "low",
    async execute(args) {
      return `Echo: ${String(args.text ?? "")}`;
    },
  },
];
