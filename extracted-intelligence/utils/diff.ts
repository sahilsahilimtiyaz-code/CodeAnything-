/** Minimal dependency-free unified diff (exact for single contiguous replacements). */
export function unifiedDiff(
  fileName: string,
  oldContent: string,
  newContent: string
): string {
  const a = oldContent.split("\n");
  const b = newContent.split("\n");
  let prefix = 0;
  while (prefix < a.length && prefix < b.length && a[prefix] === b[prefix]) prefix++;
  let suffix = 0;
  while (
    suffix < a.length - prefix &&
    suffix < b.length - prefix &&
    a[a.length - 1 - suffix] === b[b.length - 1 - suffix]
  ) {
    suffix++;
  }
  const ctx = 3;
  const aStart = Math.max(0, prefix - ctx);
  const bStart = Math.max(0, prefix - ctx);
  const aEnd = Math.min(a.length, a.length - suffix + ctx);
  const bEnd = Math.min(b.length, b.length - suffix + ctx);
  const out: string[] = [
    `--- ${fileName}\tbefore`,
    `+++ ${fileName}\tafter`,
    `@@ -${aStart + 1},${aEnd - aStart} +${bStart + 1},${bEnd - bStart} @@`,
  ];
  for (let i = aStart; i < prefix; i++) out.push(` ${a[i]}`);
  for (let i = prefix; i < a.length - suffix; i++) out.push(`-${a[i]}`);
  for (let i = prefix; i < b.length - suffix; i++) out.push(`+${b[i]}`);
  for (let i = a.length - suffix; i < aEnd; i++) out.push(` ${a[i]}`);
  void bEnd;
  return out.join("\n");
}
