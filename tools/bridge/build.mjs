import { build } from "esbuild";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync, statSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const outfile = join(here, "..", "..", "app", "src", "main", "assets", "bridge", "bridge.js");

await build({
  entryPoints: [join(here, "src", "bridge.mjs")],
  bundle: true,
  format: "iife",
  platform: "browser",
  target: "es2020",
  minify: true,
  sourcemap: false,
  define: {
    "process.env.NODE_ENV": '"production"',
    global: "window"
  },
  inject: [join(here, "src", "shims", "buffer.mjs")],
  legalComments: "none",
  outfile
});

const bytes = readFileSync(outfile);
const sha256 = createHash("sha256").update(bytes).digest("hex");
writeFileSync(`${outfile}.sha256`, sha256);

const size = statSync(outfile).size;
console.log(`built ${outfile}`);
console.log(`size: ${size} bytes`);
console.log(`sha256: ${sha256}`);
